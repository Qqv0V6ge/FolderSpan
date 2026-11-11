package com.folderspan.ui.state.main

import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.service.sync.SyncPlatformConditions
import com.folderspan.service.sync.platformSupportsSyncConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private val syncEventClock = TimeSource.Monotonic.markNow()

internal fun SyncTask.eventEndpointRefs(type: SyncEndpointType): Set<String> = buildSet {
    if (sourceType == type && sourceRef.isNotBlank()) add(sourceRef)
    val targets = destinations.ifEmpty { listOf(StorageDestination(targetType, targetRef, targetPath, targetStorageReference)) }
    targets.filter { it.type == type && it.ref.isNotBlank() }.forEach { add(it.ref) }
}

/** Event probes never create runs; only accepted requests consume the per-task ten-minute interval. */
internal class SyncEventTriggerCoordinator(
    private val scope: CoroutineScope,
    private val tasks: suspend () -> List<SyncTask>,
    private val owned: (SyncTask) -> Boolean,
    private val probeNetwork: suspend (SyncTask) -> Boolean,
    private val requestRun: suspend (SyncTask) -> Boolean,
    private val nowMillis: () -> Long = { syncEventClock.elapsedNow().inWholeMilliseconds },
    private val networkDebounceMillis: Long = 5_000,
    private val probeTimeoutMillis: Long = 10_000,
) {
    private val mutex = Mutex()
    private var connected: Set<String>? = null
    private var platformNetwork: Pair<Long, Boolean>? = null
    private var networkJob: Job? = null
    private val lastEvent = mutableMapOf<Long, Long>()

    suspend fun devicesChanged(ids: Set<String>) {
        val added = mutex.withLock { val before = connected; connected = ids; if (before == null) emptySet() else ids - before }
        if (added.isEmpty()) return
        for (task in tasks()) if (task.triggerOnDeviceConnect && task.eventEndpointRefs(SyncEndpointType.Device).any { it in added }) {
            dispatch(task, added)
        }
    }

    suspend fun resetDevices() { mutex.withLock { connected = null } }

    suspend fun platformNetworkChanged(state: SyncPlatformConditions) {
        val available = state.network != SyncNetworkType.Offline && state.network != SyncNetworkType.Unknown
        val next = state.networkRevision to available
        val changed = mutex.withLock {
            val before = platformNetwork
            platformNetwork = next
            before != null && before != next && state.networkRevision > 0
        }
        if (changed) networkChanged(available)
    }

    suspend fun resetNetwork() {
        mutex.withLock { platformNetwork = null; networkJob?.cancel(); networkJob = null }
    }

    suspend fun networkChanged(available: Boolean = true) = mutex.withLock {
        networkJob?.cancel()
        networkJob = if (!available) null else scope.launch {
            delay(networkDebounceMillis)
            for (task in tasks()) {
                currentCoroutineContext().ensureActive()
                if (!task.triggerOnNetworkAvailable || task.eventEndpointRefs(SyncEndpointType.Network).isEmpty() ||
                    !owned(task) || !mutex.withLock { intervalElapsed(task.id) }) continue
                val ready = try { withTimeoutOrNull(probeTimeoutMillis) { probeNetwork(task) } == true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (ready) dispatch(task, null)
            }
        }
    }

    private fun intervalElapsed(id: Long): Boolean = lastEvent[id]?.let { nowMillis() - it >= 10 * 60_000L } ?: true

    private suspend fun dispatch(expected: SyncTask, deviceIds: Set<String>?) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val task = tasks().firstOrNull { it.id == expected.id } ?: return@withLock
        if (!owned(task) || syncPreviewFingerprint(task) != syncPreviewFingerprint(expected) || !intervalElapsed(task.id)) return@withLock
        if (deviceIds == null) {
            if (!task.triggerOnNetworkAvailable) return@withLock
        } else if (!task.triggerOnDeviceConnect || task.eventEndpointRefs(SyncEndpointType.Device)
                .none { it in deviceIds && it in connected.orEmpty() }) return@withLock
        if (requestRun(task)) lastEvent[task.id] = nowMillis()
    }
}

