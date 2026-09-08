package com.folderspan.service.webrtc.models

import kotlinx.coroutines.flow.Flow

data class WebRtcSessionDescription(
    val type: WebRtcSdpType,
    val sdp: String
)

enum class WebRtcSdpType {
    Offer,
    Answer
}

data class WebRtcIceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)

enum class WebRtcDataChannelState {
    Connecting,
    Open,
    Closing,
    Closed
}

enum class WebRtcPeerConnectionState {
    New,
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed
}

interface WebRtcDataChannel {
    val label: String
    val state: WebRtcDataChannelState
    val bufferedAmount: Long
    val onOpen: Flow<Unit>
    val onClose: Flow<Unit>
    val onMessage: Flow<ByteArray>

    fun send(data: ByteArray): Boolean
    fun close()
}

fun WebRtcIceCandidate.isRelay(): Boolean =
    candidate.contains("typ relay", ignoreCase = true)

expect class WebRtcPeerConnection(iceServers: List<WebRtcIceServerConfig>) {
    val onIceCandidate: Flow<WebRtcIceCandidate>
    val onDataChannel: Flow<WebRtcDataChannel>
    val onConnectionStateChange: Flow<WebRtcPeerConnectionState>

    suspend fun createOffer(): WebRtcSessionDescription
    suspend fun createAnswer(): WebRtcSessionDescription
    suspend fun setLocalDescription(description: WebRtcSessionDescription)
    suspend fun setRemoteDescription(description: WebRtcSessionDescription)
    suspend fun addIceCandidate(candidate: WebRtcIceCandidate)

    fun createDataChannel(
        label: String,
        ordered: Boolean = true,
        maxRetransmits: Int? = null
    ): WebRtcDataChannel?
    fun close()
}
