package com.folderspan.service.webui

import com.folderspan.service.http.server.raw.RawTlsHttpServer
import com.folderspan.service.session.*
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.PlatformHttpCallbacks
import com.folderspan.service.http.server.defaultLinkShareHttpsPort
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.utils.FileUtils
import com.folderspan.utils.FileAccessPermission
import com.folderspan.service.mcp.McpServerSettings
import com.folderspan.service.mcp.http.McpHttpService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

private class IncomingHttp(val request: LinkShareHttpRequest) {
    val readMutex = Mutex()
    val writeMutex = Mutex()
    val output = Channel<ByteArray>(1)
    var written = 0L
    var expected: Long? = null
    val ready = CompletableDeferred<Unit>()
    var connectionCompletion: DisposableHandle? = null
    fun close() { connectionCompletion?.dispose(); ready.cancel(); output.cancel() }
}

internal suspend fun relayPlatformHttp(session: WebUiPlatformSession, callback: String, request: LinkShareHttpRequest): LinkShareHttpResponse {
    val pending = IncomingHttp(request)
    val id = session.own(pending) { pending.close() }
    // Also release when the peer disconnects before its response body writer starts.
    pending.connectionCompletion = currentCoroutineContext()[Job]?.invokeOnCompletion {
        session.scope.launch { session.release(id) }
    }
    try {
        val reply = session.callback(callback, "http-request", id)
        val response = platformJson.decodeFromString<WebUiOutgoingHttp>(reply.decodeToString())
        require(response.status in 100..599)
        require(response.contentLength == null || response.contentLength >= 0)
        val headers = response.headers.map { (name, value) ->
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" })
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
            LinkShareHttpHeader(name, value)
        }.filterNot { it.name.equals("Transfer-Encoding", true) || it.name.equals("Content-Length", true) }
        response.hostFileRange?.let { file ->
            require(file.offset >= 0 && file.length >= 0 && file.offset <= Long.MAX_VALUE - file.length)
            require(response.contentLength == file.length) { "host response length mismatch" }
            val path = checkedPlatformPath(file.path, rejectSymbolicLinks = true)
            val info = FileUtils.getFile(FileAccessPermission.Allowed, path).getOrThrow()
            require(!info.isDirectory && file.offset + file.length <= info.size)
            return LinkShareHttpResponse.stream(response.status, file.length, headers = headers) {
                try {
                    FileUtils.readFileRangeChunks(FileAccessPermission.Allowed, path, file.offset, file.offset + file.length, 256L * 1024).collect {
                        write(it.getOrThrow().second)
                    }
                } finally {
                    withContext(NonCancellable) { session.release(id) }
                }
            }
        }
        pending.expected = response.contentLength
        pending.ready.complete(Unit)
        return LinkShareHttpResponse.stream(response.status, response.contentLength, headers = headers) {
            try {
                for (chunk in pending.output) { write(chunk); flush() }
                check(pending.expected == null || pending.written == pending.expected) { "incomplete platform response" }
            } finally {
                withContext(NonCancellable) { session.release(id) }
            }
        }
    } catch (error: Throwable) {
        withContext(NonCancellable) { session.release(id) }
        throw error
    }
}

internal suspend fun httpListenerRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/listeners")
    if (operation.isEmpty() && request.method == "POST") {
        val input = request.platformBody<WebUiListenerOpen>()
        require(input.port in 1..65535)
        val callbacks = PlatformHttpCallbacks(handle = { relayPlatformHttp(session, input.callbackId, it) })
        val id: String
        val httpsPort: Int?
        when (input.kind) {
            "link-share" -> {
                val server = HttpShareFileServer(callbacks)
                id = session.own(server) { server.stop() }
                try { server.start(input.port); check(server.isRunning()) { "share listener failed to start" } }
                catch (error: Throwable) { withContext(NonCancellable) { session.release(id) }; throw error }
                httpsPort = defaultLinkShareHttpsPort(input.port)
            }
            "device" -> {
                val server = RawTlsHttpServer(DeviceListenerCallbacks(
                    http = { relayPlatformHttp(session, input.callbackId, it.asLinkShareRequest()).asRawHttpResponse() },
                    approval = { relayPlatformHttp(session, input.callbackId, it) },
                    accept = { channel, remoteHost, _ ->
                        val (handle, owned) = ownDeviceChannel(session, channel)
                        try {
                            session.emit(input.callbackId, "device-accepted", platformJson.encodeToString(WebUiAcceptedDevice(handle, remoteHost)))
                            owned.closed.await()
                        } finally { withContext(NonCancellable) { session.release(handle) } }
                    },
                ))
                id = session.own(server) { server.stop() }
                try { server.start(input.port) }
                catch (error: Throwable) { withContext(NonCancellable) { session.release(id) }; throw error }
                httpsPort = input.port
            }
            "mcp" -> {
                val server = McpHttpService(callbacks)
                id = session.own(server) { server.stop() }
                try {
                    val status = server.start(McpServerSettings(true, input.port, input.lanAccess))
                    check(status.running) { status.errorMessage ?: "MCP listener failed to start" }
                } catch (error: Throwable) { withContext(NonCancellable) { session.release(id) }; throw error }
                httpsPort = input.port
            }
            else -> error("unknown HTTP listener kind")
        }
        return platformResponse(WebUiListenerStarted(id, httpsPort, currentDeviceTlsFingerprint()))
    }
    val pending = session.resource(requireNotNull(request.query("handle"))) as? IncomingHttp
        ?: throw IllegalArgumentException("not an HTTP request handle")
    return when (request.method to operation) {
        "GET" to "/requests" -> with(pending.request) {
            platformResponse(WebUiIncomingHttp(method, rawUri, headers, remoteHost, scheme, host, port, body.contentLength))
        }
        "GET" to "/body" -> pending.readMutex.withLock {
            val size = request.query("length")?.toIntOrNull() ?: WEB_UI_FILE_STREAM_CHUNK_BYTES
            require(size in 1..WEB_UI_FILE_STREAM_CHUNK_BYTES)
            val bytes = ByteArray(size)
            val read = pending.request.body.read(bytes, 0, size)
            LinkShareHttpResponse.bytes(bytes = bytes.copyOf(read.coerceAtLeast(0)), headers = listOf(LinkShareHttpHeader("X-FolderSpan-End", (read < 0).toString())))
        }
        "PUT" to "/body" -> pending.writeMutex.withLock {
            pending.ready.await()
            val bytes = request.boundedBytes()
            require(pending.expected == null || bytes.size <= pending.expected!! - pending.written)
            pending.output.send(bytes)
            pending.written += bytes.size
            platformResponse(true)
        }
        "POST" to "/body" -> pending.writeMutex.withLock {
            pending.output.close(if (request.query("failed") == "true") IllegalStateException("browser response failed") else null)
            platformResponse(true)
        }
        else -> LinkShareHttpResponse.text(404, "unknown HTTP listener operation")
    }
}
