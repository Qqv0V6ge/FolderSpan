package com.folderspan.pro.domain.usecase

import strings.AppStrings

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
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
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.pro.domain.model.SyncBookmarkSnapshotItem
import com.folderspan.pro.domain.model.SyncFavoriteSnapshotItem
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSyncSnapshotsTest {
    @Test
    fun replaceBookmarksRewritesLocalBookmarkSnapshot() = runTest {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            val snapshots = DefaultSyncSnapshots()

            snapshots.replaceBookmarks(
                listOf(
                    SyncBookmarkSnapshotItem(
                        name = AppStrings.ui_pictures,
                        type = DrawerBookmarkType.Image,
                        path = "/pictures",
                        icon = null,
                        sort = 1L,
                    ),
                    SyncBookmarkSnapshotItem(
                        name = AppStrings.ui_download,
                        type = DrawerBookmarkType.Download,
                        path = "/downloads",
                        icon = null,
                        sort = 2L,
                    ),
                ),
            )

            assertEquals(
                listOf("/pictures", "/downloads"),
                snapshots.readBookmarks().map { item -> item.path },
            )
        }
    }

    @Test
    fun replaceFavoritesRewritesLocalFavoriteSnapshotAndSkipsShareProtocol() = runTest {
        val database = createInMemoryDatabase()
        withTestKoin(database) {
            database.fileFavoriteQueries.insert(
                name = AppStrings.ui_test_default_sync_snapshots_already_shared,
                isDirectory = false,
                isFixed = false,
                path = "/share/existing.txt",
                mineType = "text/plain",
                size = 2L,
                createdDate = 1L,
                updatedDate = 2L,
                protocol = FileProtocol.Share,
                protocolId = "share-existing",
            )
            val snapshots = DefaultSyncSnapshots()

            snapshots.replaceFavorites(
                listOf(
                    SyncFavoriteSnapshotItem(
                        name = AppStrings.ui_documentation,
                        isDirectory = false,
                        isFixed = false,
                        path = "/docs/a.txt",
                        mineType = "text/plain",
                        size = 12L,
                        createdDate = 1L,
                        updatedDate = 2L,
                        protocol = FileProtocol.Local,
                        protocolId = null,
                    ),
                    SyncFavoriteSnapshotItem(
                        name = AppStrings.ui_test_default_sync_snapshots_share_files,
                        isDirectory = false,
                        isFixed = false,
                        path = "/share/a.txt",
                        mineType = "text/plain",
                        size = 1L,
                        createdDate = 1L,
                        updatedDate = 2L,
                        protocol = FileProtocol.Share,
                        protocolId = "share-1",
                    ),
                ),
            )

            assertEquals(
                listOf("/docs/a.txt"),
                snapshots.readFavorites().map { item -> item.path },
            )
            assertEquals(
                listOf("/docs/a.txt", "/share/existing.txt"),
                database.fileFavoriteQueries.selectAll().executeAsList().map { item -> item.path }.sorted(),
            )
        }
    }
}

private suspend fun withTestKoin(
    database: FolderSpanDatabase,
    block: suspend () -> Unit,
) {
    stopKoin()
    startKoin {
        modules(
            module {
                single { database }
            },
        )
    }
    try {
        block()
    } finally {
        stopKoin()
    }
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
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(connectionTypeAdapter = deviceConnectTypeAdapter),
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
