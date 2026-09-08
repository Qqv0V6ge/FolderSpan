package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.session.DeviceSessionClientManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevicePresenceMonitorTest {
    @Test
    fun unconnectedSessionDevicesArePresenceTargets() {
        val sessionDevice = SocketDevice(
            id = "session",
            name = "Session",
            pathSeparator = "/",
            type = DeviceType.JVM,
        )
        val webRtcDevice = sessionDevice.withCopy(
            id = "webrtc",
            transportType = DeviceTransportType.WebRtc,
        )

        assertEquals(
            listOf(sessionDevice),
            sessionPresenceProbeTargets(listOf(sessionDevice, webRtcDevice)),
        )
    }

    @Test
    fun connectedSessionDevicesAreNotReportedStale() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
            )
        }
        val reported = mutableListOf<Set<String>>()
        val device = SocketDevice(
            id = "session-alive",
            name = "Remote",
            pathSeparator = "/",
            host = "203.0.113.10",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            tlsFingerprintSha256 = "a".repeat(64),
        ).apply {
            sessionClient = DeviceSessionClientManager()
        }
        val monitor = DevicePresenceMonitor(
            scope = scope,
            client = HttpClient(engine),
            config = DevicePresenceMonitor.Config(staleAfterMs = 1L),
            getProbeTargets = { listOf(device) },
            onStale = reported::add,
        )
        monitor.markSeen(device.id, atMs = 0L)

        monitor.prune()

        assertEquals(emptyList(), reported)
        assertNull(captured)
        scope.cancel()
    }

    @Test
    fun unconnectedDiscoveredDeviceIsReportedAfterLastSeenTimeout() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var nowMs = 1_000L
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
            )
        }
        val reported = mutableListOf<Set<String>>()
        val device = SocketDevice(
            id = "discovered-only",
            name = "Remote",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
        )
        val monitor = DevicePresenceMonitor(
            scope = scope,
            client = HttpClient(engine),
            config = DevicePresenceMonitor.Config(staleAfterMs = 30_000L),
            getProbeTargets = { listOf(device) },
            onStale = reported::add,
            nowMs = { nowMs },
        )
        monitor.markSeen(device.id)

        nowMs = 30_999L
        monitor.prune()
        assertEquals(emptyList(), reported)

        nowMs = 31_000L
        monitor.prune()
        assertEquals(listOf(setOf(device.id)), reported)
        assertNull(captured)
        scope.cancel()
    }

    @Test
    fun networkSignatureChangeIsReportedOnce() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var networkSignature = setOf("192.168.1.2")
        var networkChanges = 0
        val monitor = DevicePresenceMonitor(
            scope = scope,
            client = HttpClient(MockEngine { respond(ByteReadChannel.Empty) }),
            getProbeTargets = { emptyList() },
            onStale = {},
            getNetworkSignature = { networkSignature },
            onNetworkChanged = { networkChanges += 1 },
        )

        monitor.prune()
        monitor.prune()
        assertEquals(0, networkChanges)

        networkSignature = setOf("192.168.2.3")
        monitor.prune()
        monitor.prune()
        assertEquals(1, networkChanges)
        scope.cancel()
    }
}
