package com.folderspan.ui.state.main

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.*
import com.folderspan.db.*
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkStateTest {
    @Test
    fun loadPersistedS3Extras() = runBlocking {
        val database = createInMemoryDatabase()
        database.networkDriveQueries.insert(
            name = "S3",
            protocol = "S3",
            host = "https://s3.us-east-1.amazonaws.com",
            username = "AKIA_TEST",
            password = encryptPassword("secret"),
            pathSeparator = "/",
            pinned = 0L,
            extras = encodeExtras(
                NetworkDriveExtras(
                    s3 = S3DriveExtras(
                        bucket = "bucket-a",
                        region = "us-east-1",
                        endpoint = "https://s3.us-east-1.amazonaws.com",
                        sessionToken = "token-a",
                        forcePathStyle = true
                    )
                )
            )
        ).awaitDatabaseReady()

        val state = NetworkState(database)
        state.loadPersisted()

        val loaded = state.entries.single().network
        assertEquals("S3", loaded.protocol)
        assertEquals("bucket-a", loaded.extras.s3.bucket)
        assertEquals("us-east-1", loaded.extras.s3.region)
        assertEquals(true, loaded.extras.s3.forcePathStyle)
    }

    @Test
    fun loadPersistedAddsNetworks() = runBlocking {
        val database = createInMemoryDatabase()
        database.networkDriveQueries.insert(
            name = "Test",
            protocol = "FTP",
            host = "127.0.0.1:21",
            username = "user",
            password = encryptPassword("pass"),
            pathSeparator = "/",
            pinned = 0L,
            extras = encodeExtras(
                NetworkDriveExtras(
                    ftp = FtpDriveExtras(
                        passiveMode = true,
                        ftpsEnabled = false
                    )
                )
            )
        ).awaitDatabaseReady()

        val state = NetworkState(database)
        state.loadPersisted()

        assertEquals(1, state.entries.size)
        val entry = state.entries.single()
        val network = entry.network
        assertEquals("FTP", network.protocol)
        assertEquals("127.0.0.1:21", network.host)
        assertEquals("/", network.pathSeparator)
        assertEquals(true, network.extras.ftp.passiveMode)
        assertEquals(true, entry.isPersisted)
    }

    @Test
    fun addNetworkRespectsPersistenceToggle() = runBlocking {
        val database = createInMemoryDatabase()
        val state = NetworkState(database)
        val sessionOnly = Network(
            name = "Session",
            pathSeparator = "/",
            protocol = "FTP",
            host = "10.0.0.1:21",
            username = "user",
            password = "pass",
            extras = NetworkDriveExtras(
                ftp = FtpDriveExtras(
                    passiveMode = true,
                    ftpsEnabled = false
                )
            )
        )

        state.addNetwork(sessionOnly, persist = false)
        assertEquals(1, state.entries.size)
        assertEquals(0, database.networkDriveQueries.selectAll().executeAsListAwait().size)

        val persisted = Network(
            name = "Saved",
            pathSeparator = "/",
            protocol = "SFTP",
            host = "10.0.0.2:22",
            username = "user2",
            password = "pass2",
            extras = NetworkDriveExtras(
                sftp = SftpDriveExtras(
                    privateKey = "key",
                    knownHosts = "known"
                )
            )
        )
        state.addNetwork(persisted, persist = true)

        val saved = database.networkDriveQueries.selectAll().executeAsListAwait()
        assertEquals(1, saved.size)
        assertEquals("SFTP", saved.single().protocol)
        assertEquals(2, state.entries.size)
    }

    @Test
    fun updatePersistedTogglesDatabaseState() = runBlocking {
        val database = createInMemoryDatabase()
        val state = NetworkState(database)
        val network = Network(
            name = "Toggle",
            pathSeparator = "/",
            protocol = "FTP",
            host = "10.0.0.3:21",
            username = "user3",
            password = "pass3",
            extras = NetworkDriveExtras(
                ftp = FtpDriveExtras(
                    passiveMode = true,
                    ftpsEnabled = false
                )
            )
        )

        state.addNetwork(network, persist = false)
        val entry = state.entries.single()
        state.updatePersisted(entry, persist = true)
        assertEquals(1, database.networkDriveQueries.selectAll().executeAsListAwait().size)
        assertEquals(true, state.entries.single().isPersisted)

        val persistedEntry = state.entries.single()
        state.updatePersisted(persistedEntry, persist = false)
        assertEquals(0, database.networkDriveQueries.selectAll().executeAsListAwait().size)
        assertEquals(false, state.entries.single().isPersisted)
    }

    @Test
    fun removeEntryDeletesDatabaseRow() = runBlocking {
        val database = createInMemoryDatabase()
        val state = NetworkState(database)
        val network = Network(
            name = "Delete",
            pathSeparator = "/",
            protocol = "FTP",
            host = "10.0.0.4:21",
            username = "user4",
            password = "pass4",
            extras = NetworkDriveExtras(
                ftp = FtpDriveExtras(
                    passiveMode = true,
                    ftpsEnabled = false
                )
            )
        )

        state.addNetwork(network, persist = true)
        assertEquals(1, database.networkDriveQueries.selectAll().executeAsListAwait().size)
        val entry = state.entries.single()
        state.removeEntry(entry)
        assertEquals(0, state.entries.size)
        assertEquals(0, database.networkDriveQueries.selectAll().executeAsListAwait().size)
    }

    @Test
    fun updateEntryUpdatesPersistedRow() = runBlocking {
        val database = createInMemoryDatabase()
        val state = NetworkState(database)
        val network = Network(
            name = "Old",
            pathSeparator = "/",
            protocol = "FTP",
            host = "10.0.0.5:21",
            username = "user5",
            password = "pass5",
            extras = NetworkDriveExtras(
                ftp = FtpDriveExtras(
                    passiveMode = true,
                    ftpsEnabled = false
                )
            )
        )
        state.addNetwork(network, persist = true)
        val entry = state.entries.single()

        val updated = Network(
            name = "New",
            pathSeparator = "/",
            protocol = "FTP",
            host = "10.0.0.6:21",
            username = "user6",
            password = "pass6",
            extras = NetworkDriveExtras(
                ftp = FtpDriveExtras(
                    passiveMode = true,
                    ftpsEnabled = false
                )
            )
        )
        state.updateEntry(entry, updated)

        val saved = database.networkDriveQueries.selectAll().executeAsListAwait()
        assertEquals(1, saved.size)
        assertEquals("New", saved.single().name)
        assertEquals("10.0.0.6:21", saved.single().host)
        assertEquals("user6", saved.single().username)
        assertEquals("pass6", decryptPassword(saved.single().password))
        assertEquals("New", state.entries.single().network.name)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun encodeExtras(extras: NetworkDriveExtras): ByteArray {
    val plain = ProtoBuf.encodeToByteArray(NetworkDriveExtras.serializer(), extras)
    return SymmetricCrypto.encrypt(plain)
}

private fun encryptPassword(password: String): String {
    return SymmetricCrypto.encrypt(password)
}

private fun decryptPassword(password: String): String {
    return SymmetricCrypto.decrypt(password)
}

private fun createInMemoryDatabase(): FolderSpanDatabase {
    SettingsUtils.init(createSettings())
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(
            typeAdapter = deviceTypeAdapter,
        ),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
            categoryAdapter = deviceCategoryAdapter,
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = drawerBookmarkTypeAdapter,
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileRecentAdapter = FileRecent.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = fileFilterTypeAdapter,
            extensionsAdapter = listOfStringsAdapter
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = fileFilterSortAdapter,
            ignoreFilesAdapter = listOfStringsAdapter,
        )
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
    override fun decode(databaseValue: String): List<String> {
        return if (databaseValue.isEmpty()) {
            listOf()
        } else {
            databaseValue.split(",")
        }
    }

    override fun encode(value: List<String>): String = value.joinToString(separator = ",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}
