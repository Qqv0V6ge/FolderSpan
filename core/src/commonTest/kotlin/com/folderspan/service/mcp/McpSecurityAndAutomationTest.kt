package com.folderspan.service.mcp

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.mcp.automation.ActiveIpv4SubnetProvider
import com.folderspan.service.mcp.automation.DeviceScanOperationStore
import com.folderspan.service.mcp.automation.DeviceScanStatus
import com.folderspan.service.mcp.automation.Ipv4InterfaceSubnet
import com.folderspan.service.mcp.automation.McpAutomationException
import com.folderspan.service.mcp.automation.McpDeviceProbe
import com.folderspan.service.mcp.automation.McpOpaqueCursor
import com.folderspan.service.mcp.automation.McpTaskFacade
import com.folderspan.service.mcp.automation.groupOnlineDevices
import com.folderspan.service.mcp.http.McpAllowedHostProvider
import com.folderspan.service.mcp.http.McpHttpSecurityPolicy
import com.folderspan.service.mcp.http.McpHttpSessionStore
import com.folderspan.service.mcp.tools.McpJsonSchemaValidator
import com.folderspan.service.mcp.http.buildMcpAdvertisedEndpoints
import com.folderspan.service.mcp.http.isLoopbackHost
import com.folderspan.service.mcp.http.mcpAdvertisedHosts
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskFailureResultStore
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class McpSecurityAndAutomationTest {
    @Test
    fun advertisedEndpointsAreDeduplicatedHttpsFirstAndIpv6Safe() {
        val endpoints = buildMcpAdvertisedEndpoints(
            listOf("192.168.1.20", "192.168.1.20", "fe80::1"),
            52137,
        )

        assertEquals(listOf("https", "https"), endpoints.map { it.scheme })
        assertEquals(1, endpoints.count { it.host == "192.168.1.20" })
        assertEquals("https://[fe80::1]:52137/mcp", endpoints[1].statefulUrl)
        assertEquals(listOf("127.0.0.1", "localhost"), mcpAdvertisedHosts(lanAccess = false))
        assertTrue(mcpAdvertisedHosts(lanAccess = true, lanAddresses = listOf("192.168.1.20")).contains("192.168.1.20"))
    }

    @Test
    fun securityPolicyRejectsDnsRebindingAndNonLoopbackOriginsBeforeAuthentication() {
        val policy = McpHttpSecurityPolicy(
            allowedHostProvider = McpAllowedHostProvider { setOf("192.168.1.20", "fe80::1") },
        )

        assertTrue(policy.allows("192.168.1.20:52137", null))
        assertTrue(policy.allows("[fe80::1]:52137", "http://localhost:3000"))
        assertTrue(policy.allows("192.168.1.20", "http://127.0.0.1"))
        assertTrue(policy.allows("127.0.0.2", "http://127.0.0.2"))
        assertFalse(policy.allows("192.168.1.20", "http://127.evil.com"))
        assertFalse(isLoopbackHost("127.evil.com"))
        assertTrue(isLoopbackHost("127.0.0.2"))
        assertFalse(policy.allows("192.168.1.20", "https://automation.example:8443"))
        assertFalse(policy.allows("attacker.example", null))
        assertFalse(policy.allows("192.168.1.20", "https://unlisted.example"))
        assertFalse(policy.allows("192.168.1.20", "http://localhost/path"))
        assertFalse(policy.allows(null, null))
    }

    @Test
    fun sessionsEnforceOwnershipCapacityAndIdleExpiry() = runTest {
        var now = 1_000L
        val store = McpHttpSessionStore(nowMillis = { now })
        val first = store.create("token-a")

        assertNull(store.get(first.id, "token-b"))
        assertTrue(store.contains(first.id, "token-a"))
        repeat(McpHttpSessionStore.MAX_SESSIONS_PER_TOKEN - 1) { store.create("token-a") }
        assertFailsWith<Exception> { store.create("token-a") }

        now += McpHttpSessionStore.SESSION_IDLE_TIMEOUT_MS + 1
        assertNull(store.get(first.id, "token-a"))
        assertFalse(store.contains(first.id, "token-a"))
        assertTrue(store.create("token-a").id.isNotBlank())
    }

    @Test
    fun opaqueCursorPagesAndRejectsTampering() {
        val first = McpOpaqueCursor.page((1..120).toList(), cursor = null, limit = 50)
        val second = McpOpaqueCursor.page((1..120).toList(), cursor = first.nextCursor, limit = 50)

        assertEquals((1..50).toList(), first.items)
        assertEquals((51..100).toList(), second.items)
        assertFailsWith<McpAutomationException> { McpOpaqueCursor.offset("not-a-cursor") }
        assertFailsWith<McpAutomationException> { McpOpaqueCursor.limit(201) }
    }

    @Test
    fun deviceScanUsesAllActiveSubnetsByDefaultAndHonorsCidrLimit() = runTest {
        val probed = mutableListOf<String>()
        val store = DeviceScanOperationStore(
            scope = this,
            subnetProvider = ActiveIpv4SubnetProvider {
                listOf(
                    Ipv4InterfaceSubnet("10.0.0.1", 30),
                    Ipv4InterfaceSubnet("192.168.8.1", 30),
                )
            },
            probe = McpDeviceProbe { address, _ ->
                probed += address
                address == "10.0.0.2"
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { 1L },
        )

        val started = store.start(subnet = null, port = 12040)
        advanceUntilIdle()
        val completed = store.get(started.id)!!

        assertEquals(DeviceScanStatus.Success, completed.status)
        assertEquals(listOf("10.0.0.2"), completed.discoveredAddresses)
        assertEquals(setOf("10.0.0.2", "192.168.8.2"), probed.toSet())
        assertFailsWith<IllegalArgumentException> { store.start("10.0.0.0/19", 12040) }
        assertFailsWith<IllegalArgumentException> { store.start("10.0.0.0/20", 12040) }
        assertEquals(1, store.start("10.0.0.0/30", 12040).totalHosts)
    }

    @Test
    fun jsonSchemaValidatorRejectsUnknownMissingAndOversizedValues() {
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 4)
                })
            })
        }

        McpJsonSchemaValidator.validate(buildJsonObject { put("name", "test") }, schema)
        assertFailsWith<McpAutomationException> {
            McpJsonSchemaValidator.validate(buildJsonObject {}, schema)
        }
        assertFailsWith<McpAutomationException> {
            McpJsonSchemaValidator.validate(buildJsonObject {
                put("name", "test")
                put("secret", "must-not-pass")
            }, schema)
        }
        assertFailsWith<McpAutomationException> {
            McpJsonSchemaValidator.validate(buildJsonObject { put("name", "oversized") }, schema)
        }
    }

    @Test
    fun taskFacadeRedactsSecretsAndRejectsInvalidTransitions() {
        val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        state.addOrUpdate(
            Task(
                taskType = TaskType.Copy,
                key = 41,
                status = StatusEnum.SUCCESS,
                protocol = FileProtocol.Local,
                values = mapOf("path" to "/tmp/a", "password" to "hidden", "accessToken" to "hidden"),
            ),
        )
        val facade = McpTaskFacade(state)

        val task = facade.list(cursor = null, limit = 10).items.single()
        assertEquals(mapOf("path" to "/tmp/a"), task.values)
        assertEquals(listOf("delete"), task.availableActions)
        assertFailsWith<McpAutomationException> { facade.pause(task.id) }
        assertTrue(facade.delete(task.id))
        assertFailsWith<McpAutomationException> { facade.get(task.id) }

        state.addOrUpdate(
            Task(
                taskType = TaskType.Move,
                key = 42,
                status = StatusEnum.LOADING,
                protocol = FileProtocol.Device,
                protocolId = "device-a",
            ),
        )
        assertEquals("pause", facade.pause(42).status)
        assertEquals("loading", facade.resume(42).status)
    }

    @Test
    fun deviceAndWebRtcListsContainOnlyOnlinePeersGroupedByStatus() {
        fun device(
            id: String,
            status: ConnectType,
            transport: DeviceTransportType = DeviceTransportType.Session,
        ) = SocketDevice(id, id, "/", "192.168.1.2", 12040, DeviceType.JVM, status, transport)

        val devices = listOf(
            device("connected", ConnectType.Connect),
            device("connecting", ConnectType.Loading),
            device("approval", ConnectType.New),
            device("discovered", ConnectType.UnConnect),
            device("failed", ConnectType.Fail),
            device("rejected", ConnectType.Rejected),
            device("rtc", ConnectType.Connect, DeviceTransportType.WebRtc),
        )
        val http = groupOnlineDevices(devices, setOf("approval"), DeviceTransportType.Session)
        val webRtc = groupOnlineDevices(devices, emptySet(), DeviceTransportType.WebRtc)

        assertEquals(listOf("connected"), http.connected.map { it.id })
        assertEquals(listOf("connecting"), http.connecting.map { it.id })
        assertEquals(listOf("approval"), http.approvalRequired.map { it.id })
        assertEquals(listOf("discovered"), http.discovered.map { it.id })
        assertEquals(listOf("rtc"), webRtc.connected.map { it.id })
        assertTrue(webRtc.connecting.isEmpty() && webRtc.approvalRequired.isEmpty() && webRtc.discovered.isEmpty())
    }
}
