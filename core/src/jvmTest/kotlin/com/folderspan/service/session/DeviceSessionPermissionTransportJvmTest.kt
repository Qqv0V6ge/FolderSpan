package com.folderspan.service.session

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.createSettings
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.exception.AuthorityException
import com.folderspan.routes.RawHttpApiDispatcher
import com.folderspan.routes.RawHttpRequest
import com.folderspan.service.data.CreateFileRequest
import com.folderspan.service.data.GetFileByPathRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.ReadFileLinesRequest
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.file.DeviceFileService
import com.folderspan.service.path.DevicePathEntries
import com.folderspan.service.path.DevicePathService
import com.folderspan.service.webrtc.WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
import com.folderspan.service.webrtc.WebRtcDeviceSessionByteChannel
import com.folderspan.service.webrtc.WebRtcDeviceSessionCarrierLimits
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSessionPermissionTransportJvmTest {
    @Test
    fun publicHttpDispatcherRejectsRetiredFileAndPathEndpoints() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(settings = MapSettings())
        val requests = listOf(
            "/api/paths/list" to ProtoBufCodec.encode(ListRequest("/")),
            "/api/files/get-file-by-path" to ProtoBufCodec.encode(GetFileByPathRequest("/ordinary.txt")),
            "/api/files/create-file" to ProtoBufCodec.encode(CreateFileRequest(listOf("/new.txt"))),
        )
        for ((path, payload) in requests) {
            val response = dispatcher.dispatch(
                RawHttpRequest(
                    method = "POST",
                    path = path,
                    headers = mapOf("content-type" to "application/x-protobuf", "authorization" to "Bearer token"),
                    body = payload,
                ),
            )
            assertEquals(404, response.statusCode, path)
        }
    }

    @Test
    fun continuousSessionPreservesPermissionsAndBatchOrder() = runBlocking {
        verifyPermissionMatrix(PermissionTestCarrier.ContinuousBytes)
    }

    @Test
    fun webRtcByteAdapterPreservesPermissionsAndBatchOrder() = runBlocking {
        verifyPermissionMatrix(PermissionTestCarrier.FakeWebRtcDataChannel)
    }

    private suspend fun verifyPermissionMatrix(carrier: PermissionTestCarrier) {
        PermissionFixture().use { fixture ->
            assertFalse(
                fixture.certificates.checkPermission(
                    FileAccessPermission.Denied,
                    fixture.roleToken,
                    fixture.protectedPath,
                    "read",
                ),
                "敏感路径用例必须已有角色读取许可，才能验证敏感策略优先于角色",
            )
            withPermissionSession(carrier, fixture.handler(fixture.roleToken)) { client ->
                assertTrue(client.list(fixture.allowed.absolutePathString()).isSuccess)
                assertEquals("allowed.txt", client.file(fixture.allowedFile.absolutePathString()).getOrThrow().name)
                assertEquals(listOf("allowed"), client.lines(fixture.allowedFile.absolutePathString()).getOrThrow())
                for (path in listOf(fixture.outside, fixture.link.resolve("nested"))) {
                    assertIs<AuthorityException>(client.list(path.absolutePathString()).exceptionOrNull())
                }
                for (path in listOf(fixture.protectedPath, fixture.link.resolve("nested/secret.txt").absolutePathString())) {
                    assertIs<AuthorityException>(client.file(path).exceptionOrNull())
                    assertIs<AuthorityException>(client.lines(path).exceptionOrNull())
                }
                assertIs<AuthorityException>(client.list(fixture.protectedPath).exceptionOrNull())

                val first = fixture.allowed.resolve("first.txt")
                val denied = fixture.outside.resolve("denied.txt")
                val second = fixture.allowed.resolve("second.txt")
                val escaped = fixture.link.resolve("nested/escaped.txt")
                val batch = client.rpcResult<CreateFileRequest, List<SerializableResult>>(
                    DEVICE_SESSION_RPC_CREATE_FILES,
                    CreateFileRequest(listOf(first, denied, second, escaped).map { it.absolutePathString() }),
                ).toBooleanBatchResult().getOrThrow()
                assertEquals(4, batch.size)
                assertTrue(batch[0].getOrThrow())
                assertIs<AuthorityException>(batch[1].exceptionOrNull())
                assertTrue(batch[2].getOrThrow())
                assertIs<AuthorityException>(batch[3].exceptionOrNull())
                assertTrue(Files.exists(first))
                assertTrue(Files.exists(second))
                assertFalse(Files.exists(denied))
                assertFalse(Files.exists(fixture.outside.resolve("nested/escaped.txt")))
                assertEquals("secret", Files.readString(fixture.outside.resolve("nested/secret.txt")))
            }
            withPermissionSession(carrier, fixture.handler(fixture.deniedToken)) { client ->
                assertIs<AuthorityException>(client.list(fixture.allowed.absolutePathString()).exceptionOrNull())
                assertIs<AuthorityException>(client.file(fixture.allowedFile.absolutePathString()).exceptionOrNull())
            }
            withPermissionSession(carrier, fixture.handler(fixture.shareToken)) { client ->
                assertTrue(client.list("/allowed").isSuccess)
                assertIs<AuthorityException>(client.list("/allowed/escape/nested").exceptionOrNull())
                assertIs<AuthorityException>(client.list(fixture.parent.absolutePathString()).exceptionOrNull())
                assertIs<AuthorityException>(client.file(fixture.protectedPath).exceptionOrNull())
                assertIs<AuthorityException>(client.file(fixture.link.resolve("nested/secret.txt").absolutePathString()).exceptionOrNull())
                assertIs<AuthorityException>(client.lines(fixture.protectedPath).exceptionOrNull())
                assertIs<AuthorityException>(client.lines(fixture.link.resolve("nested/secret.txt").absolutePathString()).exceptionOrNull())
                val deniedWrite = fixture.allowed.resolve("share-write-denied.txt")
                val batch = client.rpcResult<CreateFileRequest, List<SerializableResult>>(
                    DEVICE_SESSION_RPC_CREATE_FILES,
                    CreateFileRequest(listOf(deniedWrite.absolutePathString())),
                ).toBooleanBatchResult().getOrThrow()
                assertIs<AuthorityException>(batch.single().exceptionOrNull())
                assertFalse(Files.exists(deniedWrite))
            }
        }
    }
}

