package com.folderspan.service.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.folderspan.androidContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

internal actual val platformSupportsSyncConditions = true

internal actual fun observeSyncPlatformConditions(): Flow<SyncPlatformConditions> = callbackFlow {
    val context = androidContext().applicationContext
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val lock = Any()
    var current = SyncPlatformConditions(true)
    var active: Network? = null
    var closed = false
    var networkRegistered = false
    var batteryRegistered = false
    var batteryObserved = false
    var linkSignature: String? = null

    fun networkType(capabilities: NetworkCapabilities?) = classifySyncNetwork(
        connected = capabilities?.let { true },
        wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
    )
    fun networkChanged(type: SyncNetworkType) {
        current = current.copy(network = type, networkRevision = current.networkRevision + 1)
        trySend(current)
    }
    fun chargingChanged(intent: Intent?) {
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        current = current.copy(charging = plugged.takeIf { it >= 0 }?.let { it != 0 })
        trySend(current)
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            if (!closed && active != network) {
                active = network
                linkSignature = null
                networkChanged(SyncNetworkType.Unknown)
            }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            if (!closed && active == network) {
                val type = networkType(capabilities)
                if (type != current.network && current.network != SyncNetworkType.Unknown) networkChanged(type)
                else { current = current.copy(network = type); trySend(current) }
            }
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(lock) {
            if (!closed && active == network) {
                val signature = properties.toString()
                if (linkSignature != null && linkSignature != signature) networkChanged(current.network)
                linkSignature = signature
            }
        }
        override fun onLost(network: Network) = synchronized(lock) {
            if (!closed && active == network) { active = null; networkChanged(SyncNetworkType.Offline) }
        }
    }
    val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = synchronized(lock) {
            if (!closed) { batteryObserved = true; chargingChanged(intent) }
        }
    }
    try {
        // Read once before registering; capability callbacks, not synchronous reads in onAvailable, own later changes.
        try {
            active = manager?.activeNetwork
            linkSignature = active?.let { manager?.getLinkProperties(it)?.toString() }
            current = current.copy(network = if (manager == null) SyncNetworkType.Unknown
                else if (active == null) SyncNetworkType.Offline else networkType(manager.getNetworkCapabilities(active)))
        } catch (_: SecurityException) { current = current.copy(network = SyncNetworkType.Unknown) }
        synchronized(lock) { trySend(current) }
        if (manager != null) {
            try { manager.registerDefaultNetworkCallback(callback); networkRegistered = true }
            catch (_: SecurityException) { synchronized(lock) { networkChanged(SyncNetworkType.Unknown) } }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(battery, filter)
        batteryRegistered = true
        synchronized(lock) { if (!batteryObserved) chargingChanged(sticky) }
        awaitClose()
    } finally {
        synchronized(lock) { closed = true }
        if (networkRegistered) runCatching { manager?.unregisterNetworkCallback(callback) }
        if (batteryRegistered) runCatching { context.unregisterReceiver(battery) }
    }
}.buffer(Channel.CONFLATED)
