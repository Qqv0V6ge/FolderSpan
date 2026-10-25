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

internal fun WebRtcIceCandidate.usernameFragment(): String? =
    candidate.substringAfter(" ufrag ", "").substringBefore(' ').takeIf(String::isNotBlank)

internal fun String.webRtcIceUfrags(): Set<String> = lineSequence()
    .map(String::trim).filter { it.startsWith("a=ice-ufrag:") }
    .map { it.substringAfter(':') }.filter(String::isNotBlank).toSet()

expect class WebRtcPeerConnection(iceServers: List<WebRtcIceServerConfig>) {
    val onIceCandidate: Flow<WebRtcIceCandidate>
    val onDataChannel: Flow<WebRtcDataChannel>
    val onConnectionStateChange: Flow<WebRtcPeerConnectionState>
    val lastIceServerErrorCode: Int?

    suspend fun createOffer(iceRestart: Boolean = false): WebRtcSessionDescription
    suspend fun createAnswer(): WebRtcSessionDescription
    suspend fun setLocalDescription(description: WebRtcSessionDescription)
    suspend fun setRemoteDescription(description: WebRtcSessionDescription)
    suspend fun addIceCandidate(candidate: WebRtcIceCandidate)
    suspend fun getSelectedIcePath(): WebRtcIcePath

    fun createDataChannel(
        label: String,
        ordered: Boolean = true,
        maxRetransmits: Int? = null
    ): WebRtcDataChannel?
    fun close()
}