private suspend fun DeviceSessionPeer.list(path: String): Result<DevicePathEntries> =
    rpcResult(DEVICE_SESSION_RPC_LIST_PATH, ListRequest(path))

private suspend fun DeviceSessionPeer.file(path: String): Result<FileSimpleInfo> =
    rpcResult(DEVICE_SESSION_RPC_GET_FILE_BY_PATH, GetFileByPathRequest(path))

private suspend fun DeviceSessionPeer.lines(path: String): Result<List<String>> =
    rpcResult(DEVICE_SESSION_RPC_READ_FILE_LINES, ReadFileLinesRequest(path))

private class PermissionFixture : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database: FolderSpanDatabase
    val certificates: DeviceCertificateState
    private val paths: DevicePathService
    private val files: DeviceFileService
    val roleToken = "permission-role-token"
    val deniedToken = "permission-denied-token"
    val shareToken = "permission-share-token"
    val parent = Files.createTempDirectory("session-permission-transport").toRealPath()
    val allowed = Files.createDirectory(parent.resolve("allowed"))
    val allowedFile = Files.writeString(allowed.resolve("allowed.txt"), "allowed")
    val outside = Files.createDirectories(parent.resolve("outside/nested")).parent
    val link = allowed.resolve("escape")
    val protectedPath = resolveDesktopApplicationDataDirectory().resolve("permission-transport-secret.db").toString()

    init {
        SettingsUtils.init(createSettings())
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        database = permissionDatabase(driver)
        certificates = DeviceCertificateState(database)
        paths = DevicePathService(certificates) { "permission-test-device" }
        files = DeviceFileService(certificates) { "permission-test-device" }
        Files.writeString(outside.resolve("nested/secret.txt"), "secret")
        Files.createSymbolicLink(link, outside)
        database.deviceRoleQueries.insertWithId(41L, "权限回归角色", "权限回归", 0L)
        for ((index, path) in listOf(allowed.absolutePathString(), protectedPath).withIndex()) {
            val permissionId = 100L + index
            database.devicePermissionQueries.insertWithId(
                permissionId, path, true, true, true, true, true, 0L, "权限回归路径",
            )
            database.deviceRoleDevicePermissionQueries.insert(41L, permissionId)
        }
        certificates.setTokenPermission(roleToken, 41L)
        certificates.setTokenPermission(deniedToken, 99L)
        certificates.setTokenPermission(shareToken, 41L)
        certificates.setDeviceSharePathScope(
            shareToken,
            DeviceSharePathScope(
                listOf(
                    DeviceSharePathGrant(allowed.absolutePathString(), isDirectory = true),
                    DeviceSharePathGrant(protectedPath, isDirectory = false),
                ),
            ),
        )
    }

    // 测试适配层固定已授权 token，只连接真实服务与公共 Session 控制端点。
    // 不覆盖 DeviceSessionServer 的建连审批、Share RPC 白名单或 TLS/ICE；
    // Share 文件/写入调用刻意验证底层服务边界，即使上层已有更严格白名单也不能放行。
    fun handler(token: String) = DeviceSessionRequestHandler { request ->
        val value = when (request.method) {
            DEVICE_SESSION_RPC_LIST_PATH -> paths.list(token, ProtoBufCodec.decode(request.payload)).getOrThrow().let {
                SerializableResult.success(it)
            }
            DEVICE_SESSION_RPC_GET_FILE_BY_PATH -> files.getFileByPath(token, ProtoBufCodec.decode(request.payload)).getOrThrow().let {
                SerializableResult.success(it)
            }
            DEVICE_SESSION_RPC_READ_FILE_LINES -> files.readFileLines(token, ProtoBufCodec.decode(request.payload)).getOrThrow().let {
                SerializableResult.success(it)
            }
            DEVICE_SESSION_RPC_CREATE_FILES -> files.createFiles(token, ProtoBufCodec.decode(request.payload))
                .toSerializableBooleanBatchResult().getOrThrow().let { SerializableResult.success(it) }
            else -> error("未配置测试方法：${request.method}")
        }
        DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, ProtoBufCodec.encode(value))
    }

    override fun close() {
        Files.deleteIfExists(link)
        parent.toFile().deleteRecursively()
        driver.close()
    }
}

