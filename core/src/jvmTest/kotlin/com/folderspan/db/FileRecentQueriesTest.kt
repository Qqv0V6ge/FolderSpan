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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FileRecentQueriesTest {
    @Test
    fun upsertDeduplicatesByPathProtocolProtocolId() = runBlocking {
        val database = createInMemoryDatabase()

        database.fileRecentQueries.upsert(
            name = "a.txt",
            isDirectory = false,
            path = "/a.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 100,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        database.fileRecentQueries.upsert(
            name = "a2.txt",
            isDirectory = false,
            path = "/a.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 3,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 200,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        val all = database.fileRecentQueries.selectAll().executeAsListAwait()
        assertEquals(1, all.size)
        assertEquals("a2.txt", all.single().name)
        assertEquals(200, all.single().lastAccessed)
        assertEquals(3, all.single().updatedDate)
    }

    @Test
    fun retainsMostRecentEntriesWithinLimit() = runBlocking {
        val database = createInMemoryDatabase()

        database.fileRecentQueries.upsert(
            name = "a.txt",
            isDirectory = false,
            path = "/a.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 100,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        database.fileRecentQueries.upsert(
            name = "b",
            isDirectory = true,
            path = "/b",
            mineType = "",
            size = 0,
            createdDate = 4,
            updatedDate = 5,
            protocol = FileProtocol.Device,
            protocolId = "device-1",
            lastAccessed = 200,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        val all = database.fileRecentQueries.selectAll().executeAsListAwait()
        assertEquals(2, all.size)
        assertEquals("/b", all.first().path)
        assertEquals(FileProtocol.Device, all.first().protocol)
        assertEquals("device-1", all.first().protocolId)
    }

    @Test
    fun prunesOldestBeyondLimit() = runBlocking {
        val database = createInMemoryDatabase()

        for (index in 0..300) {
            database.fileRecentQueries.upsert(
                name = "file-$index",
                isDirectory = false,
                path = "/file-$index",
                mineType = "text/plain",
                size = index.toLong(),
                createdDate = index.toLong(),
                updatedDate = index.toLong(),
                protocol = FileProtocol.Local,
                protocolId = "",
                lastAccessed = index.toLong(),
            ).awaitDatabaseReady()
            database.fileRecentQueries.deleteExtras().awaitDatabaseReady()
        }

        val all = database.fileRecentQueries.selectAll().executeAsListAwait()
        val paths = all.map { item ->  item.path }.toSet()
        assertEquals(300, all.size)
        assertEquals(false, paths.contains("/file-0"))
        assertEquals(true, paths.contains("/file-300"))
    }

    @Test
    fun deleteAllClearsRecents() = runBlocking {
        val database = createInMemoryDatabase()

        database.fileRecentQueries.upsert(
            name = "a.txt",
            isDirectory = false,
            path = "/a.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 100,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        database.fileRecentQueries.deleteAll().awaitDatabaseReady()

        val all = database.fileRecentQueries.selectAll().executeAsListAwait()
        assertEquals(emptyList(), all)
    }

    @Test
    fun deleteByIdsRemovesSelected() = runBlocking {
        val database = createInMemoryDatabase()

        database.fileRecentQueries.upsert(
            name = "a.txt",
            isDirectory = false,
            path = "/a.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 100,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        database.fileRecentQueries.upsert(
            name = "b",
            isDirectory = true,
            path = "/b",
            mineType = "",
            size = 0,
            createdDate = 4,
            updatedDate = 5,
            protocol = FileProtocol.Device,
            protocolId = "device-1",
            lastAccessed = 200,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        database.fileRecentQueries.upsert(
            name = "c",
            isDirectory = true,
            path = "/c",
            mineType = "",
            size = 0,
            createdDate = 6,
            updatedDate = 7,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 300,
        ).awaitDatabaseReady()
        database.fileRecentQueries.deleteExtras().awaitDatabaseReady()

        val all = database.fileRecentQueries.selectAll().executeAsListAwait()
        val idsToDelete = listOf(all.first().id, all.last().id)
        database.fileRecentQueries.deleteByIds(idsToDelete).awaitDatabaseReady()

        val remaining = database.fileRecentQueries.selectAll().executeAsListAwait()
        assertEquals(1, remaining.size)
        assertEquals("/b", remaining.single().path)
    }

}

private fun createInMemoryDatabase(): FolderSpanDatabase {
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
