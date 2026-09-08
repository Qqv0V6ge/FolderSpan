package com.folderspan.ui.state.main

import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.client.DEVICE_HEARTBEAT_STALE_DISCONNECT_MS
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal const val REMOTE_DEVICE_CONNECTION_CHECK_INTERVAL_MS = 5_000L
private const val REMOTE_DEVICE_CONNECTION_STALE_GRACE_MS = 60_000L
internal const val REMOTE_DEVICE_CONNECTION_STALE_AFTER_MS =
    DEVICE_HEARTBEAT_STALE_DISCONNECT_MS + REMOTE_DEVICE_CONNECTION_STALE_GRACE_MS

internal fun sessionPresenceProbeTargets(devices: List<SocketDevice>): List<SocketDevice> =
    devices.filter { device -> device.transportType == DeviceTransportType.Session }

internal fun findStaleRemoteDeviceConnectionIds(
    connections: Map<String, Long>,
    nowMs: Long,
    staleAfterMs: Long = REMOTE_DEVICE_CONNECTION_STALE_AFTER_MS,
): Set<String> {
    require(staleAfterMs >= 0L) { "staleAfterMs must not be negative" }
    return connections
        .filterValues { lastSeenAtMs -> nowMs - lastSeenAtMs >= staleAfterMs }
        .keys
}

internal class RemoteDeviceConnectionMonitor(
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val getConnections: suspend () -> Map<String, Long>,
    private val onStale: suspend (Set<String>) -> Unit,
) {
    data class Config(
        val checkIntervalMs: Long = REMOTE_DEVICE_CONNECTION_CHECK_INTERVAL_MS,
        val staleAfterMs: Long = REMOTE_DEVICE_CONNECTION_STALE_AFTER_MS,
    )

    private var monitorJob: Job? = null

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(config.checkIntervalMs.milliseconds)
                prune()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    internal suspend fun prune() {
        val staleIds = findStaleRemoteDeviceConnectionIds(
            connections = getConnections(),
            nowMs = nowMs(),
            staleAfterMs = config.staleAfterMs,
        )
        if (staleIds.isNotEmpty()) {
            onStale(staleIds)
        }
    }
}

class DevicePresenceMonitor(
    private val scope: CoroutineScope,
    private val client: HttpClient,
    private val config: Config = Config(),
    private val getProbeTargets: suspend () -> List<SocketDevice>,
    private val onStale: suspend (Set<String>) -> Unit,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val getNetworkSignature: suspend () -> Set<String>? = { null },
    private val onNetworkChanged: suspend () -> Unit = {},
) {
    data class Config(
        val checkIntervalMs: Long = 10_000L,
        val staleAfterMs: Long = 30_000L,
        val probeConcurrency: Int = 16,
    )

    private val lastSeenAtMutex = Mutex()
    private val lastSeenAtMs = mutableMapOf<String, Long>()
    private var monitorJob: Job? = null
    private var networkSignature: Set<String>? = null

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(config.checkIntervalMs.milliseconds)
                prune()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    suspend fun markSeen(deviceId: String, atMs: Long = nowMs()) {
        lastSeenAtMutex.withLock { lastSeenAtMs[deviceId] = atMs }
    }

    suspend fun forget(deviceId: String) {
        lastSeenAtMutex.withLock { lastSeenAtMs.remove(deviceId) }
    }

    internal suspend fun prune() {
        checkNetworkChange()
        val now = nowMs()
        val devices = getProbeTargets()
        if (devices.isEmpty()) return

        val results = coroutineScope {
            devices.chunked(config.probeConcurrency).flatMap { chunk ->
                chunk.map { device ->
                    async(Dispatchers.Default) {
                        val alive = device.hasActiveConnection() || device.probeAlive(client)
                        device.id to alive
                    }
                }.awaitAll()
            }
        }

        val staleIds = mutableSetOf<String>()
        for ((deviceId, alive) in results) {
            if (alive) {
                lastSeenAtMutex.withLock { lastSeenAtMs[deviceId] = now }
                continue
            }

            val lastSeen = lastSeenAtMutex.withLock { lastSeenAtMs[deviceId] }
            if (lastSeen == null) {
                lastSeenAtMutex.withLock { lastSeenAtMs[deviceId] = now }
                continue
            }
            if (now - lastSeen >= config.staleAfterMs) {
                staleIds.add(deviceId)
            }
        }

        if (staleIds.isEmpty()) return
        onStale(staleIds)
        lastSeenAtMutex.withLock { staleIds.forEach(lastSeenAtMs::remove) }
    }

    private suspend fun checkNetworkChange() {
        val currentSignature = try {
            getNetworkSignature()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return
        val previousSignature = networkSignature
        networkSignature = currentSignature
        if (previousSignature != null && previousSignature != currentSignature) {
            onNetworkChanged()
        }
    }
}
