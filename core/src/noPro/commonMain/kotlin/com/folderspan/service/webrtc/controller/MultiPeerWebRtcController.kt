package com.folderspan.service.webrtc.controller

import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceThemeResponse
import com.folderspan.service.message.DeviceMessageLiveEndpointRegistry
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.service.session.DeviceSessionClients
import com.folderspan.service.webrtc.controller.core.WebRtcConnectRequestEvent
import com.folderspan.service.webrtc.controller.core.WebRtcPeerSessionSummary
import com.folderspan.service.webrtc.models.TransferProgress
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import strings.AppStrings

@Suppress("UNUSED_PARAMETER")
class MultiPeerWebRtcController(
    scope: CoroutineScope,
    deviceMessageEndpointRegistry: DeviceMessageLiveEndpointRegistry? = null,
    incomingDeviceMessageCoordinator: IncomingDeviceMessageCoordinator? = null,
) {
    private val status = MutableStateFlow(WebRtcConnectionStatus.Idle)
    private val error = MutableStateFlow<String?>(null)

    val connectionStatus = status.asStateFlow()
    val lastError = error.asStateFlow()
    val activeRoomConfig: StateFlow<WebRtcConfig?> = MutableStateFlow(null)
    val transfers: StateFlow<Map<String, TransferProgress>> = MutableStateFlow(emptyMap())
    val peerSessionStates: StateFlow<List<WebRtcPeerSessionSummary>> = MutableStateFlow(emptyList())
    val connectRequests = emptyFlow<WebRtcConnectRequestEvent>()
    internal val officialLocalDeviceId: String? = null

    fun connect(config: WebRtcConfig) = setConnectionError(AppStrings.webrtc_pro_only)
    fun connectBrowserHttpSignalingClient(baseUrl: String) = setConnectionError(AppStrings.webrtc_pro_only)
    fun requestPeerConnection(remoteId: String, shareNonce: String = "", tlsFingerprintSha256: String = "") =
        setConnectionError(AppStrings.webrtc_pro_only)

    fun setConnectionError(message: String) {
        error.value = message
        status.value = WebRtcConnectionStatus.Error
    }

    fun disconnect() {
        error.value = null
        status.value = WebRtcConnectionStatus.Idle
    }

    suspend fun stop() = disconnect()
    fun disconnectPeer(remoteId: String) = Unit
    fun approveConnectRequest(remoteId: String, authorization: DeviceSessionBootstrapAuthorization) = Unit
    fun rejectConnectRequest(remoteId: String, message: String = AppStrings.webrtc_connection_request_rejected) = Unit
    internal fun deviceSessionClients(remoteId: String): DeviceSessionClients? = null
    internal fun isShareSession(remoteId: String): Boolean = false

    suspend fun getPeerTheme(remoteId: String, isDark: Boolean): Result<DeviceThemeResponse> =
        Result.failure(IllegalStateException(AppStrings.webrtc_pro_only))
}
