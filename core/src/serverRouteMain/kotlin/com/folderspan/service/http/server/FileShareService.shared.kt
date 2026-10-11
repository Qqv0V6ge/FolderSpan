package com.folderspan.service.http.server

import com.folderspan.service.session.DeviceLanBeaconAdvertiser
import com.folderspan.service.session.DeviceListenerCallbacks
import com.folderspan.service.session.createDeviceListenerCallbacks
import com.folderspan.utils.SettingsUtils
import com.folderspan.runtime.RuntimeServiceFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex

private val fileSharingMutex = Mutex()

actual suspend fun startFileShareService() = coroutineScope {
    if (!SettingsUtils.fileShare.isEnabled() || !fileSharingMutex.tryLock()) return@coroutineScope
    val port = SettingsUtils.fileShare.getPort()
    val advertiser = DeviceLanBeaconAdvertiser(this)
    try {
        advertiser.start(port)
        runDeviceListener(port, createDeviceListenerCallbacks())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        RuntimeServiceFailures.report("file-sharing", error)
        if (error.isPortInUseFailure()) notifyServerPortInUse(ServerStartNotificationService.FileSharing, port)
        else notifyServerStartFailure(ServerStartNotificationService.FileSharing, port, error)
    } finally {
        advertiser.stop()
        fileSharingMutex.unlock()
    }
}

internal expect suspend fun runDeviceListener(port: Int, callbacks: DeviceListenerCallbacks)
