@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.ui.state.main

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.extensions.isConnectionException
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.session.*
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import sun.misc.Unsafe
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.*

class DeviceSessionLifecycleJvmTest {
    @Test fun cyclicErrorCausesDoNotLoop() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertFalse(first.isConnectionException())
    }
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = blank(DeviceState::class.java)
    private val fileState = blank(FileState::class.java)
    private val owners = mutableMapOf<String, DeviceSessionClientManager>()
    private val attempts = mutableMapOf<String, Deferred<Unit>>()
    private val heartbeats = mutableMapOf<String, Job>()
    private val shareOwners = mutableMapOf<String, DeviceSessionClientManager>()
    private val shareHeartbeats = mutableMapOf<String, Job>()
    private val managers = mutableListOf<DeviceSessionClientManager>()
    private val peers = mutableListOf<DeviceSessionPeer>()
    private val device = SocketDevice("peer", "Peer", "/", type = DeviceType.JVM)

    @BeforeTest fun setUp() {
        SettingsUtils.init(createInMemorySettings())
        Dispatchers.setMain(dispatcher)
        setField(state, "mainScope", scope)
        setField(state, "devices", mutableStateListOf<Device>())
        setField(state, "socketDevices", mutableStateListOf<SocketDevice>())
        setField(state, "sessionConnectionManagers", owners)
        setField(state, "sessionConnectionAttempts", attempts)
        setField(state, "heartbeatJobs", heartbeats)
        setField(state, "shareSessionManagers", shareOwners)
        setField(state, "shareSessionHeartbeatJobs", shareHeartbeats)
        setField(state, "shares", mutableStateListOf<Share>())
        val desk = MutableStateFlow<DiskBase>(Local())
        setField(fileState, "_deskType", desk)
        setField(fileState, "deskType", desk)
    }

    @AfterTest fun tearDown() = runTest(dispatcher) {
        scope.cancel()
        runCurrent()
        managers.forEach { it.stop() }
        peers.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test fun reconnectReplacesTheDeviceAndOldFailureCannotClearTheNewConnection() = runTest(dispatcher) {
        val old = manager()
        val replacement = manager()
        invoke(old, "upsertSocketDevice", device, ConnectType.Connect, "old", old)
        invoke(replacement, "upsertSocketDevice", device, ConnectType.Connect, "new", replacement)
        owners[device.id] = replacement
        assertEquals(listOf("new"), state.devices.map { it.token })
        invokeSuspend(state, "clearHttpDeviceConnection", device.id, old, fileState, ConnectType.Fail, true)
        assertSame(replacement, state.socketDevices.single().sessionClient)
        assertEquals(listOf("new"), state.devices.map { it.token })
    }

    @Test fun oldHeartbeatCompletionCannotRemoveTheReplacementRegistration() = runTest(dispatcher) {
        val old = manager()
        val replacement = manager()
        owners[device.id] = old
        launch { invokeSuspend(state, "startSessionHeartbeat", device.id, old, fileState) }
        runCurrent()
        val oldJob = assertNotNull(heartbeats[device.id])
        owners[device.id] = replacement
        launch { invokeSuspend(state, "startSessionHeartbeat", device.id, replacement, fileState) }
        runCurrent()
        assertTrue(oldJob.isCancelled)
        assertTrue(assertNotNull(heartbeats[device.id]).isActive)
        assertNotSame(oldJob, heartbeats[device.id])
    }

    @Test fun existingConnectionAndPendingAttemptAreReused() = runTest(dispatcher) {
        val live = manager()
        setField(live, "active", true)
        state.socketDevices += device.withCopy(connectType = ConnectType.Connect, sessionClient = live)
        invokeSuspend(state, "connectHttpDevice", device, false, false)
        assertTrue(attempts.isEmpty())
        state.socketDevices.clear()
        val pending = backgroundScope.async<Unit> { awaitCancellation() }
        attempts[device.id] = pending
        val first = launch { invokeSuspend(state, "connectHttpDevice", device, false, false) }
        val second = launch { invokeSuspend(state, "connectHttpDevice", device, false, false) }
        runCurrent()
        assertSame(pending, attempts[device.id])
        first.cancelAndJoin()
        second.cancelAndJoin()
        assertTrue(pending.isActive)
    }

    @Test fun oldShareHeartbeatCannotRemoveItsReplacement() = runTest(dispatcher) {
        try {
            val old = manager()
            val replacement = manager()
            setField(old, "active", true)
            setField(replacement, "active", true)
            shareOwners[device.id] = old
            invoke(state, "startShareSessionHeartbeat", device.id, old)
            runCurrent()
            val oldJob = assertNotNull(shareHeartbeats[device.id])
            shareOwners[device.id] = replacement
            invoke(state, "startShareSessionHeartbeat", device.id, replacement)
            runCurrent()
            assertTrue(oldJob.isCancelled)
            assertSame(replacement, shareOwners[device.id])
            assertTrue(assertNotNull(shareHeartbeats[device.id]).isActive)
            assertNotSame(oldJob, shareHeartbeats[device.id])
        } finally {
            scope.cancel()
            runCurrent()
        }
    }

    private fun manager(): DeviceSessionClientManager {
        val channel = object : DeviceSessionByteChannel {
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            override suspend fun flush() = Unit
            override fun close() = Unit
        }
        val plan = DeviceSessionWindowPlan(1024 * 1024, 256 * 1024, 4)
        val peer = DeviceSessionPeer(DeviceSessionTransport(channel, plan, isClient = true), plan).also(peers::add)
        return DeviceSessionClientManager().also {
            managers += it
            setField(it, "deviceState\$delegate", lazyOf(state))
            val clients = createDeviceSessionClients(peer, HttpTransferStatus.default(), FolderSpanArchiveStreamCapabilities.unsupported())
            it.fileRouteClient = clients
            it.pathRouteClient = clients
            it.bookmarkRouteClient = clients
            it.deviceRouteClient = DeviceSessionHeartbeatClient(peer, onFailure = {})
        }
    }
}

// 隔离状态操作，避免构造 DeviceState / FileState 时启动真实网络和界面服务。
private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
private fun <T> blank(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
private fun setField(target: Any, name: String, value: Any) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
}
private fun invoke(target: Any, name: String, vararg args: Any?): Any? =
    target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
private suspend fun invokeSuspend(target: Any, name: String, vararg args: Any?): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        try { invoke(target, name, *args, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
