package com.folderspan.service.webrtc.signaling

import com.folderspan.service.http.client.applyConfiguredHttpProxy
import com.folderspan.service.webrtc.WebRtcRoomConnectionProbeClient
import com.folderspan.utils.LogKit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import strings.AppStrings

class WebRtcSignalingClient(
    private val wssUrl: String,
    private val headers: Map<String, String> = emptyMap()
) : WebRtcRoomConnectionProbeClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        applyConfiguredHttpProxy()
        install(WebSockets)
    }
    private val outgoing = Channel<String>(Channel.BUFFERED)
    private val outgoingReceiver: ReceiveChannel<String> = outgoing
    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    private val _openedEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val _closedEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val _failureEvents = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)
    private var job: Job? = null

    override val messages: Flow<SignalingMessage> = _messages.asSharedFlow()
    override val openedEvents: Flow<Unit> = _openedEvents.asSharedFlow()
    override val closedEvents: Flow<Unit> = _closedEvents.asSharedFlow()
    override val failureEvents: Flow<Throwable> = _failureEvents.asSharedFlow()

    override fun connect(scope: CoroutineScope) {
        if (job != null) return
        LogKit.i(AppStrings.ui_webrtc_signaling_connection_arg0.format(arg0 = (wssUrl)))
        job = scope.launch {
            try {
                client.webSocket(
                    request = {
                        url(wssUrl)
                        this@WebRtcSignalingClient.headers.forEach { (name, value) ->
                            header(name, value)
                        }
                    }
                ) {
                    LogKit.i(AppStrings.ui_webrtc_signaling_is_connected_arg0.format(arg0 = (wssUrl)))
                    _openedEvents.tryEmit(Unit)
                    val sender = launch {
                        for (text in outgoingReceiver) {
                            send(Frame.Text(text))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val payload = frame.readText()
                                runCatching {
                                    json.decodeFromString(SignalingMessage.serializer(), payload)
                                }.onSuccess { message ->
                                    LogKit.d(AppStrings.ui_webrtc_signaling_receiver_arg0_payloadlen_arg1.format(arg0 = (summarize(message)), arg1 = (payload.length).toString()))
                                    _messages.tryEmit(message)
                                }.onFailure { error ->
                                    LogKit.w(AppStrings.ui_parsing_signaling_message_failed_arg0_payloadlen_arg1.format(arg0 = (error.message).toString(), arg1 = (payload.length).toString()))
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        LogKit.w(AppStrings.ui_webrtc_signaling_receiver_loop_exception_arg0.format(arg0 = (error.message).toString()), error)
                    } finally {
                        sender.cancel()
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    LogKit.i(AppStrings.ui_webrtc_signaling_connection_is_canceled_arg0.format(arg0 = (error.message).toString()))
                } else {
                    _failureEvents.tryEmit(error)
                    LogKit.e(AppStrings.ui_webrtc_connection_failed_arg0.format(arg0 = (error.message).toString()), error)
                }
            } finally {
                job = null
                _closedEvents.tryEmit(Unit)
                LogKit.i(AppStrings.ui_webrtc_signaling_connection_ended_arg0.format(arg0 = (wssUrl)))
            }
        }
    }

    override suspend fun send(message: SignalingMessage) {
        val currentJob = job ?: throw IllegalStateException(AppStrings.webrtc_signaling_not_connected)
        if (currentJob.isCompleted) {
            throw IllegalStateException(AppStrings.ui_webrtc_signaling_has_been_closed)
        }
        val payload = json.encodeToString(SignalingMessage.serializer(), message)
        LogKit.d(AppStrings.ui_webrtc_signaling_arg0_payloadlen_arg1.format(arg0 = (summarize(message)), arg1 = (payload.length).toString()))
        outgoing.send(payload)
    }

    override suspend fun close() {
        LogKit.i(AppStrings.ui_webrtc_signaling_arg0.format(arg0 = (wssUrl)))
        job?.cancel()
        job = null
        outgoing.close()
        client.close()
    }

    private fun summarize(message: SignalingMessage): String {
        val fromId = message.from?.id ?: "-"
        val toId = message.to?.id ?: "-"
        val room = message.roomId ?: "-"
        val sdpLen = message.sdp?.length ?: 0
        val candidate = message.candidate?.let {
            "candidate(mid=${it.sdpMid}, index=${it.sdpMLineIndex}, len=${it.candidate.length})"
        } ?: "candidate=-"
        val peersCount = message.peers?.size ?: 0
        val error = buildList {
            message.code?.takeIf(String::isNotBlank)?.let { add("code=$it") }
            message.errorMessage?.takeIf(String::isNotBlank)?.let { add("message=$it") }
        }.joinToString(" ")
        val errorPart = if (error.isBlank()) "" else " $error"
        return "type=${message.type} room=$room from=$fromId to=$toId sdpLen=$sdpLen peers=$peersCount $candidate$errorPart"
    }
}
