package com.folderspan.service.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.getSocketDevice
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.*
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.server.raw.RawTlsHttpServer
import com.folderspan.service.webrtc.WebRtcRoomConnectionProbeClient
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webrtc.signaling.toSignalingDevice
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.aakira.napier.Napier
import io.github.aakira.napier.DebugAntilog
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory
import kotlin.system.exitProcess
import kotlin.test.*

/** Two isolated app-state processes. Only device discovery and signaling delivery are fixtures;
 * heartbeat approval, grants, controller negotiation/authentication and the public save queue are real.
 */
class WebRtcShareWorkflowJvmTest {
    @Test
    fun heartbeatApprovedShareUsesWebRtcAndPublicSaveQueue() {
        for (scenario in listOf("resume", "cancel")) {
            val directory = Files.createTempDirectory("webrtc-share-workflow-$scenario").toRealPath()
            val processes = mutableListOf<Process>()
            var primaryFailure: Throwable? = null
            try {
                val classpath = buildList {
                    addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                    generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                        .flatMap { it.urLs.asSequence() }.forEach { add(Path.of(it.toURI()).toString()) }
                }.distinct().joinToString(java.io.File.pathSeparator)
                for (role in listOf("receiver", "sender")) {
                    val home = Files.createDirectories(directory.resolve(role))
                    val arguments = listOf(
                        "-Xmx512m", "-Duser.home=$home",
                        "-Djava.util.prefs.PreferencesFactory=${WorkflowMemoryPreferencesFactory::class.java.name}",
                        "-cp", classpath, WebRtcShareWorkflowEndpoint::class.java.name,
                        directory.toString(), role, scenario,
                    )
                    val argumentsFile = directory.resolve("$role.args")
                    Files.writeString(argumentsFile, arguments.joinToString("\n") { argument ->
                        "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    })
                    processes += ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@$argumentsFile",
                    ).redirectErrorStream(true).redirectOutput(directory.resolve("$role.log").toFile()).start()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100)
                while (processes.any(Process::isAlive) && processes.none { !it.isAlive && it.exitValue() != 0 }) {
                    assertTrue(System.nanoTime() < deadline, "Workflow timed out in $directory")
                    Thread.sleep(50)
                }
                val logs = listOf("sender", "receiver").joinToString("\n") {
                    Files.readString(directory.resolve("$it.log"))
                }
                assertTrue(processes.all { !it.isAlive && it.exitValue() == 0 }, logs)
                assertTrue(Files.exists(directory.resolve("verified")), logs)
                println("WEBRTC_SHARE_WORKFLOW scenario=$scenario bytes=134217728 verified=true")
            } catch (error: Throwable) {
                primaryFailure = error
                val logs = listOf("sender", "receiver").joinToString("\n") { role ->
                    runCatching { Files.readString(directory.resolve("$role.log")).takeLast(24_000) }.getOrDefault("")
                }
                error.addSuppressed(IllegalStateException(logs))
                throw error
            } finally {
                runBlocking {
                    cleanupAll(primaryFailure,
                        { processes.filter(Process::isAlive).forEach(Process::destroy) },
                        *processes.map { process -> suspend {
                            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                check(process.waitFor(3, TimeUnit.SECONDS)) { "Workflow child did not exit" }
                            }
                        } }.toTypedArray(),
                        { check(directory.toFile().deleteRecursively()) { "Could not remove workflow test directory" } },
                    )
                }
            }
        }
    }
}

/** JVM preference isolation is selected before Preferences is initialized in each child. */
class WorkflowMemoryPreferencesFactory : PreferencesFactory {
    private val user = WorkflowPreferences(null, "")
    private val system = WorkflowPreferences(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}

private class WorkflowPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = WorkflowPreferences(this, name)
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
object WebRtcShareWorkflowEndpoint {
    @JvmStatic
    fun main(args: Array<String>) {
        Napier.base(DebugAntilog())
        val result = runCatching { runBlocking { runEndpoint(Path.of(args[0]), args[1], args[2]) } }
        result.exceptionOrNull()?.printStackTrace()
        exitProcess(if (result.isSuccess) 0 else 1)
    }

