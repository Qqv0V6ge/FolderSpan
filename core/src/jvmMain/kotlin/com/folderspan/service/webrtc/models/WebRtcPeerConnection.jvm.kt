package com.folderspan.service.webrtc.models

import dev.onvoid.webrtc.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class JvmDataChannel(private val channel: RTCDataChannel) : WebRtcDataChannel {
    private val _onOpen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _onClose = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _onMessage = Channel<ByteArray>(capacity = 512)
    private val observedState = AtomicReference(RTCDataChannelState.CONNECTING)
    private val safeLabel = runCatching { channel.label }.getOrDefault("unknown")

    @Volatile
    private var released = false

    @Volatile
    private var closing = false

    init {
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {
                // no-op
            }

            override fun onStateChange() {
                when (refreshObservedState()) {
                    RTCDataChannelState.OPEN -> _onOpen.tryEmit(Unit)
                    RTCDataChannelState.CLOSED -> {
                        markReleased()
                        _onClose.tryEmit(Unit)
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                if (_onMessage.trySend(bytes).isFailure) {
                    close()
                }
            }
        })
        refreshObservedState()
    }

    override val label: String
        get() = safeLabel

    override val state: WebRtcDataChannelState
        get() = when {
            released -> WebRtcDataChannelState.Closed
            closing -> WebRtcDataChannelState.Closing
            else -> observedState.get().toWebRtcState()
        }

    override val bufferedAmount: Long
        get() = if (released) 0L else runCatching { channel.bufferedAmount }.getOrDefault(0L)

    override val onOpen: Flow<Unit>
        get() = _onOpen.asSharedFlow()

    override val onClose: Flow<Unit>
        get() = _onClose.asSharedFlow()

    override val onMessage: Flow<ByteArray>
        get() = _onMessage.receiveAsFlow()

    override fun send(data: ByteArray): Boolean {
        return !(released || closing) && try {
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(data), true))
            true
        } catch (e: Exception) {
            markReleased()
            false
        }
    }

    override fun close() {
        if (released || closing) return
        closing = true
        runCatching { channel.close() }.onFailure {
            if (markReleased()) {
                _onClose.tryEmit(Unit)
            }
        }
    }

    private fun refreshObservedState(): RTCDataChannelState {
        val next = nativeStateOrClosed()
        // A DataChannel only advances CONNECTING -> OPEN -> CLOSING -> CLOSED. Keep a
        // late initial sample from overwriting a newer native observer notification.
        return observedState.updateAndGet { current ->
            if (next.ordinal > current.ordinal) next else current
        }
    }

    private fun nativeStateOrClosed(): RTCDataChannelState {
        if (released) return RTCDataChannelState.CLOSED
        return runCatching { channel.state }.getOrElse {
            markReleased()
            RTCDataChannelState.CLOSED
        }
    }

    private fun markReleased(): Boolean {
        if (released) return false
        released = true
        closing = false
        runCatching { channel.unregisterObserver() }
        runCatching { channel.dispose() }
        _onMessage.close()
        return true
    }
}

