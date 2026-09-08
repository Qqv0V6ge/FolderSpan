package com.folderspan.service.webrtc.signaling

import com.folderspan.getSocketDevice
import com.folderspan.service.http.client.applyFileShareAccessKey
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.service.webrtc.WebRtcRoomConnectionProbeClient
import com.folderspan.utils.LogKit
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

class HttpWebRtcSignalingClient(
    private val baseUrl: String,
    private val client: HttpClient = createNoProxyHttpClient { expectSuccess = false },
    private val role: HttpWebRtcSignalingRole = HttpWebRtcSignalingRole.Browser,
) : WebRtcRoomConnectionProbeClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    private val _openedEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val _closedEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val _failureEvents = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)
    private var job: Job? = null
    private var cursor: Long = 0L
    private var clientId: String = ""
    private var clientToken: String = ""

    override val messages: Flow<SignalingMessage> = _messages.asSharedFlow()
    override val openedEvents: Flow<Unit> = _openedEvents.asSharedFlow()
    override val closedEvents: Flow<Unit> = _closedEvents.asSharedFlow()
    override val failureEvents: Flow<Throwable> = _failureEvents.asSharedFlow()

    override fun connect(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            try {
                val localDevice = getSocketDevice().toSignalingDevice()
                val joinResponse = postJson<HttpWebRtcJoinResponse>(
                    path = "join",
                    body = json.encodeToString(
                        HttpWebRtcJoinRequest(
                            role = role,
                            device = localDevice
                        )
                    )
                )
                if (joinResponse.code != null) {
                    throw IllegalStateException(joinResponse.message ?: joinResponse.code)
                }
                clientId = joinResponse.clientId
                clientToken = joinResponse.clientToken
                cursor = joinResponse.nextCursor
                joinResponse.messages.forEach { message -> _messages.tryEmit(message) }
                _openedEvents.tryEmit(Unit)
                LogKit.i(AppStrings.ui_http_webrtc_signaling_is_connected_arg0_client_arg1_role_arg2.format(arg0 = (baseUrl), arg1 = (clientId), arg2 = (role).toString()))

                while (isActive) {
                    val pollResponse = getPoll(clientId, clientToken, cursor)
                    cursor = pollResponse.nextCursor
                    pollResponse.messages.forEach { message ->
                        _messages.tryEmit(message)
                    }
                    if (pollResponse.messages.isEmpty()) {
                        delay(POLL_IDLE_DELAY_MS.milliseconds)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    LogKit.i(AppStrings.ui_http_webrtc_signaling_connection_is_canceled_arg0.format(arg0 = (error.message).toString()))
                } else {
                    _failureEvents.tryEmit(error)
                    LogKit.e(AppStrings.ui_http_webrtc_signaling_connection_failed_arg0.format(arg0 = (error.message).toString()), error)
                }
            } finally {
                job = null
                _closedEvents.tryEmit(Unit)
            }
        }
    }

    override suspend fun send(message: SignalingMessage) {
        val currentClientId = clientId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(AppStrings.webrtc_http_signaling_not_connected)
        val response = postJson<HttpWebRtcSignalResponse>(
            path = "send",
            body = json.encodeToString(
                HttpWebRtcSignalRequest(
                    clientId = currentClientId,
                    clientToken = clientToken,
                    message = message
                )
            )
        )
        if (!response.accepted) {
            throw IllegalStateException(response.message ?: response.code ?: AppStrings.ui_http_webrtc_signaling_failed)
        }
    }

    override suspend fun close() {
        val currentClientId = clientId
        job?.cancel()
        job = null
        if (currentClientId.isNotBlank()) {
            runCatching {
                postJson<HttpWebRtcSignalResponse>(
                    path = "leave",
                    body = json.encodeToString(HttpWebRtcLeaveRequest(currentClientId, clientToken))
                )
            }
        }
        client.close()
        _closedEvents.tryEmit(Unit)
    }

    private suspend inline fun <reified T> postJson(path: String, body: String): T {
        val response = client.post(endpoint(path)) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            applyFileShareAccessKey()
            headers.append(HttpHeaders.UserAgent, "FolderSpan-WebRTC")
            if (role == HttpWebRtcSignalingRole.Host) {
                headers.append(HTTP_WEBRTC_HOST_SECRET_HEADER, HttpWebRtcHostAuth.secret)
            }
            setBody(body)
        }
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun getPoll(clientId: String, clientToken: String, cursor: Long): HttpWebRtcPollResponse {
        val response = client.get("${endpoint("poll")}?clientId=$clientId&cursor=$cursor") {
            accept(ContentType.Application.Json)
            applyFileShareAccessKey()
            headers.append(HttpHeaders.UserAgent, "FolderSpan-WebRTC")
            headers.append(HTTP_WEBRTC_CLIENT_TOKEN_HEADER, clientToken)
        }
        return json.decodeFromString(response.bodyAsText())
    }

    private fun endpoint(path: String): String {
        return "${baseUrl.trimEnd('/')}/api/webrtc/signaling/$path"
    }

    companion object {
        private const val POLL_IDLE_DELAY_MS = 500L
    }
}
