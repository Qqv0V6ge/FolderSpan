package com.folderspan.service.session

import com.folderspan.getSocketDevice
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import strings.AppStrings

internal class DeviceLanBeaconAdvertiser(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(sessionPort: Int) {
        if (job?.isActive == true) return
        LogKit.i(AppStrings.ui_lan_beacon_port_arg0_sessionport_arg1.format(arg0 = (DEVICE_LAN_BEACON_PORT).toString(), arg1 = (sessionPort).toString()))
        job = scope.launch {
            while (isActive) {
                runCatching {
                    val device = getSocketDevice()
                    val beacon = DeviceLanBeacons.create(device, sessionPort = sessionPort) ?: return@runCatching
                    DeviceLanBeaconUdp.broadcast(DeviceLanBeacons.encode(beacon))
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    LogKit.d("lan beacon broadcast failed: ${error.message}")
                }
                delay(2.seconds)
            }
        }
    }

    fun stop() {
        LogKit.i(AppStrings.ui_lan_beacon_broadcast_stop)
        job?.cancel()
        job = null
    }
}
