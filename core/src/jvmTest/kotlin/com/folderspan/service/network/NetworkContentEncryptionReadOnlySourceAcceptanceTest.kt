package com.folderspan.service.network

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.*
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.*
import com.folderspan.db.*
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.PlatformHttpCallbacks
import com.folderspan.service.http.server.linkshare.LinkShareRouteDispatcher
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Real HTTP/LinkShare sources and a disposable SFTP target, through the application's copy coordinator. */
class NetworkContentEncryptionReadOnlySourceAcceptanceTest : ChineseLocalizationTest() {
    @Test fun httpDirectoryCopyToEncryptedTargetAndReverseUploadRejected() = verify(linkShare = false)
    @Test fun linkShareCopyToEncryptedTargetAndReverseUploadRejected() = verify(linkShare = true)

    private fun verify(linkShare: Boolean) = runBlocking(Dispatchers.IO) {
        val targetFixture = readNetworkContentEncryptionFixture("SFTP")
        val base = Json.decodeFromJsonElement<NetworkConnectionConfig>(targetFixture.getValue("config"))
        val raw = NetworkClientFactory.create(base)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val local = Files.createTempDirectory("folderspan-readonly-encryption-")
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        var source: Network? = null
        var target: Network? = null
        var server: LinkShareRawHttpServer? = null
        var targetConfig: NetworkConnectionConfig? = null
        var remoteRoot: StoragePath? = null
        var passed = false
        try {
            val database = database(driver)
            val networkState = NetworkState(database)
            stopKoin()
            val application = startKoin { modules(module {
                single { database }
                single { taskState }
                single { networkState }
                single { DeviceState() }
                single { NotificationState() }
            }) }
            val deviceState = application.koin.get<DeviceState>()
            val recents = FileRecentState()
            withTimeout(240_000) {
                val directory = targetFixture["root"]!!.jsonPrimitive.content.trimEnd('/') + "/readonly-" + UUID.randomUUID()
                assertTrue(raw.createFolder(directory).getOrThrow())
                val entry = raw.stat(StoragePath(directory)).getOrThrow()
                remoteRoot = StoragePath(entry.path, entry.reference)
                val pending = base.copy(extras = base.extras.copy(
                    contentEncryption = StorageContentLocationReference(root = remoteRoot!!),
                ))
                val reference = StorageContentPlatform.setupPassword(pending, ("readonly target password " + UUID.randomUUID()).toCharArray()).getOrThrow()
                val encrypted = pending.copy(extras = pending.extras.copy(contentEncryption = reference))
                targetConfig = encrypted
                target = network(encrypted, taskState)

                val sourceFile: FileSimpleInfo
                val original: ByteArray
                if (linkShare) {
                    val shared = local.resolve("shared")
                    Files.createDirectories(shared)
                    original = ByteArray(192 * 1024 + 31) { (it % 251).toByte() }
                    Files.write(shared.resolve("source.bin"), original)
                    val root = FileSimpleInfo.nullFileSimpleInfo().withCopy(
                        name = shared.fileName.toString(), path = shared.toString(), isDirectory = true,
                        protocol = FileProtocol.Local, protocolId = "",
                    )
                    val state = FileShareState().apply {
                        updateAutoApprove(true)
                        updateAllowUpload(false)
                        updateLinkShareDefaults(allowHidden = false, files = listOf(root), allowUpload = false)
                    }
                    val sharePassword = "share login password " + UUID.randomUUID()
                    state.updateConnectPassword(sharePassword)
                    lateinit var dispatcher: LinkShareRouteDispatcher
                    val responder = HttpShareFileServer(PlatformHttpCallbacks(handle = { dispatcher.dispatch(it) }))
                    dispatcher = LinkShareRouteDispatcher(state, responder,
                        advertisedHostProvider = { setOf("127.0.0.1", "localhost") })
                    val port = ServerSocket(0).use { it.localPort }
                    dispatcher.configurePorts(port, null)
                    server = LinkShareRawHttpServer("http", handler = { dispatcher.dispatch(it) })
                        .also { it.start(port, "127.0.0.1") }
                    source = NetworkShare("Readonly source", "http://127.0.0.1:$port", sharePassword,
                        userAgent = "FolderSpan content acceptance")
                    sourceFile = source!!.getList("/shared", null, null).getOrThrow().single { !it.isDirectory }
                } else {
                    val fixture = readNetworkContentEncryptionFixture("HTTP_SOURCE")
                    source = network(Json.decodeFromJsonElement(fixture.getValue("config")), taskState)
                    sourceFile = source!!.getList("/", null, null).getOrThrow().first { !it.isDirectory }
                    original = download(source!!, sourceFile, local.resolve("original.bin"))
                    assertTrue(original.isNotEmpty(), "The real HTTP source must contain bytes")
                }
                val src = source!!
                val dst = target!!
                assertFalse(src.menuPermission.write)
                assertTrue(StorageContentPlatform.setupPassword(src.connectionConfig(), "readonly content password".toCharArray()).isFailure)
                networkState.entries.add(NetworkEntry(-1L, src, false))
                networkState.entries.add(NetworkEntry(-2L, dst, false))
                val coordinator = FileStateCopyCoordinator(taskState, deviceState, networkState, { Local() }, recents)
                val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING,
                    protocol = FileProtocol.Network, protocolId = src.protocolId)
                taskState.addOrUpdate(task)
                val destination = sourceFile.withCopy(name = "copied.bin", path = "$directory/copied.bin",
                    protocol = FileProtocol.Network, protocolId = dst.protocolId, storageReference = null)

                // Direct endpoint copying must defer to the coordinator, never write a remote path locally.
                val direct = src.copyTo(task, sourceFile, destination)
                assertIs<NetworkUnsupportedException>(direct.exceptionOrNull())
                assertFalse(Files.exists(Path.of(destination.path)))
                assertTrue(coordinator.copyTo(task, sourceFile, destination).getOrThrow())
                val copied = dst.getList(directory, null, null).getOrThrow().single { it.name == "copied.bin" }
                assertEquals(original.size.toLong(), copied.size)
                assertContentEquals(original, download(dst, copied, local.resolve("copied.bin")))
                assertTrue(copied.storageReference?.id?.isNotBlank() == true,
                    "The result must carry managed identity rather than be an ordinary upload")

                var reverseRead = false
                val reverse = src.uploadFromSource("/reverse-upload.bin", original.size.toLong(), { _, _ -> }) {
                    if (reverseRead) null else original.also { reverseRead = true }
                }
                assertTrue(reverse.isFailure)
                assertFalse(reverseRead, "A read-only source must reject before consuming upload bytes")
                assertContentEquals(original, download(src, sourceFile, local.resolve("unchanged.bin")))
                if (linkShare) assertContentEquals(original, Files.readAllBytes(local.resolve("shared/source.bin")))
                assertTrue(dst.delete(copied).getOrThrow())
                passed = true
                println("READONLY_ENCRYPTION_COPY passed source=" + if (linkShare) "LinkShare" else "HttpDirectory")
            }
        } finally {
            try {
                source?.close()
                target?.close()
                server?.stop()
                targetConfig?.let { StorageContentPlatform.lock(it) }
                remoteRoot?.let { location ->
                    val cleanup = runCatching { deleteNetworkContentEncryptionFixture(raw, location) }
                    if (cleanup.isFailure) {
                        println("READONLY_ENCRYPTION_COPY cleanup failed source=" + (if (linkShare) "LinkShare" else "HttpDirectory") +
                            " type=" + cleanup.exceptionOrNull()!!::class.simpleName)
                        if (passed) cleanup.getOrThrow()
                    } else println("READONLY_ENCRYPTION_COPY cleaned source=" + if (linkShare) "LinkShare" else "HttpDirectory")
                }
            } finally {
                raw.close()
                stopKoin()
                driver.close()
                local.toFile().deleteRecursively()
            }
        }
    }

    private fun network(config: NetworkConnectionConfig, state: TaskState): Network =
        object : Network("Readonly encryption acceptance", config.pathSeparator, config.protocol,
            config.host, config.username, config.password, extras = config.extras) {
            override val taskState = state
        }

    private suspend fun download(network: Network, file: FileSimpleInfo, target: Path): ByteArray {
        assertTrue(network.downloadFileToLocal(file, target.toString()) { _, _ -> }.getOrThrow())
        return Files.readAllBytes(target)
    }

    private fun database(driver: JdbcSqliteDriver): FolderSpanDatabase {
        SettingsUtils.init(createSettings())
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        val strings = object : ColumnAdapter<List<String>, String> {
            override fun decode(databaseValue: String) = databaseValue.split(',').filter(String::isNotEmpty)
            override fun encode(value: List<String>) = value.joinToString(",")
        }
        return FolderSpanDatabase(driver,
            DeviceAdapter = com.folderspan.db.Device.Adapter(typeAdapter = enumAdapter<DeviceType>()),
            DeviceConnectAdapter = DeviceConnect.Adapter(connectionTypeAdapter = enumAdapter<DeviceConnectType>(), categoryAdapter = enumAdapter<DeviceCategory>()),
            FileBookmarkAdapter = FileBookmark.Adapter(typeAdapter = enumAdapter<DrawerBookmarkType>(), protocolAdapter = enumAdapter<FileProtocol>()),
            FileFavoriteAdapter = FileFavorite.Adapter(enumAdapter<FileProtocol>()),
            FileRecentAdapter = FileRecent.Adapter(enumAdapter<FileProtocol>()),
            FileFilterAdapter = FileFilter.Adapter(typeAdapter = enumAdapter<FileFilterType>(), extensionsAdapter = strings),
            DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(enumAdapter<DeviceConnectType>()),
            FilePathPreferenceAdapter = FilePathPreference.Adapter(protocolAdapter = enumAdapter<FileProtocol>(), sortAdapter = enumAdapter<FileFilterSort>(), ignoreFilesAdapter = strings),
        )
    }

    private inline fun <reified T : Enum<T>> enumAdapter() = object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T) = value.name
    }
}
