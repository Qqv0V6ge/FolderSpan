@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState.UIDeviceBatteryStateCharging
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.UIKit.UIDeviceBatteryState.UIDeviceBatteryStateFull
import platform.UIKit.UIDeviceBatteryState.UIDeviceBatteryStateUnplugged
import platform.darwin.dispatch_get_main_queue

internal actual val platformSupportsSyncConditions = true

// Main-queue collectors share UIKit's process-wide switch and restore its previous value after the last one closes.
private object SyncBatteryMonitoring {
    private var users = 0
    private var previouslyEnabled = false
    fun acquire() {
        if (users++ == 0) {
            previouslyEnabled = UIDevice.currentDevice.batteryMonitoringEnabled
            UIDevice.currentDevice.batteryMonitoringEnabled = true
        }
    }
    fun release() {
        if (--users == 0) UIDevice.currentDevice.batteryMonitoringEnabled = previouslyEnabled
    }
}

internal actual fun observeSyncPlatformConditions(): Flow<SyncPlatformConditions> = callbackFlow {
    var current = SyncPlatformConditions(true)
    var closed = false
    var observedPath = false
    val center = NSNotificationCenter.defaultCenter
    val monitor = checkNotNull(nw_path_monitor_create())
    SyncBatteryMonitoring.acquire()
    fun chargingChanged() {
        if (closed) return
        current = current.copy(charging = when (UIDevice.currentDevice.batteryState) {
            UIDeviceBatteryStateCharging, UIDeviceBatteryStateFull -> true
            UIDeviceBatteryStateUnplugged -> false
            else -> null
        })
        trySend(current)
    }
    val observer = center.addObserverForName(UIDeviceBatteryStateDidChangeNotification, null, NSOperationQueue.mainQueue) {
        chargingChanged()
    }
    try {
        chargingChanged()
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (!closed && path != null) {
                current = current.copy(network = classifySyncNetwork(
                    connected = nw_path_get_status(path) == nw_path_status_satisfied,
                    wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi),
                    ethernet = nw_path_uses_interface_type(path, nw_interface_type_wired),
                    cellular = nw_path_uses_interface_type(path, nw_interface_type_cellular),
                ), networkRevision = current.networkRevision + if (observedPath) 1 else 0)
                observedPath = true
                trySend(current)
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
        awaitClose()
    } finally {
        closed = true
        nw_path_monitor_cancel(monitor)
        center.removeObserver(observer)
        SyncBatteryMonitoring.release()
    }
}.buffer(Channel.CONFLATED).flowOn(Dispatchers.Main.immediate)
