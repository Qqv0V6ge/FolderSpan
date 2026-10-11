package com.folderspan.service.webrtc.models

data class WebRtcConfig(
    val wssUrl: String,
    val roomId: String,
    val iceServers: List<WebRtcIceServerConfig>,
    val unreliableMode: Boolean,
    val headers: Map<String, String> = emptyMap()
)

enum class WebRtcConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Disconnected,
    Error
}

enum class TransferDirection {
    Send,
    Receive
}

enum class TransferStatus {
    InProgress,
    Completed,
    Failed
}

data class TransferProgress(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBytesPerSec: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val errorMessage: String? = null,
    val averageSpeedBytesPerSec: Long = 0L,
    val elapsedMs: Long = 0L,
    val peerId: String? = null,
    val peerName: String? = null
)
