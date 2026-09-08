package com.folderspan.service.session

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.getSocketDevice
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.*
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.db.*
import com.folderspan.routes.RawHttpApiDispatcher
import com.folderspan.routes.RawHttpBody
import com.folderspan.routes.RawHttpRequest
import com.folderspan.service.data.*
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.withRealWebRtcDataChannelPair
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.*

/** Real approval dispatcher, scoped authentication, SCTP/DTLS and Device-to-Local task engine.
 * UI approval and signaling delivery are explicit fixture boundaries; no app screen is driven.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcShareSaveJvmTest {
    @Test
    fun approvedShareSavesThroughTaskHandlerWithProgressAndPause() = runBlocking {
        verifySave(cancel = false)
    }

    @Test
    fun cancellingShareSaveKeepsCancellationAndLeavesSessionUsable() = runBlocking {
        verifySave(cancel = true)
    }

    private suspend fun verifySave(cancel: Boolean) {
        ShareSaveFixture().use { fixture ->
            fixture.start()
            val source = getSocketDevice()
            val receiver = source.withCopy(id = "share-save-test-receiver")
            val nonce = "share-save-test-nonce"
            // Deliver the receiver's approved UI decision through the production heartbeat endpoint.
            fixture.devices.publishShareConnectionResult(source.id, FileShareStatus.COMPLETED)
            val heartbeat = fixture.dispatcher.dispatch(RawHttpRequest(
                method = "POST", path = "/api/share/heartbeat",
                headers = mapOf("content-type" to "application/x-protobuf"),
                body = ProtoBufCodec.encode(ShareRequestPollRequest(source, checkOnly = false, connectNonce = nonce)),
            ))
            assertEquals(200, heartbeat.statusCode)
            assertEquals(FileShareStatus.COMPLETED, ProtoBufCodec.decode<ShareRequestPollResponse>(
                (heartbeat.body as RawHttpBody.Bytes).bytes,
            ).status)
            // This is the sender's response to that approval; the grant still crosses the real validator.
            fixture.devices.allowDeviceShareConnection[receiver.id] = DeviceShareConnectionGrant(
                deviceId = receiver.id,
                tlsFingerprintSha256 = receiver.tlsFingerprintSha256,
                nonce = nonce,
                expiresAtMillis = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000L,
                allowedPaths = listOf(DeviceSharePathGrant(fixture.input.toString(), false)),
            )
            val grant = assertNotNull(fixture.devices.consumeAllowedDeviceShareConnection(receiver, nonce))
            assertNull(fixture.devices.consumeAllowedDeviceShareConnection(receiver, nonce))
            val attempt = "share-save-test-attempt"
            val authorization = fixture.devices.deviceSessionBootstrapAuthorizationRegistry.issue(
                initiatorDeviceId = receiver.id, targetDeviceId = source.id,
                connectionAttemptId = attempt, roleId = 2L, sharePathScope = grant.pathScope,
            )
            withRealWebRtcDataChannelPair { outgoing, incoming, _, _ ->
                var authenticated = 0
                val server = launch {
                    checkNotNull(createWebRtcDeviceSessionServerLauncher()).run(
                        WebRtcDeviceSessionByteChannel(incoming, this), receiver.id, attempt, this,
                    ) { authenticated++ }
                }
                var runtime: DeviceSessionClientRuntime? = null
                try {
                    val client = DeviceSessionClientRuntimeLauncher.start(
                        channel = WebRtcDeviceSessionByteChannel(outgoing, this), scope = this,
                        authentication = DeviceSessionClientAuthenticationContext(source.id) { peer ->
                            peer.rpcValue<DeviceConnectRequest, DeviceSessionConnectResponse>(
                                DEVICE_SESSION_RPC_CONNECT,
                                DeviceConnectRequest(device = receiver, authorizationMode = DeviceConnectAuthorizationMode.STANDARD, bootstrapAuthorization = authorization),
                            )
                        },
                    ).also { runtime = it }
                    assertEquals(DeviceConnectType.APPROVED, client.connectResponse.connection.connectType)
                    assertEquals(1, authenticated)
                    assertEquals(0, fixture.devices.deviceSessionBootstrapAuthorizationRegistry.pendingCount())
                    assertTrue(fixture.devices.connectionRequest.isEmpty(), "Scoped authentication must not ask for a second approval")
                    assertTrue(fixture.certificates.getDeviceSharePathScope(client.connectResponse.connection.token) != null)
                    val roots = client.clients.listPath(ListRequest("/")).getOrThrow()
                    assertEquals(listOf("/payload.bin"), roots.map { it.path })
                    assertTrue(client.clients.readBytes(fixture.outside.toString(), 0L, 1L).isFailure)
                    val firstWritten = CompletableDeferred<Unit>()
                    val continueRead = CompletableDeferred<Unit>()
                    val secondOffered = CompletableDeferred<Unit>()
                    var chunkCount = 0
                    var writtenBytes = 0
                    val observedFileClient = object : DeviceFileClient by client.clients {
                        override suspend fun readStream(path: String, startOffset: Long, endOffset: Long, onChunk: suspend (ByteArray) -> Unit): Result<Boolean> =
                            client.clients.readStream(path, startOffset, endOffset) { bytes ->
                                if (chunkCount == 0) {
                                    delay(550L) // Make the production 500 ms progress publisher observable.
                                    onChunk(bytes)
                                    writtenBytes += bytes.size
                                    chunkCount++
                                    firstWritten.complete(Unit)
                                    continueRead.await()
                                } else {
                                    secondOffered.complete(Unit)
                                    onChunk(bytes)
                                    writtenBytes += bytes.size
                                    chunkCount++
                                }
                            }
                    }
                    val share = source.toShare(ReadOnlyShareSessionAdapter(
                        devicePathClient = client.clients, deviceFileClient = observedFileClient,
                        active = { client.sessionJob.isActive }, disconnectSession = { outgoing.close(); true },
                    ))
                    fixture.devices.shares += share
                    val task = Task(taskType = TaskType.Copy, key = if (cancel) 7002L else 7001L,
                        status = StatusEnum.LOADING, protocol = FileProtocol.Share, protocolId = share.id)
                    val sourceFile = roots.single().withCopy(protocol = FileProtocol.Share, protocolId = share.id)
                    val destination = sourceFile.withCopy(protocol = FileProtocol.Local, protocolId = "", path = fixture.output.toString())
                    val finished = CompletableDeferred<Result<Boolean>>()
                    fixture.tasks.addOrUpdate(task)
                    fixture.tasks.registerTaskHandler(task) {
                        val result = share.copyTo(task, sourceFile, destination)
                        finished.complete(result)
                        result.getOrThrow()
                        fixture.tasks.updateStatus(task, StatusEnum.SUCCESS)
                    }
                    firstWritten.await()
                    val progressing = assertNotNull(fixture.tasks.getTask(task.key))
                    assertEquals(StatusEnum.LOADING, progressing.status)
                    assertTrue(writtenBytes in 1 until fixture.bytes.size)
                    // A single file's counter remains 0/1; byte progress is a transient task result.
                    assertEquals(fixture.output.toString(), progressing.transientResultPath())
                    assertTrue(progressing.transientResultMessage().orEmpty().contains(writtenBytes.toString()))
                    assertTrue(Files.exists(fixture.output))
                    fixture.tasks.requestPause(progressing)
                    assertEquals(StatusEnum.PAUSE, fixture.tasks.getTask(task.key)?.status)
                    val written = Files.readAllBytes(fixture.output)
                    val pausedAtBytes = writtenBytes
                    continueRead.complete(Unit)
                    secondOffered.await()
                    delay(100L)
                    assertEquals(1, chunkCount, "Production pause must block the second write after the fixture gate opens")
                    assertEquals(pausedAtBytes, writtenBytes)
                    assertContentEquals(written, Files.readAllBytes(fixture.output))
                    assertEquals(roots, client.clients.listPath(ListRequest("/")).getOrThrow())
                    if (cancel) {
                        fixture.tasks.requestCancel(assertNotNull(fixture.tasks.getTask(task.key)), "share-test-cancel")
                        assertIs<CancellationException>(finished.await().exceptionOrNull())
                        withTimeout(2_000L) {
                            while (fixture.tasks.getTask(task.key)?.status == StatusEnum.PAUSE) delay(10L)
                        }
                        assertEquals(StatusEnum.FAILURE, assertNotNull(fixture.tasks.getTask(task.key)).status)
                        assertEquals(pausedAtBytes, writtenBytes)
                        assertFalse(Files.readAllBytes(fixture.output).contentEquals(fixture.bytes))
                        assertEquals(roots, client.clients.listPath(ListRequest("/")).getOrThrow())
                        assertTrue(client.sessionJob.isActive)
                    } else {
                        fixture.tasks.requestResume(assertNotNull(fixture.tasks.getTask(task.key)))
                        assertTrue(finished.await().getOrThrow())
                        withTimeout(2_000L) {
                            while (fixture.tasks.getTask(task.key)?.status != StatusEnum.SUCCESS) delay(10L)
                        }
                        assertContentEquals(fixture.bytes, Files.readAllBytes(fixture.output))
                    }
                    share.disconnect()
                    withTimeout(2_000L) { client.sessionJob.join() }
                    fixture.tasks.delete(task.key)
                } finally {
                    withContext(NonCancellable) {
                        runtime?.close()
                        server.cancelAndJoin()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ShareSaveFixture : AutoCloseable {
    val directory = Files.createTempDirectory("webrtc-share-save").toRealPath()
    val bytes = ByteArray(1024 * 1024) { (it * 31 + 17).toByte() }
    val input = Files.write(directory.resolve("payload.bin"), bytes)
    val outside = Files.write(directory.resolve("private.bin"), byteArrayOf(9))
    val output = directory.resolve("saved.bin")
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private lateinit var database: FolderSpanDatabase
    val tasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
    lateinit var certificates: DeviceCertificateState
    lateinit var devices: DeviceState
    val dispatcher = RawHttpApiDispatcher(MapSettings())
    // Restore every settings holder, including a previously uninitialized holder.
    private val settingsSnapshot = listOf(
        SettingsUtils, SettingsUtils.fileShare, SettingsUtils.mcp,
        SettingsUtils.easyFileShare, DataEncryptionKey,
    ).map { holder ->
        val field = holder.javaClass.getDeclaredField("settings").apply { isAccessible = true }
        field to field.get(null)
    }
    private var mainInstalled = false
    private var koinStarted = false
    fun start() {
        SettingsUtils.init(MapSettings())
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        database = shareSaveDatabase(driver)
        database.deviceRoleQueries.insertWithId(2L, "Share test", "Share test", 0L)
        certificates = DeviceCertificateState(database)
        Dispatchers.setMain(StandardTestDispatcher())
        mainInstalled = true
        val koin = startKoin { modules(module {
            single { database }
            single { certificates }
            single { tasks }
            single { FileShareState() }
            single { DeviceState() }
        }) }.koin
        koinStarted = true
        devices = koin.get()
        // Prevent discovery/UI background jobs from touching the user's app. Business methods remain real.
        (devices.javaClass.getDeclaredField("mainScope").apply { isAccessible = true }.get(devices) as CoroutineScope).cancel()
        Dispatchers.setMain(Dispatchers.Unconfined)
    }
    override fun close() {
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        cleanup {
            val scheduler = tasks.javaClass.getDeclaredField("taskScheduler").apply { isAccessible = true }.get(tasks)
            val taskScope = scheduler.javaClass.getDeclaredField("taskScope").apply { isAccessible = true }.get(scheduler) as CoroutineScope
            runBlocking { taskScope.coroutineContext.job.cancelAndJoin() }
        }
        if (::devices.isInitialized) {
            cleanup { (devices.javaClass.getDeclaredField("mainScope").apply { isAccessible = true }.get(devices) as CoroutineScope).cancel() }
            cleanup { (devices.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(devices) as HttpClient).close() }
        }
        if (koinStarted) cleanup { stopKoin() }
        if (mainInstalled) cleanup { Dispatchers.resetMain() }
        cleanup { driver.close() }
        cleanup { directory.toFile().deleteRecursively() }
        settingsSnapshot.forEach { (field, previous) -> cleanup { field.set(null, previous) } }
        failure?.let { throw it }
    }
}

internal fun shareSaveDatabase(driver: JdbcSqliteDriver) = FolderSpanDatabase(
    driver = driver,
    DeviceAdapter = Device.Adapter(shareSaveEnumAdapter<DeviceType>()),
    DeviceConnectAdapter = DeviceConnect.Adapter(shareSaveEnumAdapter<DeviceConnectType>(), shareSaveEnumAdapter<DeviceCategory>()),
    FileBookmarkAdapter = FileBookmark.Adapter(shareSaveEnumAdapter<DrawerBookmarkType>(), shareSaveEnumAdapter<FileProtocol>()),
    FileFavoriteAdapter = FileFavorite.Adapter(shareSaveEnumAdapter<FileProtocol>()),
    FileRecentAdapter = FileRecent.Adapter(shareSaveEnumAdapter<FileProtocol>()),
    FileFilterAdapter = FileFilter.Adapter(shareSaveEnumAdapter<FileFilterType>(), shareSaveStringsAdapter),
    DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(shareSaveEnumAdapter<DeviceConnectType>()),
    FilePathPreferenceAdapter = FilePathPreference.Adapter(
        shareSaveEnumAdapter<FileProtocol>(), shareSaveEnumAdapter<FileFilterSort>(), shareSaveStringsAdapter,
    ),
)

private inline fun <reified T : Enum<T>> shareSaveEnumAdapter(): ColumnAdapter<T, String> =
    object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T): String = value.name
    }

private val shareSaveStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> = databaseValue.split(',').filter(String::isNotEmpty)
    override fun encode(value: List<String>): String = value.joinToString(",")
}
