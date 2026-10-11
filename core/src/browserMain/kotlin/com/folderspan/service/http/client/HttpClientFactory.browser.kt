@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.folderspan.service.http.client

import com.folderspan.service.webui.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual fun createNoProxyHttpClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(RuntimeHttpEngine(null, null), config)
actual fun createPinnedNoProxyHttpClient(expectedFingerprintSha256: String, config: HttpClientConfig<*>.() -> Unit) =
    HttpClient(RuntimeHttpEngine(expectedFingerprintSha256, null), config)
internal actual fun createDiscoveryNoProxyHttpClient(leafCertificateCapture: LeafCertificateCapture, config: HttpClientConfig<*>.() -> Unit) =
    HttpClient(RuntimeHttpEngine(null, leafCertificateCapture), config)

private class RuntimeHttpEngine(private val pin: String?, private val capture: LeafCertificateCapture?) : HttpClientEngineBase("runtime-http") {
    override val config = HttpClientEngineConfig()
    override val supportedCapabilities = setOf(HttpTimeoutCapability)
    private val mutex = Mutex()
    private var handle: String? = null
    private var session: String? = null
    private var closed = false

    private suspend fun clientHandle(): String = mutex.withLock {
        check(!closed) { "HTTP client closed" }
        if (handle != null) {
            check(session == WebUiAttachment.sessionToken) { "Runtime session changed" }
            return@withLock handle!!
        }
        session = WebUiAttachment.sessionToken
        WebUiPlatformClient.post<WebUiHttpClientOpen, WebUiHandle>("/http/clients", WebUiHttpClientOpen(pin, capture != null)).id.also { handle = it }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val context = callContext()
        val owner = CoroutineScope(context)
        val time = GMTDate()
        var content = data.body
        while (content is OutgoingContent.ContentWrapper) content = content.delegate()
        val payload = content
        require(content !is OutgoingContent.ProtocolUpgrade) { "HTTP upgrade is unavailable through the platform HTTP client" }
        val headers = buildList {
            data.headers.entries().forEach { (name, values) -> values.forEach { add(name to it) } }
            content.headers.entries().forEach { (name, values) -> values.forEach { add(name to it) } }
            content.contentType?.let { if (none { it.first.equals("Content-Type", true) }) add("Content-Type" to it.toString()) }
        }
        val length = if (content is OutgoingContent.NoContent) 0L else content.contentLength
        val handle = WebUiPlatformClient.post<WebUiHttpOutgoingRequest, WebUiHandle>("/http/requests",
            WebUiHttpOutgoingRequest(clientHandle(), data.url.toString(), data.method.value, headers, length)).id
        val suffix = "?handle=$handle"
        val upload = owner.launch {
            suspend fun send(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
                var index = offset
                while (index < offset + length) {
                    val end = minOf(index + WEB_UI_FILE_STREAM_CHUNK_BYTES, offset + length)
                    WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/http/upload$suffix", bytes.copyOfRange(index, end), "application/octet-stream")
                    index = end
                }
            }
            suspend fun read(channel: ByteReadChannel) {
                val buffer = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    if (count > 0) send(buffer, 0, count)
                }
            }
            when (val content = payload) {
                is OutgoingContent.NoContent -> Unit
                is OutgoingContent.ByteArrayContent -> send(content.bytes())
                is OutgoingContent.ReadChannelContent -> read(content.readFrom())
                is OutgoingContent.WriteChannelContent -> coroutineScope {
                    val pipe = ByteChannel(autoFlush = true)
                    val writer = launch { try { content.writeTo(pipe); pipe.flushAndClose() } catch (error: Throwable) { pipe.cancel(error); throw error } }
                    try { read(pipe); writer.join() } finally { writer.cancel(); pipe.cancel() }
                }
                else -> error("unsupported HTTP request content")
            }
            WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/http/upload$suffix")
        }
        try {
            val metadata = WebUiPlatformClient.get<WebUiHttpIncomingResponse>("/http/response$suffix")
            metadata.fingerprint?.let { capture?.accept(it) }
            val body = ByteChannel(autoFlush = true)
            owner.launch {
                try {
                    while (isActive) {
                        val chunk = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/http/download$suffix")
                        if (chunk.headers.entries.any { it.key.equals("X-FolderSpan-End", true) && it.value == "true" }) break
                        body.writeFully(chunk.body)
                    }
                    body.flushAndClose()
                } catch (error: Throwable) { body.cancel(error); throw error }
                finally { upload.cancel(); releaseTransfer(handle) }
            }
            return HttpResponseData(HttpStatusCode.fromValue(metadata.status), time,
                Headers.build { metadata.headers.forEach { append(it.first, it.second) } }, HttpProtocolVersion.HTTP_1_1, body, context)
        } catch (error: Throwable) { upload.cancel(); releaseTransfer(handle); throw error }
    }

    private suspend fun releaseTransfer(id: String) = withContext(NonCancellable) {
        withTimeoutOrNull(5_000) { runCatching { WebUiPlatformClient.release(id) } }
        Unit
    }

    override fun close() {
        closed = true
        super.close()
        val id = handle ?: return
        CoroutineScope(Dispatchers.Default).launch {
            if (session == WebUiAttachment.sessionToken) releaseTransfer(id)
        }
    }
}
