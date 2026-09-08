package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.createSettings
import com.folderspan.service.http.server.raw.RawTlsHttpServer
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicBoolean

private val fileShareServiceStartingOrRunning = AtomicBoolean(false)
private val fileShareRawTlsServer = RawTlsHttpServer()

actual suspend fun startFileShareService() {
    if (!fileShareServiceStartingOrRunning.compareAndSet(false, true)) {
        LogKit.i(AppStrings.ui_file_sharing_https_service_already_running_skip_repeated_startup)
        return
    }

    try {
        val settings = createSettings()
        SettingsUtils.init(settings)
        if (!SettingsUtils.fileShare.isEnabled()) {
            LogKit.i(AppStrings.ui_file_sharing_service_disabled_skipping_startup)
            return
        }

        val port = SettingsUtils.fileShare.getPort()
        try {
            LogKit.i(AppStrings.ui_start_file_sharing_raw_tls_service_port_arg0.format(arg0 = (port).toString()))
            fileShareRawTlsServer.start(port)
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.toServerStartFailureMessage(port)
            LogKit.e(AppStrings.ui_file_sharing_raw_tls_service_failed_start_android_arg0.format(arg0 = message), error)
            if (error.isPortInUseFailure()) {
                notifyServerPortInUse(ServerStartNotificationService.FileSharing, port)
            } else {
                notifyServerStartFailure(ServerStartNotificationService.FileSharing, port, error)
            }
        }
    } finally {
        try {
            fileShareRawTlsServer.stop()
        } catch (error: Throwable) {
            LogKit.w(AppStrings.ui_failed_stop_file_sharing_raw_tls_service_arg0.format(arg0 = (error.message).toString()), error)
        }
        fileShareServiceStartingOrRunning.set(false)
    }
}