    private suspend fun runEndpoint(directory: Path, role: String, scenario: String) = withTimeout(80_000L) {
        val other = if (role == "sender") "receiver" else "sender"
        val bootstrap = StandardTestDispatcher()
        val mainDispatcher = newSingleThreadContext("workflow-main")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val tasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        var devices: DeviceState? = null
        var files: FileState? = null
        var relay: WorkflowSignalRelay? = null
        var server: RawTlsHttpServer? = null
        var koinStarted = false
        var primaryFailure: Throwable? = null
        Dispatchers.setMain(bootstrap)
        try {
            val settings = createSettings()
            val firstPort = if (role == "receiver") 19_040 else 19_140
            val port = (firstPort until firstPort + 100 step 4).first { candidate ->
                runCatching { ServerSocket(candidate).use { ServerSocket(candidate + 2).use {} } }.isSuccess
            }
            settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, port)
            SettingsUtils.init(settings)
            FolderSpanDatabase.Schema.create(driver)
            DatabaseReady.markReady()
            val database = shareSaveDatabase(driver)
            database.deviceRoleQueries.insertWithId(2, "Share", "Share", 0)
            val fileShare = FileShareState()
            val notifications = NotificationState()
            val koin = startKoin { modules(module {
                single<FolderSpanDatabase> { database }
                single { DeviceCertificateState(database) }
                single { tasks }
                single { fileShare }
                single { notifications }
                single { MainState() }
                single<ShareHistoryStore> { TempShareHistoryStore(directory.resolve(role).resolve("history").toString()) }
                single { FileRecentState() }
                single { FileFavoriteState() }
                single { NetworkState(database) }
                single { DeviceState() }
                single { FileState() }
            }) }.koin
            koinStarted = true
            val state = koin.get<DeviceState>().also { devices = it }
            // Discovery is supplied by the peer descriptor; no multicast discovery in this fixture.
            val beacon = field(state, "lanBeaconListener")
            beacon.javaClass.getDeclaredMethod("stop").apply { isAccessible = true }.invoke(beacon)
            files = koin.get<FileState>()
            (field(files, "mainScope") as CoroutineScope).coroutineContext.cancelChildren()
            val controller = field(state, "webRtcController") as MultiPeerWebRtcController
            relay = WorkflowSignalRelay(controller, this)
            setField(controller, "signalingClient", relay)
            setField(controller, "currentConfig", WebRtcConfig(
                wssUrl = "ws://127.0.0.1", roomId = "workflow", iceServers = emptyList(), unreliableMode = false,
            ))
            setField(controller, "currentLocalDeviceId", getSocketDevice().id)
            Dispatchers.setMain(mainDispatcher)
            bootstrap.scheduler.runCurrent()
            val self = getSocketDevice().withCopy(host = "127.0.0.1")
            if (role == "receiver") server = RawTlsHttpServer().also {
                it.start(port)
                (field(it, "beaconAdvertiser") as DeviceLanBeaconAdvertiser).stop()
            }
            publish(directory.resolve("$role.device"), Json.encodeToString(self))
            publish(directory.resolve("$role.signal"), relay.port.toString())
            waitUntil { Files.exists(directory.resolve("$other.signal")) }
            val remote = Json.decodeFromString<SocketDevice>(Files.readString(directory.resolve("$other.device")))
            relay.remotePort = Files.readString(directory.resolve("$other.signal")).toInt()
            withContext(Dispatchers.Main) {
                deliver(controller, SignalingMessage(type = "joined", peers = listOf(remote.toSignalingDevice())))
            }
            val payload = directory.resolve("sender/payload.bin")
            val saveDirectory = Files.createDirectories(directory.resolve("receiver/saved"))
            if (role == "sender") {
                val chunk = ByteArray(256 * 1024) { (it * 31 + 17).toByte() }
                Files.newOutputStream(payload).use { output -> repeat(512) { output.write(chunk) } }
                val info = FileUtils.getFile(FileAccessPermission.Allowed, payload.toString()).getOrThrow()
                withContext(Dispatchers.Main) { fileShare.shareToDevices[remote.id] = true to listOf(info) }
                waitUntil { Files.exists(directory.resolve("receiver.ready")) }
                withContext(Dispatchers.Main) { state.share(remote) }
                waitUntil {
                    check(fileShare.sendFile[remote.id] != FileShareStatus.ERROR) {
                        "Heartbeat failed: ${fileShare.sendFileMessage[remote.id]}"
                    }
                    controller.isShareSession(remote.id)
                }
                waitUntil { Files.exists(directory.resolve("receiver.connected")) }
                assertTrue(state.allowDeviceShareConnection.isEmpty(), "The heartbeat-issued grant must be consumed")
                assertEquals(0, state.deviceSessionBootstrapAuthorizationRegistry.pendingCount())
                assertTrue(state.connectionRequest.isEmpty(), "Sharing must not request a second approval")
                publish(directory.resolve("sender.verified"), "ok")
                waitUntil { Files.exists(directory.resolve("verified")) }
                withContext(Dispatchers.Main) { state.cancelShare(remote.id) }
            } else {
                database.deviceQueries.insert(remote.id, remote.name, remote.host, remote.port.toLong(), remote.type)
                database.deviceReceiveShareQueries.insert(remote.id, DeviceConnectType.AUTO_CONNECT, saveDirectory.toString())
                publish(directory.resolve("receiver.ready"), "ok")
                var sawTask = false
                waitUntil {
                    if (tasks.tasks.isNotEmpty()) sawTask = true
                    check(!sawTask || tasks.tasks.isNotEmpty()) { "Copy finished before byte progress was observed" }
                    check(tasks.tasks.none { it.status == StatusEnum.FAILURE }) { "Save task failed: ${tasks.tasks.map { it.result }}" }
                    check(tasks.tasks.none { it.status == StatusEnum.SUCCESS }) { "Copy finished before a byte-progress pause could be observed" }
                    tasks.tasks.any { it.status == StatusEnum.LOADING && transferredBytes(it) in 1 until Files.size(payload) }
                }
                val task = tasks.tasks.single { it.status == StatusEnum.LOADING }
                assertEquals(saveDirectory.resolve("payload.bin").toString(), task.transientResultPath())
                assertEquals(Files.size(payload), byteProgressParts(task)?.second)
                assertTrue(transferredBytes(task) in 1 until Files.size(payload), task.transientResultMessage())
                println("WORKFLOW positive byte progress: ${task.transientResultMessage()}")
                assertTrue(controller.isShareSession(remote.id))
                val client = assertNotNull(controller.deviceSessionClients(remote.id))
                tasks.requestPause(task)
                assertEquals(StatusEnum.PAUSE, tasks.getTask(task.key)?.status)
                assertEquals(listOf("/payload.bin"), client.listPath(ListRequest("/")).getOrThrow().map { it.path })
                publish(directory.resolve("receiver.connected"), "ok")
                val output = saveDirectory.resolve("payload.bin")
                withTimeout(2_000) {
                    var previous = -1L
                    var stableSamples = 0
                    while (stableSamples < 3) {
                        delay(20)
                        val current = Files.size(output)
                        stableSamples = if (current == previous) stableSamples + 1 else 0
                        previous = current
                    }
                }
                val pausedSize = Files.size(output)
                assertTrue(pausedSize in 1 until Files.size(payload))
                val pausedHash = sha256(output)
                val pausedProgress = transferredBytes(assertNotNull(tasks.getTask(task.key)))
                delay(300)
                assertEquals(pausedSize, Files.size(output), "A paused task must stop writing")
                assertContentEquals(pausedHash, sha256(output))
                assertEquals(pausedProgress, transferredBytes(assertNotNull(tasks.getTask(task.key))))
                client.listPath(ListRequest("/")).getOrThrow()
                if (scenario == "cancel") {
                    tasks.requestCancel(assertNotNull(tasks.getTask(task.key)), "workflow-cancel")
                    waitUntil { tasks.getTask(task.key)?.status == StatusEnum.FAILURE }
                    assertEquals("workflow-cancel", assertNotNull(tasks.getTask(task.key)).result["cancel_reason"])
                    assertEquals(pausedSize, Files.size(output))
                    assertContentEquals(pausedHash, sha256(output))
                } else {
                    tasks.requestResume(assertNotNull(tasks.getTask(task.key)))
                    waitUntil { tasks.getTask(task.key) == null || tasks.getTask(task.key)?.status == StatusEnum.FAILURE }
                    assertNull(tasks.getTask(task.key), "Successful public save tasks must be removed")
                    assertContentEquals(sha256(payload), sha256(output))
                }
                waitUntil { !state.hasActiveShareConnection(remote.id) }
                assertTrue(notifications.notifications.none { it.type == NotificationType.Error }, "Cancellation must not post a share failure")
                waitUntil { Files.exists(directory.resolve("sender.verified")) }
                publish(directory.resolve("verified"), "ok")
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                cleanupAll(primaryFailure,
                    { withTimeout(5_000) { relay?.close() } },
                    { withTimeout(5_000) { server?.stop() } },
                    { devices?.let { (field(it, "webRtcController") as MultiPeerWebRtcController).disconnect() } },
                    { devices?.let { (field(it, "browserWebRtcController") as MultiPeerWebRtcController).disconnect() } },
                    { devices?.let { withTimeout(5_000) { (field(it, "mainScope") as CoroutineScope).coroutineContext.job.cancelAndJoin() } } },
                    { devices?.let { (field(it, "client") as io.ktor.client.HttpClient).close() } },
                    { files?.let { withTimeout(5_000) { (field(it, "mainScope") as CoroutineScope).coroutineContext.job.cancelAndJoin() } } },
                    { withTimeout(5_000) { (field(field(tasks, "taskScheduler"), "taskScope") as CoroutineScope).coroutineContext.job.cancelAndJoin() } },
                    { if (koinStarted) stopKoin() },
                    { driver.close() },
                    { Dispatchers.resetMain() },
                    { mainDispatcher.close() },
                )
            }
        }
    }
}