/** Reuses the scheduler's one-second tick, including clients that start without a Compose UI. */
internal class SyncEventTriggerRuntime(private val state: SyncState) {
    private var devicesObserved = false
    private var networkJob: Job? = null
    private val coordinator = SyncEventTriggerCoordinator(state.scope,
        tasks = { withContext(Dispatchers.Main) { state.snapshotTasks() } },
        owned = { state.isTaskOwnedByCurrentDevice(it) },
        probeNetwork = { state.probeEventNetworkEndpoints(it) },
        requestRun = { expected -> withContext(Dispatchers.Main) {
            val task = state.getTask(expected.id)
            if (task == null || !state.isTaskOwnedByCurrentDevice(task) ||
                syncPreviewFingerprint(task) != syncPreviewFingerprint(expected)) false
            else if (state.requiresSyncPreview(task)) { state.requestSyncPreviewConfirmation(task.id); false }
            else state.enqueueRun(task) { state.runTaskInternal(task, "event") }
        } })

    suspend fun check() = withContext(Dispatchers.Main) {
        val tasks = state.snapshotTasks().filter { state.isTaskOwnedByCurrentDevice(it) }
        val watchDevices = tasks.any { it.triggerOnDeviceConnect && it.eventEndpointRefs(SyncEndpointType.Device).isNotEmpty() }
        if (watchDevices) coordinator.devicesChanged(state.deviceState.devices.map { it.id }.toSet())
        else if (devicesObserved) coordinator.resetDevices()
        devicesObserved = watchDevices
        val watchNetwork = tasks.any { it.triggerOnNetworkAvailable && it.eventEndpointRefs(SyncEndpointType.Network).isNotEmpty() }
        if (watchNetwork && networkJob == null) networkJob = state.scope.launch {
            try { coroutineScope {
                launch { state.conditionsMonitor.state.collect { coordinator.platformNetworkChanged(it) } }
                if (!platformSupportsSyncConditions) launch {
                    state.deviceState.networkChanges.drop(1).collect {
                        coordinator.networkChanged(state.conditionsMonitor.state.value.network != SyncNetworkType.Offline)
                    }
                }
            } } finally { withContext(NonCancellable) { coordinator.resetNetwork() } }
        }
        if (!watchNetwork) {
            networkJob?.cancelAndJoin()
            networkJob = null
        }
    }
}

private suspend fun SyncState.probeEventNetworkEndpoints(task: SyncTask): Boolean {
    val networks = withContext(Dispatchers.Main) {
        networkState.entries.associate { it.network.buildProtocolId() to it.network }
    }
    return syncEventNetworksAvailable(task) { ref ->
        val network = networks[ref]
        if (network == null) false else {
            val root = network.getRootPaths().firstOrNull()?.path ?: network.pathSeparator
            network.getList(root).isSuccess
        }
    }
}

internal suspend fun syncEventNetworksAvailable(task: SyncTask, probe: suspend (String) -> Boolean): Boolean {
    val results = mutableMapOf<String, Boolean>()
    suspend fun available(ref: String): Boolean {
        results[ref]?.let { return it }
        return probe(ref).also { results[ref] = it }
    }
    if (task.sourceType == SyncEndpointType.Network && !available(task.sourceRef)) return false
    val targets = task.destinations.ifEmpty { listOf(StorageDestination(task.targetType, task.targetRef, task.targetPath, task.targetStorageReference)) }
        .filter { it.type == SyncEndpointType.Network }
    if (task.selectRecommended && task.destinations.isNotEmpty()) {
        for (target in targets) if (available(target.ref)) return true
        return false
    }
    for (target in targets) if (!available(target.ref)) return false
    return true
}
