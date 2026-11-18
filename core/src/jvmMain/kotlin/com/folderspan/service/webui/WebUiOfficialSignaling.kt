package com.folderspan.service.webui

import com.folderspan.AppBuildConfig
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal val officialSignalingGateway: String = AppBuildConfig.GATEWAY_BASE_URL.trim().trimEnd('/')
    .takeIf { it.startsWith("https://") && AppBuildConfig.API_HOST_HEADER.isNotBlank() }
    ?.let { "wss://" + it.removePrefix("https://") + "/api/v1/webrtc/ws" }
    .orEmpty()

private val officialHeaderNames = setOf(
    "Authorization", "X-Device-Key", "X-App-Key", "X-Timestamp", "X-Nonce", "X-Signature", "Host", "Origin",
)

private const val SIGNALING_MAX_FRAME_BYTES = 64 * 1024

internal suspend fun officialSignalingRequest(
    operation: String,
    request: LinkShareHttpRequest,
    session: WebUiPlatformSession,
): LinkShareHttpResponse {
    if (request.method == "POST" && operation == "official-signaling/open") {
        val input = request.platformBody<WebUiOfficialSignalingOpen>()
        require(officialSignalingGateway.isNotEmpty() && input.gatewayUrl == officialSignalingGateway) {
            "unsupported official signaling gateway"
        }
        require(input.headers.keys == officialHeaderNames && input.headers.values.all {
            it.isNotBlank() && it.length <= 8192 && it.none(Char::isISOControl)
        }) { "invalid official signaling headers" }
        require(input.headers["Host"] == AppBuildConfig.API_HOST_HEADER &&
            input.headers["Origin"] == "https://" + AppBuildConfig.API_HOST_HEADER &&
            input.headers["Authorization"]?.startsWith("Bearer ") == true
        ) { "invalid official signaling origin" }
        val client = HttpClient {
            followRedirects = false
            install(WebSockets) { maxFrameSize = SIGNALING_MAX_FRAME_BYTES.toLong() }
        }
        val socket = try {
            withTimeout(8_000L) {
                client.webSocketSession {
                    url(Url(officialSignalingGateway))
                    input.headers.forEach { (key, value) -> header(key, value) }
                }
            }
        } catch (error: Throwable) {
            client.close()
            if (error is CancellationException) throw error
            error("official signaling connection failed")
        }
        return platformResponse(WebUiHandle(session.own(socket) { client.close() }))
    }
    val handle = requireNotNull(request.query("handle"))
    val socket = session.resource(handle) as? DefaultClientWebSocketSession ?: error("not an official signaling handle")
    return when (request.method to operation) {
        "GET" to "official-signaling/read" -> {
            val frame = withTimeoutOrNull(15_000L) { socket.incoming.receiveCatching() }
                ?: return platformResponse("")
            val text = (frame.getOrNull() as? Frame.Text)?.readText()
            platformResponse(text)
        }
        "POST" to "official-signaling/write" -> {
            val frame = request.platformBody<WebUiOfficialSignalingFrame>()
            require(frame.text.encodeToByteArray().size in 1..SIGNALING_MAX_FRAME_BYTES)
            withTimeout(8_000L) { socket.send(Frame.Text(frame.text)) }
            platformResponse(true)
        }
        else -> LinkShareHttpResponse.text(404, "unknown official signaling operation")
    }
}
