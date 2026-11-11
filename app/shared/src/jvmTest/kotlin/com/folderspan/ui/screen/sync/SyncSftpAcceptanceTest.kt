package com.folderspan.ui.screen.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SftpDriveExtras
import com.folderspan.db.*
import com.folderspan.extensions.formatFileSize
import com.folderspan.service.*
import com.folderspan.ui.navigation.*
import com.folderspan.ui.state.file.FileRecentState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DatabaseReady
import com.folderspan.test.ChineseLocalizationTest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.jetbrains.skia.Image
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import strings.AppStrings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncSftpAcceptanceTest : ChineseLocalizationTest() {
    @Test
    fun realSftpReverseTasksRepeatWithoutCopiesAndShowActualRunStatistics() {
        val root = Files.createTempDirectory("sync-sftp-acceptance")
        val local = Files.createDirectory(root.resolve("local"))
        val remote = Files.createDirectory(root.resolve("sftp"))
        val localPayload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val remotePayload = ByteArray(2 * 1024 * 1024) { (it % 239).toByte() }
        Files.write(local.resolve("local.bin"), localPayload)
        Files.write(remote.resolve("remote.bin"), remotePayload)
        val modifiedAt = 1_700_000_000_000L
        Files.setLastModifiedTime(local.resolve("local.bin"), FileTime.fromMillis(modifiedAt))
        Files.setLastModifiedTime(remote.resolve("remote.bin"), FileTime.fromMillis(modifiedAt))
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(keyPair)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture-password" }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(remote)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        val database = database(driver)
        val taskState = TaskState(cleanupStore = TempTaskCleanupStore(root.resolve("cleanup").toString()))
        val sync = SyncState(TempSyncTaskStore(root.resolve("sync-tasks").toString())) {
            SyncDeviceIdentity("sftp-acceptance", "SFTP acceptance")
        }
        val networkState = NetworkState(database)
        stopKoin()
        val koin = startKoin { modules(module {
            single { database }
            single { sync }
            single { taskState }
            single { networkState }
            single { DeviceState() }
            single { MainState() }
            single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }.koin
        var network: Network? = null
        try {
            server.start()
            network = Network(
                "Local SFTP fixture", "/", "SFTP", "127.0.0.1:${server.port}", "fixture", "fixture-password",
                extras = NetworkDriveExtras(sftp = SftpDriveExtras(
                    knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(keyPair.public)}",
                )),
            )
            val endpoint = requireNotNull(network)
            val tasks = runBlocking {
                networkState.addNetwork(endpoint, persist = false)
                networkState.connectValidated(networkState.entries.single().id).getOrThrow()
                val forward = sync.createOrUpdateTask(SyncTask(
                    0, "Local to SFTP", enabled = false,
                    sourceType = SyncEndpointType.Local, sourcePath = local.toString(),
                    targetType = SyncEndpointType.Network, targetRef = endpoint.protocolId, targetPath = "/",
                    conflictPolicy = SyncConflictPolicy.Rename,
                )).getOrThrow()
                val reverse = sync.createOrUpdateTask(SyncTask(
                    0, "SFTP to local", enabled = false,
                    sourceType = SyncEndpointType.Network, sourceRef = endpoint.protocolId, sourcePath = "/",
                    targetType = SyncEndpointType.Local, targetPath = local.toString(),
                    conflictPolicy = SyncConflictPolicy.Rename,
                ), confirmReverseTasks = true).getOrThrow()
                listOf(forward, reverse)
            }
            val evidence = Path.of(System.getenv("FOLDERSPAN_SYNC_EVIDENCE_DIR") ?: "build/sync-defects-evidence")
            Files.createDirectories(evidence)
            val navigator = BackStackAppNavigator(mutableStateListOf(SyncManageScreen.toAppRoute()))
            runSkikoComposeUiTest(size = Size(1200f, 1000f)) {
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) {
                            (navigator.currentRoute as? AppRoute.Payload)?.let(AppRoutePayloadStore::resolve)?.Content()
                        }
                    }
                }
                fun runFromUi(task: SyncTask) {
                    val previousCount = sync.recordsFor(task.id).size
                    onNode(
                        hasContentDescription(AppStrings.ui_execute_immediately) and
                            hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(task.name))),
                        useUnmergedTree = true,
                    ).performClick()
                    waitUntil(timeoutMillis = 30_000) {
                        sync.recordsFor(task.id).size > previousCount &&
                            sync.getTask(task.id)?.lastStatus !in setOf(SyncRunStatus.Running, SyncRunStatus.Queued)
                    }
                    val record = sync.recordsFor(task.id).first()
                    assertEquals(SyncRunStatus.Success, record.status, record.message)
                    assertEquals(0, record.failureCount)
                }
                tasks.forEach(::runFromUi)
                assertEquals(1, sync.recordsFor(tasks[0].id).first().copiedCount)
                assertEquals(localPayload.size.toLong(), sync.recordsFor(tasks[0].id).first().transferredBytes)
                assertEquals(1, sync.recordsFor(tasks[1].id).first().copiedCount)
                assertEquals(remotePayload.size.toLong(), sync.recordsFor(tasks[1].id).first().transferredBytes)
                tasks.forEach(::runFromUi)
                tasks.forEach { task ->
                    val latest = sync.recordsFor(task.id).first()
                    assertEquals(0, latest.copiedCount)
                    assertEquals(0, latest.renamedCount)
                    assertEquals(0, latest.createdDirectoryCount)
                    assertEquals(2, latest.unchangedCount)
                    assertEquals(0L, latest.transferredBytes)
                }
                fun capture(name: String) {
                    val bitmap = onRoot().captureToImage().asSkiaBitmap()
                    Files.write(evidence.resolve(name), Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
                }
                capture("sftp-repeat-management.png")
                tasks.forEachIndexed { index, task ->
                    onNodeWithText(task.name).performClick()
                    waitUntil(timeoutMillis = 5_000) { navigator.routes.size == 2 }
                    val records = sync.recordsFor(task.id)
                    onNodeWithText(AppStrings.sync_counts_arg0_arg1_arg2_arg3_arg4.format(
                        arg0 = "0", arg1 = "0", arg2 = "0", arg3 = "2", arg4 = "0",
                    )).assertExists()
                    val copied = records.first { it.copiedCount > 0 }
                    assertTrue(copied.transferDurationMillis > 0)
                    onNodeWithText(AppStrings.sync_transfer_arg0_arg1.format(
                        arg0 = copied.transferredBytes.formatFileSize(),
                        arg1 = syncAverageBytesPerSecond(copied).formatFileSize(),
                    )).assertExists()
                    capture("sftp-repeat-detail-$index.png")
                    runOnIdle { navigator.pop() }
                    waitUntil(timeoutMillis = 5_000) { navigator.routes.size == 1 }
                }
            }
            assertTrue(Files.readAllBytes(remote.resolve("local.bin")).contentEquals(localPayload))
            assertTrue(Files.readAllBytes(local.resolve("remote.bin")).contentEquals(remotePayload))
            for (directory in listOf(local, remote)) {
                Files.list(directory).use { assertEquals(2L, it.count()) }
                for (name in listOf("local.bin", "remote.bin")) {
                    assertEquals(modifiedAt, Files.getLastModifiedTime(directory.resolve(name)).toMillis())
                }
            }
            Files.writeString(evidence.resolve("sftp-reverse-runs.json"), Json.encodeToString(tasks.flatMap { sync.recordsFor(it.id) }))
        } finally {
            runBlocking {
                sync.stop()
                taskState.stop()
                network?.close()
                koin.getOrNull<DeviceState>()?.stop()
            }
            stopKoin()
            server.stop(true)
            driver.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun database(driver: JdbcSqliteDriver) = FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = driverAdapter),
        DeviceConnectAdapter = DeviceConnect.Adapter(connectionTypeAdapter = driverConnectTypeAdapter, categoryAdapter = driverCategoryAdapter),
        FileBookmarkAdapter = FileBookmark.Adapter(typeAdapter = fileBookmarkAdapter, protocolAdapter = fileProtocolAdapter),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(typeAdapter = fileFilterType, extensionsAdapter = listOfStringsAdapter),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(protocolAdapter = fileProtocolAdapter, sortAdapter = fileFilterSortAdapter, ignoreFilesAdapter = listOfStringsAdapter),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(connectionTypeAdapter = driverConnectTypeAdapter),
    )
}
