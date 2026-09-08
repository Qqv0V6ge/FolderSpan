package com.folderspan.service.webrtc.controller.core

import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcPeerSessionDisplayStatus
import com.folderspan.service.webrtc.signaling.SignalingDevice

data class WebRtcPeerSessionSummary(
    val peer: SignalingDevice,
    val status: WebRtcConnectionStatus,
    val displayStatus: WebRtcPeerSessionDisplayStatus,
    val isOfferer: Boolean,
    val isSelectedTarget: Boolean,
    val isPreferredPeer: Boolean,
    val isAwaitingApproval: Boolean,
    val isShareSession: Boolean,
    val sessionPhase: WebRtcDeviceSessionPhase,
)

enum class WebRtcDeviceSessionPhase {
    Signaling,
    PeerConnection,
    DataChannel,
    SessionAuthentication,
    Ready,
    Failed,
}

data class WebRtcConnectRequestEvent(
    val peer: SignalingDevice,
    val roomId: String,
    val connectionAttemptId: String,
    val shareNonce: String = "",
    val tlsFingerprintSha256: String = "",
)

internal fun chooseTransferTargetPeerId(
    selectedPeerId: String?,
    preferredPeerId: String?,
    connectedPeerIds: Set<String>,
    availablePeerIds: Set<String>,
): String? = when {
    !selectedPeerId.isNullOrBlank() && selectedPeerId in connectedPeerIds -> selectedPeerId
    !preferredPeerId.isNullOrBlank() && preferredPeerId in connectedPeerIds -> preferredPeerId
    connectedPeerIds.isNotEmpty() -> connectedPeerIds.min()
    !selectedPeerId.isNullOrBlank() && selectedPeerId in availablePeerIds -> selectedPeerId
    !preferredPeerId.isNullOrBlank() && preferredPeerId in availablePeerIds -> preferredPeerId
    availablePeerIds.isNotEmpty() -> availablePeerIds.min()
    else -> null
}

internal data class HttpSignalingOpenedRoomState(
    val connectionStatus: WebRtcConnectionStatus,
    val activeRoomConfig: WebRtcConfig,
)

internal fun resolveHttpSignalingOpenedRoomState(
    currentConfig: WebRtcConfig?,
    openedConfig: WebRtcConfig,
): HttpSignalingOpenedRoomState? {
    if (currentConfig != openedConfig) return null
    return HttpSignalingOpenedRoomState(
        connectionStatus = WebRtcConnectionStatus.Connected,
        activeRoomConfig = openedConfig,
    )
}

interface IncomingFileChunkWriter {
    suspend fun prepare(path: String, fileSize: Long): Result<Boolean> = Result.success(true)

    suspend fun writeChunk(
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean>

    suspend fun commit(path: String, fileSize: Long): Result<Boolean> = Result.success(true)

    fun cleanup(path: String) = Unit
}
