package com.folderspan.service.bookmark

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.Device
import com.folderspan.db.DeviceConnect
import com.folderspan.db.DeviceReceiveShare
import com.folderspan.db.FileBookmark
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileFilter
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedBookmarkRepositoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createDatabase(driver)
    private var now = 100L
    private val unavailableSources = mutableSetOf<BookmarkScope>()
    private val repository = ScopedBookmarkRepository(
        database = database,
        sourceAvailability = BookmarkSourceAvailability { scope -> scope !in unavailableSources },
        nowMillis = { now++ },
    )

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun crudIsStrictlyIsolatedByProtocolAndSourceId() = runTest {
        val local = BookmarkScope.Local
        val phone = BookmarkScope.of(FileProtocol.Device, "phone")
        val tablet = BookmarkScope.of(FileProtocol.Device, "tablet")
        val share = BookmarkScope.of(FileProtocol.Share, "system-share")
        val network = BookmarkScope.of(FileProtocol.Network, "nas")

        val localItem = repository.create(local, input("Local", "/local"))
        val phoneItem = repository.create(phone, input("Phone", "/phone"))
        repository.create(tablet, input("Tablet", "/tablet"))
        repository.create(share, input("Share", "/share"))
        repository.create(network, input("Network", "/network"))

        assertEquals(listOf("Phone"), repository.list(phone).items.map { it.name })
        assertNull(repository.get(tablet, phoneItem.id))
        assertNull(repository.update(tablet, phoneItem.id, input("Wrong", "/wrong")))
        assertFalse(repository.delete(tablet, phoneItem.id))

        val updated = repository.update(phone, phoneItem.id, input("Renamed", "/phone/new"))
        assertEquals("Renamed", updated?.name)
        assertTrue(repository.delete(local, localItem.id))
        assertEquals(0L, repository.list(local).totalCount)
        assertEquals(1L, repository.list(phone).totalCount)
    }

    @Test
    fun paginationOrderingAndAvailabilityAreReportedPerScope() = runTest {
        val scope = BookmarkScope.of(FileProtocol.Network, "offline-nas")
        unavailableSources += scope
        repeat(5) { index ->
            repository.create(scope, input("Item $index", "/$index", sort = (4 - index).toLong()))
        }

        val page = repository.list(scope, offset = 1, limit = 2)

        assertEquals(listOf("Item 3", "Item 2"), page.items.map { it.name })
        assertEquals(5L, page.totalCount)
        assertTrue(page.hasMore)
        assertFalse(page.sourceAvailable)
        assertTrue(page.items.all { item -> !item.sourceAvailable })
    }

    @Test
    fun reorderRequiresTheCompleteScopedIdSet() = runTest {
        val scope = BookmarkScope.of(FileProtocol.Share, "share-1")
        val one = repository.create(scope, input("One", "/one"))
        val two = repository.create(scope, input("Two", "/two"))
        val three = repository.create(scope, input("Three", "/three"))

        assertFalse(repository.reorder(scope, listOf(one.id, two.id)))
        assertFalse(repository.reorder(scope, listOf(one.id, one.id, three.id)))
        assertTrue(repository.reorder(scope, listOf(three.id, one.id, two.id)))
        assertEquals(listOf("Three", "One", "Two"), repository.list(scope).items.map { it.name })
    }

    private fun input(name: String, path: String, sort: Long? = null) = ScopedBookmarkInput(
        name = name,
        type = DrawerBookmarkType.Custom,
        path = path,
        sort = sort,
    )
}

private fun createDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase {
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val fileProtocolAdapter = enumAdapter<FileProtocol>()
    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = enumAdapter<DeviceType>()),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = enumAdapter<DeviceConnectType>(),
            categoryAdapter = enumAdapter<DeviceCategory>(),
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = enumAdapter<DrawerBookmarkType>(),
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = enumAdapter<FileFilterType>(),
            extensionsAdapter = stringListAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = enumAdapter<DeviceConnectType>(),
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = enumAdapter<FileFilterSort>(),
            ignoreFilesAdapter = stringListAdapter,
        ),
    )
}

private inline fun <reified T : Enum<T>> enumAdapter() = object : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
    override fun encode(value: T): String = value.name
}

private val stringListAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.takeIf(String::isNotEmpty)?.split(',') ?: emptyList()

    override fun encode(value: List<String>): String = value.joinToString(",")
}