private class WorkflowSignalRelay(private val controller: MultiPeerWebRtcController, scope: CoroutineScope) : WebRtcRoomConnectionProbeClient {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    var remotePort = 0
    private val sendMutex = Mutex()
    private val job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val socket = try { server.accept() } catch (error: java.net.SocketException) {
                if (isActive) throw error else break
            }
            val signal = socket.use {
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                require(length in 1..65_536)
                Json.decodeFromString<SignalingMessage>(input.readNBytes(length).decodeToString())
            }
            println("SIGNAL receive ${signal.type}")
            withContext(Dispatchers.Main) { deliver(controller, signal) }
        }
    }
    override val messages = emptyFlow<SignalingMessage>()
    override val openedEvents = emptyFlow<Unit>()
    override val closedEvents = emptyFlow<Unit>()
    override val failureEvents = emptyFlow<Throwable>()
    override fun connect(scope: CoroutineScope) = Unit
    override suspend fun send(message: SignalingMessage) = sendMutex.withLock {
        println("SIGNAL send ${message.type}")
        withContext(Dispatchers.IO) {
            Socket(InetAddress.getLoopbackAddress(), remotePort).use { socket ->
                val bytes = Json.encodeToString(message).encodeToByteArray()
                DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
                Unit
            }
        }
    }
    override suspend fun close() { job.cancel(); server.close(); job.join() }
}

