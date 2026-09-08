package com.folderspan.ui.state.file

import strings.AppStrings

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.DeviceConnect
import com.folderspan.db.DeviceReceiveShare
import com.folderspan.db.FileBookmark
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileFilter
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.bookmark.BookmarkScope
import com.folderspan.service.bookmark.DeviceBookmarkClient
import com.folderspan.service.bookmark.ScopedBookmarkInput
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.utils.DatabaseReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileBookmarkStateTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createBookmarkStateDatabase(driver)
    private val repository = ScopedBookmarkRepository(database)

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun remoteLoadUsesLiveDeviceDataWithoutPersistingIt() = runTest {
        val deviceScope = BookmarkScope.of(FileProtocol.Device, "linux")
        repository.create(
            deviceScope,
            ScopedBookmarkInput(AppStrings.ui_test_file_bookmark_state_cache_directory, DrawerBookmarkType.Home, "/stale"),
        )
        val remoteBookmarks = listOf(
            DrawerBookmark(11, AppStrings.ui_home_directory, DrawerBookmarkType.Home, "/home/webb", sort = 1),
            DrawerBookmark(12, AppStrings.ui_download, DrawerBookmarkType.Download, "/home/webb/Downloads", sort = 2),
        )
        val client = RecordingBookmarkClient(bookmarksResult = Result.success(remoteBookmarks))
        val device = testDevice(client)
        val state = FileBookmarkState(repository) { device }

        state.load()

        assertEquals(remoteBookmarks, state.bookmarks)
        assertEquals(1, client.getCount)
        assertEquals(1L, repository.list(deviceScope).totalCount)
        assertTrue(state.sourceAvailable)
    }

    @Test
    fun remoteLoadFailureDoesNotFallBackToPersistedCache() = runTest {
        val deviceScope = BookmarkScope.of(FileProtocol.Device, "linux")
        repository.create(
            deviceScope,
            ScopedBookmarkInput(AppStrings.ui_test_file_bookmark_state_cache_directory, DrawerBookmarkType.Home, "/stale"),
        )
        val client = RecordingBookmarkClient(
            bookmarksResult = Result.failure(IllegalStateException("offline")),
        )
        val state = FileBookmarkState(repository) { testDevice(client) }

        state.load()

        assertTrue(state.bookmarks.isEmpty())
        assertFalse(state.sourceAvailable)
        assertEquals(deviceScope, state.activeScope)
        assertEquals(1L, repository.list(deviceScope).totalCount)
    }

    @Test
    fun remoteMutationsUseDeviceClientAndLeaveLocalDatabaseUntouched() = runTest {
        val client = RecordingBookmarkClient()
        val device = testDevice(client)
        val state = FileBookmarkState(repository) { device }
        val reordered = listOf(
            DrawerBookmark(2, AppStrings.ui_download, DrawerBookmarkType.Download, "/home/webb/Downloads", sort = 1),
            DrawerBookmark(1, AppStrings.ui_home_directory, DrawerBookmarkType.Home, "/home/webb", sort = 2),
        )

        assertTrue(state.add(AppStrings.ui_project, DrawerBookmarkType.Custom, "/home/webb/project", "icon").getOrThrow())
        assertTrue(
            state.update(7, AppStrings.ui_test_file_bookmark_state_project_2, DrawerBookmarkType.Document, "/home/webb/project2", null).getOrThrow(),
        )
        assertTrue(state.delete(7).getOrThrow())
        assertTrue(state.updateSort(reordered).getOrThrow())

        assertEquals(CreateCall(AppStrings.ui_project, "/home/webb/project", DrawerBookmarkType.Custom, "icon"), client.createCall)
        assertEquals(UpdateCall(7, AppStrings.ui_test_file_bookmark_state_project_2, "/home/webb/project2", DrawerBookmarkType.Document, ""), client.updateCall)
        assertEquals(7L, client.deletedId)
        assertEquals(listOf(2L, 1L), client.reorderedIds)
        assertEquals(0L, repository.list(BookmarkScope.of(FileProtocol.Device, device.id)).totalCount)
    }

    @Test
    fun localLoadAndMutationsStillUseLocalRepository() = runTest {
        val state = FileBookmarkState(repository) { Local() }

        assertTrue(state.add(AppStrings.ui_documentation, DrawerBookmarkType.Document, "/Users/test/Documents").getOrThrow())
        state.load()
        val created = state.bookmarks.single()

        assertEquals(AppStrings.ui_documentation, created.name)
        assertTrue(
            state.update(
                created.id,
                AppStrings.ui_test_index_template_data,
                DrawerBookmarkType.Document,
                "/Users/test/Documents",
            ).getOrThrow(),
        )
        assertTrue(state.delete(created.id).getOrThrow())
        assertEquals(0L, repository.list(BookmarkScope.Local).totalCount)
    }
}

private data class CreateCall(
    val name: String,
    val path: String,
    val type: DrawerBookmarkType,
    val icon: String,
)

private data class UpdateCall(
    val id: Long,
    val name: String,
    val path: String,
    val type: DrawerBookmarkType,
    val icon: String,
)

private class RecordingBookmarkClient(
    private val bookmarksResult: Result<List<DrawerBookmark>> = Result.success(emptyList()),
) : DeviceBookmarkClient {
    var getCount = 0
    var createCall: CreateCall? = null
    var updateCall: UpdateCall? = null
    var deletedId: Long? = null
    var reorderedIds: List<Long>? = null

    override suspend fun getBookmarks(): Result<List<DrawerBookmark>> {
        getCount++
        return bookmarksResult
    }

    override suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        createCall = CreateCall(name, path, iconType, iconPath)
        return Result.success(true)
    }

    override suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        updateCall = UpdateCall(id, name, path, iconType, iconPath)
        return Result.success(true)
    }

    override suspend fun deleteBookmark(id: Long): Result<Boolean> {
        deletedId = id
        return Result.success(true)
    }

    override suspend fun reorderBookmarks(orderedIds: List<Long>): Result<Boolean> {
        reorderedIds = orderedIds
        return Result.success(true)
    }
}

private fun testDevice(client: DeviceBookmarkClient): Device = Device(
    id = "linux",
    name = "webb-Linux",
    pathSeparator = "/",
    host = mutableMapOf(),
    type = DeviceType.JVM,
    token = "",
    bookmarkClient = client,
)

private fun createBookmarkStateDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase {
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val fileProtocolAdapter = bookmarkStateEnumAdapter<FileProtocol>()
    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = com.folderspan.db.Device.Adapter(typeAdapter = bookmarkStateEnumAdapter<DeviceType>()),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = bookmarkStateEnumAdapter<DeviceConnectType>(),
            categoryAdapter = bookmarkStateEnumAdapter<DeviceCategory>(),
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = bookmarkStateEnumAdapter<DrawerBookmarkType>(),
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = bookmarkStateEnumAdapter<FileFilterType>(),
            extensionsAdapter = bookmarkStateStringListAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = bookmarkStateEnumAdapter<DeviceConnectType>(),
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = bookmarkStateEnumAdapter<FileFilterSort>(),
            ignoreFilesAdapter = bookmarkStateStringListAdapter,
        ),
    )
}

private inline fun <reified T : Enum<T>> bookmarkStateEnumAdapter() = object : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
    override fun encode(value: T): String = value.name
}

private val bookmarkStateStringListAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.takeIf(String::isNotEmpty)?.split(',') ?: emptyList()

    override fun encode(value: List<String>): String = value.joinToString(",")
}
