package com.folderspan.pro.domain.usecase

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.pro.domain.model.*
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.main.*
import com.folderspan.utils.*
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultConfigurationSnapshotsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun deviceProviderReadsAndAppliesDeviceConfiguration() = runBlocking {
        val database = createInMemoryDatabase()
        val provider = DeviceConfigurationSnapshotProvider(database, json)
        database.deviceQueries.insert("old-device", "Old", "127.0.0.1", 1, DeviceType.JVM).awaitDatabaseReady()

        provider.apply(
            json.encodeToJsonElement(
                DeviceConfigurationSnapshot(
                    devices = listOf(DeviceSnapshotItem("device-1", "Phone", "10.0.0.2", 12040, DeviceType.Android, true)),
                    connections = listOf(
                        DeviceConnectionSnapshotItem(
                            id = "device-1",
                            connectionType = DeviceConnectType.AUTO_CONNECT,
                            firstConnection = 11,
                            lastConnection = 22,
                            category = DeviceCategory.SERVER,
                            roleId = 2,
                        ),
                    ),
                    receiveShares = listOf(
                        DeviceReceiveShareSnapshotItem(
                            id = "device-1",
                            connectionType = DeviceConnectType.APPROVED,
                            path = "/downloads",
                        ),
                    ),
                ),
            ),
        )

        val device = database.deviceQueries.queryAll().executeAsListAwait().single()
        assertEquals("device-1", device.id)
        assertEquals(DeviceType.Android, device.type)
        assertEquals(true, device.hasRemarks)
        val connection = database.deviceConnectQueries.queryAll().executeAsListAwait().single()
        assertEquals(11L, connection.firstConnection)
        assertEquals(2L, connection.roleId)
        assertEquals("/downloads", database.deviceReceiveShareQueries.selectAll().executeAsListAwait().single().path)

        val snapshot = json.decodeFromJsonElement<DeviceConfigurationSnapshot>(provider.read())
        assertEquals("device-1", snapshot.devices.single().id)
    }

    @Test
    fun roleProviderReadsAndAppliesRolesPermissionsAndLinks() = runBlocking {
        val database = createInMemoryDatabase()
        val provider = RoleConfigurationSnapshotProvider(database, json)

        provider.apply(
            json.encodeToJsonElement(
                RoleConfigurationSnapshot(
                    roles = listOf(RoleSnapshotItem(id = 10, name = "Ops", comment = "remote", sortOrder = 5)),
                    permissions = listOf(
                        DevicePermissionSnapshotItem(
                            id = 20,
                            path = "/data",
                            useAll = false,
                            read = true,
                            write = true,
                            remove = false,
                            rename = false,
                            sortOrder = 3,
                            comment = "data",
                        ),
                    ),
                    rolePermissions = listOf(RolePermissionLinkSnapshotItem(10, 20)),
                ),
            ),
        )

        val role = database.deviceRoleQueries.selectById(10).executeAsOneOrNullAwait()
        assertEquals("Ops", role?.name)
        val permission = database.devicePermissionQueries.selectById(20).executeAsOneOrNullAwait()
        assertEquals("/data", permission?.path)
        assertEquals(1, database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait().size)

        val snapshot = json.decodeFromJsonElement<RoleConfigurationSnapshot>(provider.read())
        assertEquals("Ops", snapshot.roles.single().name)
        assertEquals("/data", snapshot.permissions.single().path)
    }

    @Test
    fun networkProviderPreservesLocalEncryptedCredentialsWithoutSyncLayerCrypto() = runBlocking {
        SettingsUtils.init(MapSettings())
        val database = createInMemoryDatabase()
        val provider = NetworkDrivesConfigurationSnapshotProvider(database, json)
        val encryptedPassword = "encrypted-password-from-local-store"
        val encryptedExtras = "encrypted-extras-from-local-store".encodeToByteArray()

        provider.apply(
            json.encodeToJsonElement(
                NetworkConfigurationSnapshotCodec().encode(
                    listOf(
                        EncryptedNetworkSnapshotItem(
                            name = "SFTP",
                            protocol = "SFTP",
                            host = "10.0.0.2:22",
                            username = "alice",
                            encryptedPassword = encryptedPassword,
                            pathSeparator = "/",
                            pinned = true,
                            encryptedExtras = encryptedExtras,
                        ),
                    ),
                ),
            ),
        )

        val stored = database.networkDriveQueries.selectAll().executeAsListAwait().single()
        assertEquals(encryptedPassword, stored.password)
        assertEquals(encryptedExtras.toList(), stored.extras.toList())

        val snapshot = json.decodeFromJsonElement<NetworkConfigurationSnapshot>(provider.read())
        val encrypted = NetworkConfigurationSnapshotCodec().decode(snapshot).single()
        assertEquals(encryptedPassword, encrypted.encryptedPassword)
        assertEquals(encryptedExtras.toList(), encrypted.encryptedExtras.toList())
    }

    @Test
    fun webRtcProviderPreservesEncryptedTurnPasswordWithoutSyncLayerCrypto() = runBlocking {
        val database = createInMemoryDatabase()
        val provider = WebRtcRoomsConfigurationSnapshotProvider(database, json)
        val encryptedTurnPassword = "encrypted-turn-password-from-local-store"

        provider.apply(
            json.encodeToJsonElement(
                WebRtcRoomConfigurationSnapshot(
                    rooms = listOf(
                        WebRtcRoomSnapshotItem(
                            name = "Office",
                            wssUrl = "wss://signal.example/ws",
                            roomId = "office-room",
                            stunUrl = "stun:signal.example:3478",
                            turnUrl = "turn:signal.example:3478",
                            turnUsername = "alice",
                            encryptedTurnPassword = encryptedTurnPassword,
                            source = com.folderspan.data.main.webrtc.WebRtcRoomSource.Official,
                            pinned = true,
                            sortOrder = 4L,
                        ),
                    ),
                ),
            ),
        )

        val stored = database.webRtcRoomQueries.selectAll().executeAsListAwait().single()
        assertEquals(encryptedTurnPassword, stored.turnPassword)
        assertEquals(com.folderspan.data.main.webrtc.WebRtcRoomSource.Official.name, stored.source)
        assertEquals(1L, stored.pinned)
        assertEquals(4L, stored.sortOrder)

        val snapshot = json.decodeFromJsonElement<WebRtcRoomConfigurationSnapshot>(provider.read())
        assertEquals(encryptedTurnPassword, snapshot.rooms.single().encryptedTurnPassword)
        assertEquals(com.folderspan.data.main.webrtc.WebRtcRoomSource.Official, snapshot.rooms.single().source)
        assertTrue(json.encodeToString(snapshot).contains(encryptedTurnPassword))
        assertTrue(!json.encodeToString(snapshot).contains("plain-turn-password"))
    }

    @Test
    fun syncTaskProviderExcludesRunHistoryAndRuntimeStatus() = runBlocking {
        val store = TempSyncTaskStore(Files.createTempDirectory("config-sync-tasks").absolutePathString())
        val provider = SyncTasksConfigurationSnapshotProvider(store, json)
        val task = store.saveTask(syncTask(name = "Docs").copy(lastStatus = SyncRunStatus.Failure, lastMessage = "password=secret"))
        store.appendRun(
            SyncRunRecord(
                runId = 1,
                taskId = task.id,
                trigger = "manual",
                startedAt = 1,
                endedAt = 2,
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = "failed",
            ),
        )

        val snapshot = json.decodeFromJsonElement<SyncTasksConfigurationSnapshot>(provider.read())
        assertEquals(SyncRunStatus.Idle, snapshot.tasks.single().lastStatus)
        assertEquals("", snapshot.tasks.single().lastMessage)

        provider.apply(json.encodeToJsonElement(snapshot.copy(tasks = listOf(syncTask(name = "Remote")))))

        assertEquals(listOf("Remote"), store.loadTasks().map { item -> item.name })
        assertTrue(store.loadRuns(task.id).isEmpty())
    }

    private fun syncTask(name: String): SyncTask =
        SyncTask(
            id = 0,
            name = name,
            sourceType = SyncEndpointType.Local,
            sourcePath = "/source",
            targetType = SyncEndpointType.Local,
            targetPath = "/target",
            conflictPolicy = SyncConflictPolicy.Replace,
            scheduleType = SyncScheduleType.Manual,
            lastStatus = SyncRunStatus.Idle,
        )
}

