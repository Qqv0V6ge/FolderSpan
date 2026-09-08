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
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceBookmarkImporterTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createImporterDatabase(driver)
    private val repository = ScopedBookmarkRepository(database)
    private val importer = DeviceBookmarkImporter(database, repository) { 1234L }

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun successfulImportRunsOnlyOnceAndKeepsRemoteUntouched() = runTest {
        var fetchCount = 0
        val remote = mutableListOf(
            DrawerBookmark(8, "Two", DrawerBookmarkType.Document, "/two", sort = 2),
            DrawerBookmark(7, "One", DrawerBookmarkType.Home, "/one", sort = 1),
        )

        val first = importer.importIfNeeded("phone") {
            fetchCount++
            Result.success(remote.toList())
        }.getOrThrow()
        val second = importer.importIfNeeded("phone") {
            fetchCount++
            Result.success(emptyList())
        }.getOrThrow()

        assertEquals(DeviceBookmarkImportOutcome.Imported(2), first)
        assertIs<DeviceBookmarkImportOutcome.AlreadyImported>(second)
        assertEquals(1, fetchCount)
        assertTrue(importer.isImported("phone"))
        assertEquals(listOf("One", "Two"), repository.list(BookmarkScope.of(FileProtocol.Device, "phone")).items.map { it.name })
        assertEquals(2, remote.size)
    }

    @Test
    fun failedFetchDoesNotWriteMarkerAndCanRetry() = runTest {
        val failed = importer.importIfNeeded("tablet") { Result.failure(IllegalStateException("offline")) }

        assertTrue(failed.isFailure)
        assertFalse(importer.isImported("tablet"))

        val retried = importer.importIfNeeded("tablet") {
            Result.success(listOf(DrawerBookmark(1, "Root", DrawerBookmarkType.Home, "/")))
        }
        assertTrue(retried.isSuccess)
        assertTrue(importer.isImported("tablet"))
        assertEquals(1L, repository.list(BookmarkScope.of(FileProtocol.Device, "tablet")).totalCount)
    }
}

private fun createImporterDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase {
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val fileProtocolAdapter = importerEnumAdapter<FileProtocol>()
    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = importerEnumAdapter<DeviceType>()),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = importerEnumAdapter<DeviceConnectType>(),
            categoryAdapter = importerEnumAdapter<DeviceCategory>(),
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = importerEnumAdapter<DrawerBookmarkType>(),
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = importerEnumAdapter<FileFilterType>(),
            extensionsAdapter = importerStringListAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = importerEnumAdapter<DeviceConnectType>(),
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = importerEnumAdapter<FileFilterSort>(),
            ignoreFilesAdapter = importerStringListAdapter,
        ),
    )
}

private inline fun <reified T : Enum<T>> importerEnumAdapter() = object : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
    override fun encode(value: T): String = value.name
}

private val importerStringListAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.takeIf(String::isNotEmpty)?.split(',') ?: emptyList()

    override fun encode(value: List<String>): String = value.joinToString(",")
}
