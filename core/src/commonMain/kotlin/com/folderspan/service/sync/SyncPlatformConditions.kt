package com.folderspan.service.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal enum class SyncNetworkType { Unknown, Offline, Wifi, Ethernet, Cellular, Other }
enum class SyncRunCondition { Wifi, Charging }

/** Transient platform observations; an unknown mobile condition never authorizes a run. */
internal data class SyncPlatformConditions(
    val supportsConditions: Boolean,
    val network: SyncNetworkType = SyncNetworkType.Unknown,
    val charging: Boolean? = null,
    val networkRevision: Long = 0,
) {
    fun unmetConditions(requireWifi: Boolean, requireCharging: Boolean): Set<SyncRunCondition> = buildSet {
        if (!supportsConditions) return@buildSet
        if (requireWifi && network != SyncNetworkType.Wifi && network != SyncNetworkType.Ethernet) add(SyncRunCondition.Wifi)
        if (requireCharging && charging != true) add(SyncRunCondition.Charging)
    }
}

internal fun classifySyncNetwork(connected: Boolean?, wifi: Boolean = false, ethernet: Boolean = false,
    cellular: Boolean = false): SyncNetworkType = when {
    connected == null -> SyncNetworkType.Unknown
    !connected -> SyncNetworkType.Offline
    // A VPN that can also use cellular must not authorize Wi-Fi-only traffic.
    cellular -> SyncNetworkType.Cellular
    ethernet -> SyncNetworkType.Ethernet
    wifi -> SyncNetworkType.Wifi
    else -> SyncNetworkType.Other
}

internal expect val platformSupportsSyncConditions: Boolean
internal expect fun observeSyncPlatformConditions(): Flow<SyncPlatformConditions>

/** Owns one cold platform listener; cancelling its owner or closing it releases that listener. */
internal class SyncConditionsMonitor(
    scope: CoroutineScope,
    supportsConditions: Boolean = platformSupportsSyncConditions,
    observations: Flow<SyncPlatformConditions> = observeSyncPlatformConditions(),
) {
    private val current = MutableStateFlow(SyncPlatformConditions(supportsConditions))
    val state: StateFlow<SyncPlatformConditions> = current.asStateFlow()
    private val job = scope.launch {
        observations.catch { error ->
            if (error is CancellationException) throw error
            emit(SyncPlatformConditions(supportsConditions))
        }.collect { current.value = it.copy(supportsConditions = supportsConditions) }
    }

    fun close() { job.cancel() }
}
