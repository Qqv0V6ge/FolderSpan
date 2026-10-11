package com.folderspan.service.webui

import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

private class PlatformNetwork(val config: NetworkConnectionConfig, val callback: String, val session: WebUiPlatformSession) {
    val client = if (config.protocol == NetworkProtocol.SFTP.name) {
        SftpNetworkClient(config, hostKeyTrust = null, verifyHostKey = { key ->
            platformJson.decodeFromString<Boolean>(session.callback(callback, "sftp-host-key", platformJson.encodeToString(key)).decodeToString())
        })
    } else NetworkClientFactory.create(config)

    fun transferConnection() = PlatformNetwork(config.copy(sessionOwner = java.util.UUID.randomUUID().toString()), callback, session)
    suspend fun close() {
        FtpNetworkClient.closeOwner(config.sessionOwner)
        SftpNetworkClient.closeOwner(config.sessionOwner)
        SmbNetworkClient.closeOwner(config.sessionOwner)
        client.close()
    }
}
private class NetworkDirectory(val entries: Iterator<NetworkFileEntry>) { val mutex = Mutex() }
private class NetworkTransfer {
    val chunks = Channel<Pair<ByteArray, Long>>(1)
    val mutex = Mutex()
    lateinit var job: Deferred<Boolean>
}

internal suspend fun networkPlatformRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/networks")
    suspend fun connection(id: String) = (session.resource(id) as? PlatformNetwork)?.client ?: error("not a network handle")
    suspend fun transfer() = (session.resource(requireNotNull(request.query("handle"))) as? NetworkTransfer) ?: error("not a transfer handle")
    return when (request.method to operation) {
        "POST" to "" -> {
            val input = request.platformBody<WebUiNetworkOpen>()
            require(input.callbackId.length in 1..128)
            require(input.config.protocol in NetworkProtocol.entries.map { it.name })
            val config = input.config.copy(sessionOwner = java.util.UUID.randomUUID().toString())
            val resource = PlatformNetwork(config, input.callbackId, session)
            platformResponse(WebUiHandle(session.own(resource) { resource.close() }))
        }
        "POST" to "/directories" -> {
            val input = request.platformBody<WebUiNetworkPath>()
            // ponytail: native protocol clients return a List; page transport here, add native cursors if listings exceed host memory.
            val directory = NetworkDirectory(connection(input.handle).list(input.path).getOrThrow().iterator())
            platformResponse(WebUiHandle(session.own(directory) {}))
        }
        "GET" to "/directories" -> {
            val id = requireNotNull(request.query("handle"))
            val directory = session.resource(id) as? NetworkDirectory ?: error("not a network directory")
            val batch = directory.mutex.withLock {
                val entries = buildList { repeat(128) { if (directory.entries.hasNext()) add(directory.entries.next()) } }
                WebUiNetworkBatch(entries, !directory.entries.hasNext())
            }
            if (batch.finished) session.release(id)
            platformResponse(batch)
        }
        "POST" to "/mkdir", "POST" to "/create", "POST" to "/delete", "POST" to "/rename", "POST" to "/copy" -> {
            val input = request.platformBody<WebUiNetworkPath>()
            val client = connection(input.handle)
            val result = when (operation) {
                "/mkdir" -> client.createFolder(input.path)
                "/create" -> client.createFile(input.path)
                "/delete" -> client.delete(input.path, input.isDirectory)
                "/rename" -> client.rename(input.path, input.newPath)
                else -> client.copyFile(input.path, input.newPath)
            }
            platformResponse(result.getOrThrow())
        }
        "POST" to "/download", "POST" to "/upload" -> {
            val input = request.platformBody<WebUiNetworkPath>()
            val parent = session.resource(input.handle) as? PlatformNetwork ?: error("not a network handle")
            // A transfer owns its sockets so cancellation cannot leave a blocking native read alive.
            val owned = parent.transferConnection()
            val client = owned.client
            val transfer = NetworkTransfer()
            transfer.job = session.scope.async(start = CoroutineStart.LAZY) {
                try {
                    if (operation == "/download") {
                        val reader = client as? ChunkReadableNetworkClient ?: error("protocol does not support streaming")
                        reader.downloadByChunks(input.path, input.size) { bytes, total ->
                            var offset = 0
                            while (offset < bytes.size) {
                                val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                                transfer.chunks.send(bytes.copyOfRange(offset, end) to total)
                                offset = end
                            }
                            Result.success(Unit)
                        }.getOrThrow().also { check(it) }
                    } else {
                        var received = 0L
                        client.uploadFromSource(input.path, input.size, readChunk = {
                            val item = transfer.chunks.receiveCatching()
                            item.exceptionOrNull()?.let { throw it }
                            val bytes = item.getOrNull()?.first
                            if (bytes == null && input.size >= 0) check(received == input.size) { "upload length mismatch" }
                            if (bytes != null) {
                                received += bytes.size
                                if (input.size >= 0) check(received <= input.size) { "upload length exceeded" }
                            }
                            bytes
                        }).getOrThrow().also {
                            check(it)
                            if (input.size >= 0) check(received == input.size) { "upload ended before all bytes were consumed" }
                        }
                    }
                } catch (error: Throwable) {
                    transfer.chunks.close(error)
                    throw error
                } finally { transfer.chunks.close() }
            }
            val id = session.own(transfer) {
                transfer.chunks.cancel()
                transfer.job.cancel()
                owned.close()
                transfer.job.join()
            }
            transfer.job.start()
            platformResponse(WebUiHandle(id))
        }
        "GET" to "/downloads" -> {
            val transfer = transfer()
            transfer.mutex.withLock {
                val item = transfer.chunks.receiveCatching()
                item.exceptionOrNull()?.let { throw it }
                val part = item.getOrNull()
                if (part == null) check(transfer.job.await())
                LinkShareHttpResponse.bytes(bytes = part?.first ?: byteArrayOf(), contentType = "application/octet-stream", headers = listOf(
                    LinkShareHttpHeader("X-FolderSpan-End", (part == null).toString()),
                    LinkShareHttpHeader("X-FolderSpan-Size", (part?.second ?: 0).toString()),
                ))
            }
        }
        "PUT" to "/uploads" -> {
            val transfer = transfer()
            val bytes = request.boundedBytes()
            transfer.mutex.withLock { transfer.chunks.send(bytes to 0L) }
            platformResponse(true)
        }
        "POST" to "/uploads" -> {
            val transfer = transfer()
            transfer.mutex.withLock { transfer.chunks.close() }
            platformResponse(transfer.job.await())
        }
        else -> LinkShareHttpResponse.text(404, "unknown network operation")
    }
}
