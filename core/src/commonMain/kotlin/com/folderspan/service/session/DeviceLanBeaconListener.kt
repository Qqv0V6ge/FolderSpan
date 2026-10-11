package com.folderspan.service.session

import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import strings.AppStrings

internal class DeviceLanBeaconListener(
    private val scope: CoroutineScope,
    private val onPacket: suspend (host: String, payload: ByteArray) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        LogKit.i(AppStrings.ui_lan_beacon_listens_port_arg0.format(arg0 = (DEVICE_LAN_BEACON_PORT).toString()))
        job = scope.launch {
            while (isActive) {
                try {
                    DeviceLanBeaconUdp.listen { host, payload ->
                        onPacket(host, payload)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogKit.d("lan beacon listen failed: ${error.message}")
                }
                if (isActive) delay(1.seconds)
            }
        }
    }

    fun stop() {
        LogKit.i(AppStrings.ui_lan_beacon_stops_listening)
        job?.cancel()
        job = null
    }
}
