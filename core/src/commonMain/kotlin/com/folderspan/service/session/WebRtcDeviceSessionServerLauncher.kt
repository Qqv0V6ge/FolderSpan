package com.folderspan.service.session

import kotlinx.coroutines.CoroutineScope

internal fun interface WebRtcDeviceSessionServerLauncher {
    suspend fun run(
        channel: DeviceSessionByteChannel,
        remoteDeviceId: String,
        connectionAttemptId: String,
        scope: CoroutineScope,
        onAuthenticated: suspend () -> Unit,
    )
}

internal expect fun createWebRtcDeviceSessionServerLauncher(): WebRtcDeviceSessionServerLauncher?
