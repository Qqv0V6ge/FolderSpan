package com.folderspan.service.http.server

import com.folderspan.createSettings
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

private val easyShareServiceLock = Mutex()
private var easyShareServiceStartingOrRunning = false

suspend fun startBackgroundShareServices(
    fileShareState: FileShareState,
    awaitEasyShareInitializationReady: suspend () -> Boolean = { true },
) {
    runBackgroundShareServices(
        startFileShare = { startFileShareService() },
        startEasyShare = {
            startEasyFileShareService(
                fileShareState = fileShareState,
                awaitInitializationReady = awaitEasyShareInitializationReady,
            )
        }
    )
}

internal suspend fun runBackgroundShareServices(
    startFileShare: suspend () -> Unit,
    startEasyShare: suspend () -> Unit,
) {
    coroutineScope {
        launch { startFileShare() }
        launch { startEasyShare() }
    }
}

suspend fun startEasyFileShareService(
    fileShareState: FileShareState,
    awaitInitializationReady: suspend () -> Boolean = { true },
) {
    val shouldStart = easyShareServiceLock.withLock {
        if (easyShareServiceStartingOrRunning) {
            false
        } else {
            easyShareServiceStartingOrRunning = true
            true
        }
    }
    if (!shouldStart) {
        LogKit.i(AppStrings.ui_simple_sharing_service_is_already_running_skip_repeated_startup)
        return
    }

    try {
        val settings = createSettings()
        SettingsUtils.init(settings)
        val port = SettingsUtils.easyFileShare.getPort()
        val server = HttpShareFileServer.getInstance(fileShareState)
        runEasyFileShareService(
            port = port,
            server = server,
            awaitInitializationReady = {
                !(SettingsUtils.easyFileShare.isAutoStart() &&
                        SettingsUtils.easyFileShare.getSharePaths().isNotEmpty()) || awaitInitializationReady()
            },
            initializeIfNeeded = fileShareState::initializeEasyShareIfNeeded
        )
    } finally {
        easyShareServiceLock.withLock {
            easyShareServiceStartingOrRunning = false
        }
    }
}

internal suspend fun runEasyFileShareService(
    port: Int,
    server: HttpShareFileServerInterface,
    awaitInitializationReady: suspend () -> Boolean = { true },
    initializeIfNeeded: suspend () -> Boolean,
) {
    if (!awaitInitializationReady()) return
    if (!initializeIfNeeded()) return

    try {
        LogKit.i(AppStrings.ui_start_the_simple_sharing_server_port_arg0.format(arg0 = (port).toString()))
        server.start(port)
        if (!server.isRunning()) {
            return
        }
        awaitCancellation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val message = error.toServerStartFailureMessage(port)
        LogKit.e(AppStrings.ui_simple_share_service_start_failed_arg0.format(arg0 = (message)), error)
        if (error.isPortInUseFailure()) {
            notifyServerPortInUse(ServerStartNotificationService.SimpleSharing, port)
        } else {
            notifyServerStartFailure(ServerStartNotificationService.SimpleSharing, port, error)
        }
    } finally {
        try {
            server.stop()
        } catch (error: Throwable) {
            LogKit.w(AppStrings.ui_stop_sharing_service_failed_arg0.format(arg0 = (error.message).toString()), error)
        }
    }
}
