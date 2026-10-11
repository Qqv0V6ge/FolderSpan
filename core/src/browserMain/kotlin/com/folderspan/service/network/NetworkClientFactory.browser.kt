package com.folderspan.service.network

import com.folderspan.service.webui.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.random.Random

actual object NetworkClientFactory {
    actual fun create(network: NetworkConnectionConfig): NetworkClient = HttpNetworkClient(network)
}

private class HttpNetworkClient(private val config: NetworkConnectionConfig) : NetworkClient, ChunkReadableNetworkClient {
    private val mutex = Mutex()
    private var connection: String? = null
    private var token = ""
    private var callback: String? = null
    private var failure: Throwable? = null

    private suspend fun handle(): String = mutex.withLock {
        failure?.let { throw it }
        if (connection != null) {
            check(token == WebUiAttachment.sessionToken) { "network session expired; reconnect the Runtime" }
            return@withLock checkNotNull(connection)
        }
        token = WebUiAttachment.sessionToken
        val callbackId = "network-${Random.nextLong()}-${Random.nextLong()}"
        callback = callbackId
        WebUiPlatformClient.register(callbackId, { failure = it }) { event ->
            check(event.type == "sftp-host-key") { "unknown network event" }
            val key = WebUiPlatformClient.json.decodeFromString<SftpHostKey>(event.data)
            WebUiPlatformClient.json.encodeToString(SftpHostKeyTrust.shared.confirm(key)).encodeToByteArray()
        }
        try {
            WebUiPlatformClient.post<WebUiNetworkOpen, WebUiHandle>("/networks", WebUiNetworkOpen(callbackId, config)).id.also { connection = it }
        } catch (error: Throwable) {
            WebUiPlatformClient.unregister(callbackId)
            throw error
        }
    }

    override suspend fun close() = mutex.withLock {
        val handle = connection
        connection = null
        callback?.let { WebUiPlatformClient.unregister(it) }
        callback = null
        if (handle != null && token == WebUiAttachment.sessionToken) release(handle)
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = platformResult {
        val directory = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/directories", WebUiNetworkPath(handle(), path)).id
        try {
            buildList {
                do {
                    val batch = WebUiPlatformClient.get<WebUiNetworkBatch>("/networks/directories?handle=${encodeWebUiQuery(directory)}")
                    addAll(batch.entries)
                } while (!batch.finished)
            }
        } finally { release(directory) }
    }

    private suspend fun operation(operation: String, path: String, target: String = "", directory: Boolean = false): Result<Boolean> = platformResult {
        WebUiPlatformClient.post("/networks/$operation", WebUiNetworkPath(handle(), path, target, directory))
    }
    override suspend fun rename(path: String, newPath: String) = operation("rename", path, newPath)
    override suspend fun delete(path: String, isDirectory: Boolean) = operation("delete", path, directory = isDirectory)
    override suspend fun createFolder(path: String) = operation("mkdir", path)
    override suspend fun createFile(path: String) = operation("create", path)
    override suspend fun copyFile(sourcePath: String, targetPath: String) = operation("copy", sourcePath, targetPath)

    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = platformResult {
        val download = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/download", WebUiNetworkPath(handle(), remotePath, size = size)).id
        try {
            while (true) {
                val response = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/networks/downloads?handle=${encodeWebUiQuery(download)}")
                if (response.headers["x-folderspan-end"] == "true") break
                check(response.body.isNotEmpty()) { "invalid network stream chunk" }
                onChunk(response.body, response.headers["x-folderspan-size"]?.toLongOrNull() ?: size).getOrThrow()
            }
            true
        } finally { release(download) }
    }

    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = platformResult {
        FileUtils.writeBytes(FileAccessPermission.Allowed, localPath, 0, byteArrayOf(), 0).getOrThrow()
        var offset = 0L
        downloadByChunks(remotePath, size) { bytes, total ->
            FileUtils.writeBytes(FileAccessPermission.Allowed, localPath, maxOf(total, offset + bytes.size), bytes, offset).map {
                offset += bytes.size
                onProgress(offset, total)
            }
        }.getOrThrow()
    }

    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
        val length = if (size >= 0) size else FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrThrow().size
        var offset = 0L
        return uploadFromSource(remotePath, length, onProgress) {
            if (offset >= length) null else {
                val end = offset + minOf(WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong(), length - offset)
                FileUtils.readFileRange(FileAccessPermission.Allowed, localPath, offset, end).getOrThrow().also { offset = end }
            }
        }
    }

    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> = platformResult {
        val upload = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/upload", WebUiNetworkPath(handle(), remotePath, size = size)).id
        try {
            var sent = 0L
            while (true) {
                val bytes = readChunk() ?: break
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                    WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/networks/uploads?handle=${encodeWebUiQuery(upload)}", bytes.copyOfRange(offset, end), "application/octet-stream")
                    sent += end - offset
                    offset = end
                    onProgress(sent, size)
                }
            }
            WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/networks/uploads?handle=${encodeWebUiQuery(upload)}")
            true
        } finally { release(upload) }
    }

    private suspend fun release(handle: String) = withContext(NonCancellable) {
        if (token == WebUiAttachment.sessionToken) runCatching { WebUiPlatformClient.release(handle) }
        Unit
    }
}