private fun createInMemoryDatabase(): FolderSpanDatabase {
    SettingsUtils.init(createSettings())
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = deviceTypeAdapter),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
            categoryAdapter = deviceCategoryAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(connectionTypeAdapter = deviceConnectTypeAdapter),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = drawerBookmarkTypeAdapter,
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = fileFilterTypeAdapter,
            extensionsAdapter = listOfStringsAdapter,
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = fileFilterSortAdapter,
            ignoreFilesAdapter = listOfStringsAdapter,
        ),
    )
}

private val deviceTypeAdapter = object : ColumnAdapter<DeviceType, String> {
    override fun decode(databaseValue: String): DeviceType = DeviceType.valueOf(databaseValue)
    override fun encode(value: DeviceType): String = value.name
}

private val deviceConnectTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
    override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
    override fun encode(value: DeviceConnectType): String = value.name
}

private val deviceCategoryAdapter = object : ColumnAdapter<DeviceCategory, String> {
    override fun decode(databaseValue: String): DeviceCategory = DeviceCategory.valueOf(databaseValue)
    override fun encode(value: DeviceCategory): String = value.name
}

private val drawerBookmarkTypeAdapter = object : ColumnAdapter<DrawerBookmarkType, String> {
    override fun decode(databaseValue: String): DrawerBookmarkType = DrawerBookmarkType.valueOf(databaseValue)
    override fun encode(value: DrawerBookmarkType): String = value.name
}

private val fileProtocolAdapter = object : ColumnAdapter<FileProtocol, String> {
    override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
    override fun encode(value: FileProtocol): String = value.name
}

private val fileFilterTypeAdapter = object : ColumnAdapter<FileFilterType, String> {
    override fun decode(databaseValue: String): FileFilterType = FileFilterType.valueOf(databaseValue)
    override fun encode(value: FileFilterType): String = value.name
}

private val listOfStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.split(",").map { item -> item.trim() }.filter { item -> item.isNotEmpty() }

    override fun encode(value: List<String>): String = value.joinToString(",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}
