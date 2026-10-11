package com.folderspan.service.webui

import com.folderspan.service.http.client.*
import com.folderspan.service.http.server.linkshare.*
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class PlatformHttpClient(val client: HttpClient, val pin: String?, val capture: LeafCertificateCapture?) {
    val captureMutex = Mutex()
}

private class OutboundHttp(val length: Long?) {
    val input = Channel<ByteArray>(1)
    val output = Channel<ByteArray>(1)
    val metadata = CompletableDeferred<WebUiHttpIncomingResponse>()
    val inputMutex = Mutex()
    val outputMutex = Mutex()
    var written = 0L
    lateinit var job: Job
    suspend fun close() { input.cancel(); output.cancel(); metadata.cancel(); job.cancelAndJoin() }
}

internal suspend fun outboundHttpRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/http")
    if (request.method == "POST" && operation == "/clients") {
        val input = request.platformBody<WebUiHttpClientOpen>()
        require(input.fingerprint == null || input.fingerprint.isNotBlank())
        require(input.fingerprint == null || !input.captureCertificate)
        val capture = if (input.captureCertificate) LeafCertificateCapture() else null
        val config: HttpClientConfig<*>.() -> Unit = { followRedirects = false; expectSuccess = false }
        val client = when {
            input.fingerprint != null -> createPinnedNoProxyHttpClient(input.fingerprint, config)
            capture != null -> createDiscoveryNoProxyHttpClient(capture, config)
            else -> createNoProxyHttpClient(config)
        }
        val resource = PlatformHttpClient(client, input.fingerprint, capture)
        return platformResponse(WebUiHandle(session.own(resource) { client.close() }))
    }
    if (request.method == "POST" && operation == "/requests") {
        val input = request.platformBody<WebUiHttpOutgoingRequest>()
        val client = session.resource(input.client) as? PlatformHttpClient ?: error("not an HTTP client")
        val url = Url(input.url)
        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) { "HTTP(S) URL required" }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "URL credentials must use request headers" }
        require(client.pin == null || url.protocol == URLProtocol.HTTPS) { "pinned requests require HTTPS" }
        require(input.contentLength == null || input.contentLength >= 0)
        require(input.method.isNotBlank() && input.method.all { it in 'A'..'Z' || it == '-' })
        require(input.headers.size <= 128)
        input.headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" })
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
        }
        val transfer = OutboundHttp(input.contentLength)
        transfer.job = session.scope.launch(start = CoroutineStart.LAZY) {
            suspend fun execute() {
                client.client.prepareRequest(input.url) {
                    method = HttpMethod(input.method)
                    headers { input.headers.filterNot { it.first.lowercase() in setOf("host", "content-length", "transfer-encoding", "connection") }.forEach { append(it.first, it.second) } }
                    if (input.contentLength != 0L) setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength = input.contentLength
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            for (bytes in transfer.input) channel.writeFully(bytes)
                        }
                    })
                }.execute { response ->
                    transfer.metadata.complete(WebUiHttpIncomingResponse(response.status.value,
                        response.headers.entries().flatMap { (name, values) -> values.map { name to it } }, client.capture?.take()))
                    val body = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (!body.isClosedForRead) {
                        val read = body.readAvailable(buffer)
                        if (read < 0) break
                        if (read > 0) transfer.output.send(buffer.copyOf(read))
                    }
                }
            }
            try {
                if (client.capture == null) execute() else client.captureMutex.withLock { execute() }
                transfer.output.close()
            } catch (error: Throwable) {
                transfer.metadata.completeExceptionally(error)
                transfer.output.close(error)
                if (error is CancellationException) throw error
            } finally { transfer.input.cancel() }
        }
        val id = session.own(transfer) { transfer.close() }
        transfer.job.start()
        return platformResponse(WebUiHandle(id))
    }
    val transfer = session.resource(requireNotNull(request.query("handle"))) as? OutboundHttp ?: error("not an HTTP transfer")
    return when (request.method to operation) {
        "GET" to "/response" -> platformResponse(transfer.metadata.await())
        "PUT" to "/upload" -> transfer.inputMutex.withLock {
            val bytes = request.boundedBytes()
            require(transfer.length == null || bytes.size <= transfer.length - transfer.written)
            transfer.input.send(bytes)
            transfer.written += bytes.size
            platformResponse(true)
        }
        "POST" to "/upload" -> transfer.inputMutex.withLock {
            require(transfer.length == null || transfer.written == transfer.length) { "incomplete HTTP upload" }
            transfer.input.close()
            platformResponse(true)
        }
        "GET" to "/download" -> transfer.outputMutex.withLock {
            val chunk = transfer.output.receiveCatching()
            chunk.exceptionOrNull()?.let { throw it }
            LinkShareHttpResponse.bytes(bytes = chunk.getOrNull() ?: byteArrayOf(), headers = listOf(LinkShareHttpHeader("X-FolderSpan-End", chunk.isClosed.toString())))
        }
        else -> LinkShareHttpResponse.text(404, "unknown outbound HTTP operation")
    }
}