private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
private fun setField(target: Any, name: String, value: Any) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
private fun deliver(controller: MultiPeerWebRtcController, signal: SignalingMessage) {
    controller.javaClass.getDeclaredMethod("handleSignal", SignalingMessage::class.java).apply { isAccessible = true }.invoke(controller, signal)
}
private suspend fun waitUntil(condition: () -> Boolean) { while (!condition()) delay(5L) }
private fun publish(path: Path, text: String) {
    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temporary, text)
    Files.move(temporary, path)
}
private fun sha256(path: Path): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) { val size = input.read(bytes); if (size < 0) break; digest.update(bytes, 0, size) }
    }
    return digest.digest()
}

private val byteProgressPattern = Regex("""\((\d+)/(\d+)\)""")
private fun byteProgressParts(task: Task): Pair<Long, Long>? {
    val parts = byteProgressPattern.find(task.transientResultMessage().orEmpty())?.groupValues ?: return null
    return (parts[1].toLongOrNull() ?: return null) to (parts[2].toLongOrNull() ?: return null)
}
private fun transferredBytes(task: Task): Long = byteProgressParts(task)?.first ?: 0L

private suspend fun cleanupAll(primaryFailure: Throwable?, vararg actions: suspend () -> Unit) {
    var failure = primaryFailure
    for (action in actions) {
        try { action() } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
    }
    if (primaryFailure == null) failure?.let { throw it }
}
