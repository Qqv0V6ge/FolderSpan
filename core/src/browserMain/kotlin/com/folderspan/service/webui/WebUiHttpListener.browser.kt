package com.folderspan.service.webui

import com.folderspan.service.http.server.PlatformHttpCallbacks
import com.folderspan.service.http.server.linkshare.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import korlibs.crypto.SecureRandom

internal class BrowserHttpListener(
    private val callbacks: PlatformHttpCallbacks,
    private val onDevice: suspend (WebUiAcceptedDevice, CoroutineScope) -> Unit = { _, _ -> error("unexpected device connection") },
) {
    private var handle: String? = null
    private var callbackId: String? = null
    private var scope: CoroutineScope? = null
    var running = false
        private set

    suspend fun start(kind: String, port: Int, lanAccess: Boolean = true): WebUiListenerStarted {
        stop()
        val id = ByteArray(16).also(SecureRandom::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = owner
        callbackId = id
        WebUiPlatformClient.register(id, failed = {
            owner.cancel(); running = false; callbacks.running(false)
        }) { event ->
            when (event.type) {
                "http-request" -> respond(event.data, owner)
                "device-accepted" -> {
                    val accepted = WebUiPlatformClient.json.decodeFromString<WebUiAcceptedDevice>(event.data)
                    owner.launch { onDevice(accepted, owner) }
                    byteArrayOf()
                }
                else -> error("unknown listener event")
            }
        }
        try {
            val started = WebUiPlatformClient.post<WebUiListenerOpen, WebUiListenerStarted>("/listeners", WebUiListenerOpen(id, kind, port, lanAccess))
            handle = started.handle
            callbacks.configurePorts(port, started.httpsPort)
            running = true
            callbacks.running(true)
            return started
        } catch (error: Throwable) {
            withContext(NonCancellable) { stop() }
            throw error
        }
    }

    suspend fun stop() {
        running = false
        callbacks.running(false)
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        callbackId?.let { WebUiPlatformClient.unregister(it) }
        callbackId = null
        val owned = handle
        handle = null
        try { if (owned != null) WebUiPlatformClient.release(owned) }
        finally { callbacks.clearSessions() }
    }

    private suspend fun respond(id: String, owner: CoroutineScope): ByteArray {
        val suffix = "?handle=${encodeWebUiQuery(id)}"
        val metadata = WebUiPlatformClient.get<WebUiIncomingHttp>("/listeners/requests$suffix")
        val readMutex = Mutex()
        val input = object : LinkShareHttpRequestBody {
            override val contentLength = metadata.contentLength
            override var remainingBytes = contentLength
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = readMutex.withLock {
                require(offset >= 0 && length >= 0 && length <= buffer.size - offset)
                if (length == 0) return@withLock 0
                val response = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/listeners/body$suffix&length=${minOf(length, WEB_UI_FILE_STREAM_CHUNK_BYTES)}")
                if (response.headers.entries.any { it.key.equals("X-FolderSpan-End", true) && it.value == "true" }) return@withLock -1
                require(response.body.size <= length)
                response.body.copyInto(buffer, offset)
                remainingBytes = remainingBytes?.minus(response.body.size)
                response.body.size
            }
            override suspend fun discard() {
                val bytes = ByteArray(64 * 1024)
                while (read(bytes, 0, bytes.size) >= 0) { currentCoroutineContext().ensureActive() }
            }
        }
        val response = callbacks.handle(LinkShareHttpRequest.from(metadata.method, metadata.uri, metadata.headers,
            metadata.remoteHost, metadata.scheme, metadata.host, metadata.port, input))
        val length = when (val body = response.body) {
            LinkShareHttpResponseBody.Empty -> 0L
            is LinkShareHttpResponseBody.Bytes -> body.bytes.size.toLong()
            is LinkShareHttpResponseBody.Stream -> body.contentLength
        }
        val hostFile = (response.body as? LinkShareHttpResponseBody.Stream)?.hostFileRange
        if (hostFile != null) return WebUiPlatformClient.json.encodeToString(
            WebUiOutgoingHttp(response.statusCode, response.headers.map { it.name to it.value }, length, hostFile),
        ).encodeToByteArray()
        owner.launch {
            var failed = true
            try {
                val writer = object : LinkShareHttpResponseBodyWriter {
                    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && length <= bytes.size - offset)
                        var position = offset
                        while (position < offset + length) {
                            val end = minOf(offset + length, position + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                            WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/listeners/body$suffix", bytes.copyOfRange(position, end), "application/octet-stream")
                            position = end
                        }
                    }
                    override suspend fun flush() = Unit
                }
                when (val body = response.body) {
                    LinkShareHttpResponseBody.Empty -> Unit
                    is LinkShareHttpResponseBody.Bytes -> writer.write(body.bytes)
                    is LinkShareHttpResponseBody.Stream -> body.writer(writer)
                }
                failed = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Report failure through the response stream; no detached coroutine exception or replay.
            } finally {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) { runCatching {
                        WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/listeners/body$suffix&failed=$failed")
                    } }
                }
            }
        }
        return WebUiPlatformClient.json.encodeToString(WebUiOutgoingHttp(response.statusCode, response.headers.map { it.name to it.value }, length)).encodeToByteArray()
    }
}
