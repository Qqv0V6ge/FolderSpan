package com.folderspan.service.webrtc.models

import com.shepeliev.webrtckmp.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.concurrent.Volatile

private class NonJvmDataChannel(private val channel: DataChannel) : WebRtcDataChannel {
    private val safeLabel: String = runCatching { channel.label }.getOrDefault("unknown")

    override val label: String
        get() = safeLabel

    override val state: WebRtcDataChannelState
        get() = runCatching { channel.readyState.toWebRtcState() }
            .getOrElse { WebRtcDataChannelState.Closed }

    override val bufferedAmount: Long
        get() = runCatching {
            channel.bufferedAmount
                .toString()
                .toLongOrNull()
                ?: 0L
        }.getOrDefault(0L)

    override val onOpen: Flow<Unit>
        get() = channel.onOpen

    override val onClose: Flow<Unit>
        get() = channel.onClose

    override val onMessage: Flow<ByteArray>
        get() = channel.onMessage

    override fun send(data: ByteArray): Boolean = channel.send(data)

    override fun close() {
        runCatching { channel.close() }
    }
}

actual class WebRtcPeerConnection actual constructor(iceServers: List<WebRtcIceServerConfig>) {
    @Volatile private var closed = false
    // shortcut: the pinned KMP binding hides ICE server errors; use callbacks when it exposes them.
    actual val lastIceServerErrorCode: Int? get() = null
    internal val peerConnection: PeerConnection =
        PeerConnection(
            rtcConfiguration = RtcConfiguration(
                iceServers = iceServers.map { it.toNativeIceServer() },
                iceTransportPolicy = IceTransportPolicy.All,
            )
        )

    actual val onIceCandidate: Flow<WebRtcIceCandidate> =
        peerConnection.onIceCandidate.map { candidate ->
            WebRtcIceCandidate(
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                candidate = candidate.candidate
            )
        }

    actual val onDataChannel: Flow<WebRtcDataChannel> =
        peerConnection.onDataChannel.map { channel -> NonJvmDataChannel(channel) }

    actual val onConnectionStateChange: Flow<WebRtcPeerConnectionState> =
        peerConnection.onConnectionStateChange.map { state -> state.toWebRtcState() }.distinctUntilChanged()

    actual suspend fun createOffer(iceRestart: Boolean): WebRtcSessionDescription {
        val offer = peerConnection.createOffer(OfferAnswerOptions(iceRestart = iceRestart))
        return offer.toWebRtcDescription()
    }

    actual suspend fun createAnswer(): WebRtcSessionDescription {
        val answer = peerConnection.createAnswer(OfferAnswerOptions())
        return answer.toWebRtcDescription()
    }

    actual suspend fun setLocalDescription(description: WebRtcSessionDescription) {
        peerConnection.setLocalDescription(description.toNative())
    }

    actual suspend fun setRemoteDescription(description: WebRtcSessionDescription) {
        peerConnection.setRemoteDescription(description.toNative())
    }

    actual suspend fun addIceCandidate(candidate: WebRtcIceCandidate) {
        check(peerConnection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )) { "WebRTC rejected ICE candidate" }
    }

    actual suspend fun getSelectedIcePath(): WebRtcIcePath {
        if (closed) return WebRtcIcePath()
        // shortcut: the pinned JS/Wasm binding returns no stats; use the authenticated peer report there.
        val report = peerConnection.getStats() ?: return WebRtcIcePath()
        if (closed) return WebRtcIcePath()
        return selectedWebRtcIcePath(report.stats.mapValues { (_, stat) -> WebRtcIceStats(stat.type, stat.members) })
    }

    actual fun createDataChannel(
        label: String,
        ordered: Boolean,
        maxRetransmits: Int?
    ): WebRtcDataChannel? {
        val retransmits = maxRetransmits ?: -1
        return peerConnection.createDataChannel(
            label = label,
            ordered = ordered,
            maxRetransmits = retransmits
        )?.let { NonJvmDataChannel(it) }
    }

    actual fun close() {
        if (closed) return
        closed = true
        peerConnection.close()
    }
}

private fun WebRtcIceServerConfig.toNativeIceServer(): IceServer {
    return IceServer(
        urls = urls,
        username = username,
        password = password,
        tlsCertPolicy = TlsCertPolicy.TlsCertPolicySecure,
    )
}

private fun SessionDescription.toWebRtcDescription(): WebRtcSessionDescription {
    val type = when (this.type) {
        SessionDescriptionType.Offer -> WebRtcSdpType.Offer
        SessionDescriptionType.Answer -> WebRtcSdpType.Answer
        SessionDescriptionType.Pranswer -> WebRtcSdpType.Answer
        SessionDescriptionType.Rollback -> WebRtcSdpType.Answer
    }
    return WebRtcSessionDescription(type, sdp)
}

private fun WebRtcSessionDescription.toNative(): SessionDescription {
    val type = when (this.type) {
        WebRtcSdpType.Offer -> SessionDescriptionType.Offer
        WebRtcSdpType.Answer -> SessionDescriptionType.Answer
    }
    return SessionDescription(type, sdp)
}

private fun DataChannelState.toWebRtcState(): WebRtcDataChannelState {
    return when (this) {
        DataChannelState.Connecting -> WebRtcDataChannelState.Connecting
        DataChannelState.Open -> WebRtcDataChannelState.Open
        DataChannelState.Closing -> WebRtcDataChannelState.Closing
        DataChannelState.Closed -> WebRtcDataChannelState.Closed
    }
}

private fun PeerConnectionState.toWebRtcState(): WebRtcPeerConnectionState {
    return when (this) {
        PeerConnectionState.New -> WebRtcPeerConnectionState.New
        PeerConnectionState.Connecting -> WebRtcPeerConnectionState.Connecting
        PeerConnectionState.Connected -> WebRtcPeerConnectionState.Connected
        PeerConnectionState.Disconnected -> WebRtcPeerConnectionState.Disconnected
        PeerConnectionState.Failed -> WebRtcPeerConnectionState.Failed
        PeerConnectionState.Closed -> WebRtcPeerConnectionState.Closed
    }
}
