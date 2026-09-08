package com.folderspan.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeviceConnectQueriesTest {
    @Test
    fun upsertUpdatesExistingDeviceAccessInsteadOfViolatingUniqueConstraint() = runBlocking {
        val testDatabase = createInMemoryDatabase()
        val database = testDatabase.database

        database.deviceConnectQueries.insert(
            id = "device-1",
            connectionType = DeviceConnectType.WAITING,
            category = DeviceCategory.CLIENT,
            value = null
        ).awaitDatabaseReady()
        testDatabase.driver.execute(
            identifier = null,
            sql = """
                UPDATE DeviceConnect
                SET firstConnection = 123, lastConnection = 456
                WHERE id = ? AND category = ?
            """.trimIndent(),
            parameters = 2
        ) {
            bindString(0, "device-1")
            bindString(1, DeviceCategory.CLIENT.name)
        }
        val first = assertNotNull(
            database.deviceConnectQueries.queryByIdAndCategory(
                id = "device-1",
                category = DeviceCategory.CLIENT
            ).executeAsOneOrNullAwait()
        )

        database.deviceConnectQueries.upsert(
            id = "device-1",
            connectionType = DeviceConnectType.AUTO_CONNECT,
            category = DeviceCategory.CLIENT,
            roleId = null
        ).awaitDatabaseReady()

        val saved = database.deviceConnectQueries.queryByCategory(DeviceCategory.CLIENT).executeAsListAwait()
        assertEquals(1, saved.size)
        val updated = saved.single()
        assertEquals(DeviceConnectType.AUTO_CONNECT, updated.connectionType)
        assertEquals(123L, first.firstConnection)
        assertEquals(123L, updated.firstConnection)
        assertEquals(-1L, updated.roleId)
    }
}

private data class TestDatabase(
    val database: FolderSpanDatabase,
    val driver: JdbcSqliteDriver
)

private fun createInMemoryDatabase(): TestDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    val database = FolderSpanDatabase(
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
    return TestDatabase(database, driver)
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
    override fun decode(databaseValue: String): List<String> = databaseValue
        .split(",")
        .filter { it.isNotEmpty() }

    override fun encode(value: List<String>): String = value.joinToString(",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}