private enum class PermissionTestCarrier { ContinuousBytes, FakeWebRtcDataChannel }

private suspend fun withPermissionSession(
    carrier: PermissionTestCarrier,
    handler: DeviceSessionRequestHandler,
    block: suspend (DeviceSessionPeer) -> Unit,
) = withTimeout(15_000L) {
    coroutineScope {
        val (clientChannel, serverChannel) = permissionChannels(carrier, this)
        val plan = DeviceSessionWindowPlan(2 * 1024 * 1024, DEVICE_SESSION_MAX_PAYLOAD_BYTES, 2)
        val client = DeviceSessionPeer(DeviceSessionTransport(clientChannel, plan, plan, true), plan)
        val server = DeviceSessionPeer(
            DeviceSessionTransport(serverChannel, plan, plan, false), plan, DeviceSessionEndpointRole.Server,
        )
        server.setRequestHandler(handler)
        val clientJob = client.start(this)
        val serverJob = server.start(this)
        try {
            block(client)
        } finally {
            client.close()
            server.close()
            clientJob.cancelAndJoin()
            serverJob.cancelAndJoin()
        }
    }
}

private fun permissionChannels(
    carrier: PermissionTestCarrier,
    scope: CoroutineScope,
): Pair<DeviceSessionByteChannel, DeviceSessionByteChannel> {
    if (carrier == PermissionTestCarrier.ContinuousBytes) {
        val toServer = Channel<ByteArray>(64)
        val toClient = Channel<ByteArray>(64)
        return FragmentedPermissionChannel(toClient, toServer) to FragmentedPermissionChannel(toServer, toClient)
    }
    val client = PermissionDataChannel()
    val server = PermissionDataChannel()
    client.peer = server
    server.peer = client
    val limits = WebRtcDeviceSessionCarrierLimits(messageBytes = 128)
    return WebRtcDeviceSessionByteChannel(client, scope, limits) to WebRtcDeviceSessionByteChannel(server, scope, limits)
}

private class FragmentedPermissionChannel(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : DeviceSessionByteChannel {
    private var current = byteArrayOf()
    private var position = 0
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position == current.size) {
            current = incoming.receiveCatching().getOrNull() ?: return -1
            position = 0
        }
        val count = minOf(3, length, current.size - position)
        current.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        outgoing.send(buffer.copyOfRange(offset, offset + length))
    }
    override suspend fun flush() = Unit
    override fun close() {
        outgoing.close()
    }
}

private class PermissionDataChannel : WebRtcDataChannel {
    private val incoming = Channel<ByteArray>(512)
    private val closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var peer: PermissionDataChannel? = null
    private var open = true
    override val label = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
    override val state get() = if (open) WebRtcDataChannelState.Open else WebRtcDataChannelState.Closed
    override val bufferedAmount = 0L
    override val onOpen: Flow<Unit> = emptyFlow()
    override val onClose = closeEvents.asSharedFlow()
    override val onMessage = incoming.receiveAsFlow()
    override fun send(data: ByteArray): Boolean {
        val target = peer?.takeIf { it.open } ?: return false
        return open && target.incoming.trySend(data.copyOf()).isSuccess
    }
    override fun close() {
        if (!open) return
        open = false
        incoming.close()
        closeEvents.tryEmit(Unit)
        peer?.close()
    }
}

private fun permissionDatabase(driver: JdbcSqliteDriver) = FolderSpanDatabase(
    driver = driver,
    DeviceAdapter = Device.Adapter(enumPermissionAdapter<DeviceType>()),
    DeviceConnectAdapter = DeviceConnect.Adapter(enumPermissionAdapter<DeviceConnectType>(), enumPermissionAdapter<DeviceCategory>()),
    FileBookmarkAdapter = FileBookmark.Adapter(enumPermissionAdapter<DrawerBookmarkType>(), enumPermissionAdapter<FileProtocol>()),
    FileFavoriteAdapter = FileFavorite.Adapter(enumPermissionAdapter<FileProtocol>()),
    FileRecentAdapter = FileRecent.Adapter(enumPermissionAdapter<FileProtocol>()),
    FileFilterAdapter = FileFilter.Adapter(enumPermissionAdapter<FileFilterType>(), permissionStringsAdapter),
    DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(enumPermissionAdapter<DeviceConnectType>()),
    FilePathPreferenceAdapter = FilePathPreference.Adapter(
        enumPermissionAdapter<FileProtocol>(), enumPermissionAdapter<FileFilterSort>(), permissionStringsAdapter,
    ),
)

private inline fun <reified T : Enum<T>> enumPermissionAdapter(): ColumnAdapter<T, String> =
    object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T): String = value.name
    }

private val permissionStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> = databaseValue.split(',').filter(String::isNotEmpty)
    override fun encode(value: List<String>): String = value.joinToString(",")
}
