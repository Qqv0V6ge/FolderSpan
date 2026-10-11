package com.folderspan.ui.state.main

import com.folderspan.service.http.client.DEVICE_HEARTBEAT_STALE_DISCONNECT_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteDeviceConnectionMonitorTest {
    @Test
    fun serverSessionLeaseOutlivesClientHeartbeatRecoveryWindow() {
        assertEquals(
            60_000L,
            REMOTE_DEVICE_CONNECTION_STALE_AFTER_MS - DEVICE_HEARTBEAT_STALE_DISCONNECT_MS,
        )
        assertEquals(
            emptySet(),
            findStaleRemoteDeviceConnectionIds(
                connections = mapOf("recovering" to 0L),
                nowMs = DEVICE_HEARTBEAT_STALE_DISCONNECT_MS,
            ),
        )
    }

    @Test
    fun staleConnectionsAreReportedAtTimeoutBoundary() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val reported = mutableListOf<Set<String>>()
        val monitor = RemoteDeviceConnectionMonitor(
            scope = scope,
            config = RemoteDeviceConnectionMonitor.Config(staleAfterMs = 30_000L),
            nowMs = { 40_000L },
            getConnections = {
                mapOf(
                    "stale" to 10_000L,
                    "online" to 10_001L,
                    "future" to 40_001L,
                )
            },
            onStale = reported::add,
        )

        monitor.prune()

        assertEquals(listOf(setOf("stale")), reported)
        scope.cancel()
    }

    @Test
    fun noCallbackIsMadeWhenEveryConnectionIsFresh() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var callbackCount = 0
        val monitor = RemoteDeviceConnectionMonitor(
            scope = scope,
            nowMs = { 40_000L },
            getConnections = { mapOf("online" to 39_999L) },
            onStale = { callbackCount++ },
        )

        monitor.prune()

        assertEquals(0, callbackCount)
        scope.cancel()
    }

    @Test
    fun negativeTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            findStaleRemoteDeviceConnectionIds(
                connections = mapOf("device" to 1L),
                nowMs = 2L,
                staleAfterMs = -1L,
            )
        }
    }
}