actual class WebRtcPeerConnection actual constructor(iceServers: List<WebRtcIceServerConfig>) {
    private val iceCandidates = MutableSharedFlow<WebRtcIceCandidate>(extraBufferCapacity = 64)
    private val dataChannels = MutableSharedFlow<WebRtcDataChannel>(extraBufferCapacity = 64)
    private val connectionStates = MutableStateFlow(WebRtcPeerConnectionState.New)

    private val factory = PeerConnectionFactory()
    private val peerConnection: RTCPeerConnection

    init {
        val config = RTCConfiguration()
        config.iceServers.addAll(iceServers.map { it.toNativeIceServer() })
        peerConnection = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                iceCandidates.tryEmit(
                    WebRtcIceCandidate(
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                )
            }

            override fun onDataChannel(dataChannel: RTCDataChannel) {
                dataChannels.tryEmit(JvmDataChannel(dataChannel))
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                connectionStates.value = state.toWebRtcState()
            }
        })
    }

    actual val onIceCandidate: Flow<WebRtcIceCandidate>
        get() = iceCandidates.asSharedFlow()

    actual val onDataChannel: Flow<WebRtcDataChannel>
        get() = dataChannels.asSharedFlow()

    actual val onConnectionStateChange: Flow<WebRtcPeerConnectionState>
        get() = connectionStates.asStateFlow()

    actual suspend fun createOffer(): WebRtcSessionDescription {
        return createSessionDescription { observer ->
            peerConnection.createOffer(RTCOfferOptions(), observer)
        }
    }

    actual suspend fun createAnswer(): WebRtcSessionDescription {
        return createSessionDescription { observer ->
            peerConnection.createAnswer(RTCAnswerOptions(), observer)
        }
    }

    actual suspend fun setLocalDescription(description: WebRtcSessionDescription) {
        setSessionDescription(description) { desc, observer ->
            peerConnection.setLocalDescription(desc, observer)
        }
    }

    actual suspend fun setRemoteDescription(description: WebRtcSessionDescription) {
        setSessionDescription(description) { desc, observer ->
            peerConnection.setRemoteDescription(desc, observer)
        }
    }

    actual suspend fun addIceCandidate(candidate: WebRtcIceCandidate) {
        peerConnection.addIceCandidate(
            RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    actual fun createDataChannel(
        label: String,
        ordered: Boolean,
        maxRetransmits: Int?
    ): WebRtcDataChannel? {
        val init = RTCDataChannelInit().apply {
            this.ordered = ordered
            if (maxRetransmits != null) {
                this.maxRetransmits = maxRetransmits
            }
        }
        return JvmDataChannel(peerConnection.createDataChannel(label, init))
    }

    actual fun close() {
        peerConnection.close()
        factory.dispose()
    }

    private suspend fun createSessionDescription(
        create: (CreateSessionDescriptionObserver) -> Unit
    ): WebRtcSessionDescription {
        return suspendCancellableCoroutine { cont ->
            create(object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    cont.resume(description.toWebRtcDescription())
                }

                override fun onFailure(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }

    private suspend fun setSessionDescription(
        description: WebRtcSessionDescription,
        setter: (RTCSessionDescription, SetSessionDescriptionObserver) -> Unit
    ) {
        val native = description.toNative()
        return suspendCancellableCoroutine { cont ->
            setter(native, object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    cont.resume(Unit)
                }

                override fun onFailure(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }
}

private fun WebRtcIceServerConfig.toNativeIceServer(): RTCIceServer {
    return RTCIceServer().apply {
        urls.addAll(this@toNativeIceServer.urls)
        if (this@toNativeIceServer.username.isNotBlank()) {
            username = this@toNativeIceServer.username
        }
        if (this@toNativeIceServer.password.isNotBlank()) {
            password = this@toNativeIceServer.password
        }
    }
}

private fun RTCSessionDescription.toWebRtcDescription(): WebRtcSessionDescription {
    val type = when (sdpType) {
        RTCSdpType.OFFER -> WebRtcSdpType.Offer
        RTCSdpType.ANSWER -> WebRtcSdpType.Answer
        RTCSdpType.PR_ANSWER -> WebRtcSdpType.Answer
        RTCSdpType.ROLLBACK -> WebRtcSdpType.Answer
    }
    return WebRtcSessionDescription(type, sdp)
}

private fun WebRtcSessionDescription.toNative(): RTCSessionDescription {
    val type = when (this.type) {
        WebRtcSdpType.Offer -> RTCSdpType.OFFER
        WebRtcSdpType.Answer -> RTCSdpType.ANSWER
    }
    return RTCSessionDescription(type, sdp)
}

private fun RTCDataChannelState.toWebRtcState(): WebRtcDataChannelState {
    return when (this) {
        RTCDataChannelState.CONNECTING -> WebRtcDataChannelState.Connecting
        RTCDataChannelState.OPEN -> WebRtcDataChannelState.Open
        RTCDataChannelState.CLOSING -> WebRtcDataChannelState.Closing
        RTCDataChannelState.CLOSED -> WebRtcDataChannelState.Closed
    }
}

private fun RTCPeerConnectionState.toWebRtcState(): WebRtcPeerConnectionState {
    return when (this) {
        RTCPeerConnectionState.NEW -> WebRtcPeerConnectionState.New
        RTCPeerConnectionState.CONNECTING -> WebRtcPeerConnectionState.Connecting
        RTCPeerConnectionState.CONNECTED -> WebRtcPeerConnectionState.Connected
        RTCPeerConnectionState.DISCONNECTED -> WebRtcPeerConnectionState.Disconnected
        RTCPeerConnectionState.FAILED -> WebRtcPeerConnectionState.Failed
        RTCPeerConnectionState.CLOSED -> WebRtcPeerConnectionState.Closed
    }
}
