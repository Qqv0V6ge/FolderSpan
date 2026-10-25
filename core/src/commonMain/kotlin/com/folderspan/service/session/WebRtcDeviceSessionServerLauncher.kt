package com.folderspan.service.session

import com.folderspan.service.webrtc.models.WebRtcIceSnapshot
import kotlinx.coroutines.CoroutineScope

internal typealias DeviceSessionConnectivityProbe = suspend () -> Boolean

internal fun interface WebRtcDeviceSessionServerLauncher {
    suspend fun run(
        channel: DeviceSessionByteChannel,
        remoteDeviceId: String,
        connectionAttemptId: String,
        scope: CoroutineScope,
        iceSnapshot: () -> WebRtcIceSnapshot?,
        onAuthenticated: suspend (DeviceSessionConnectivityProbe) -> Unit,
    )
}

internal expect fun createWebRtcDeviceSessionServerLauncher(): WebRtcDeviceSessionServerLauncher?
