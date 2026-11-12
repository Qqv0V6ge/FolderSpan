package com.folderspan.ui.state.device

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRoleStateTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createRoleDatabase(driver)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun deletionPreservesPermissionsAndRemainsDeletedAfterReload() = runBlocking {
        val state = loadedState()
        val role = state.roles.first()
        val permissions = database.devicePermissionQueries.select().executeAsListAwait()
        val mappings = database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait()

        state.delete(role, 0)
        state.refresh()

        assertNull(database.deviceRoleQueries.selectById(role.id).executeAsOneOrNullAwait())
        assertFalse(state.roles.any { it.id == role.id })
        assertEquals(permissions, database.devicePermissionQueries.select().executeAsListAwait())
        assertEquals(
            mappings.filter { it.deviceRoleId != role.id },
            database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait(),
        )
    }

    @Test
    fun failedDeletionKeepsRoleMappingsAndVisibleState() = runBlocking {
        val state = loadedState()
        val roles = state.roles.toList()
        val mappings = database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait()
        val permissions = database.devicePermissionQueries.select().executeAsListAwait()
        driver.execute(null, "CREATE TRIGGER reject_role_delete BEFORE DELETE ON DeviceRole BEGIN SELECT RAISE(ABORT, 'delete failed'); END", 0)

        assertFailsWith<Exception> { state.delete(roles.first(), 0) }

        assertEquals(roles, state.roles.toList())
        assertEquals(mappings, database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait())
        assertEquals(permissions, database.devicePermissionQueries.select().executeAsListAwait())
        assertTrue(database.deviceRoleQueries.selectById(roles.first().id).executeAsOneOrNullAwait() != null)
    }

    @Test
    fun deletionAwaitsAsynchronousTransactions() = runBlocking {
        val delegate = driver
        val asyncDriver = object : SqlDriver by delegate {
            override fun newTransaction() = QueryResult.AsyncValue { delegate.newTransaction().await() }
        }
        val state = loadedState(asyncDriver)
        val role = state.roles.first()
        val permissions = database.devicePermissionQueries.select().executeAsListAwait()

        state.delete(role, 0)

        assertNull(database.deviceRoleQueries.selectById(role.id).executeAsOneOrNullAwait())
        assertEquals(permissions, database.devicePermissionQueries.select().executeAsListAwait())
        assertTrue(database.deviceRoleDevicePermissionQueries.queryDevicePermissionIdByRoleId(role.id).executeAsListAwait().isEmpty())
    }

    private suspend fun loadedState(sqlDriver: SqlDriver = driver): DeviceRoleState {
        val state = DeviceRoleState(database, sqlDriver)
        withTimeout(10_000) {
            while (state.roles.size != 2) delay(10)
        }
        return state
    }
}

private fun createRoleDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase {
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val protocolAdapter = roleEnumAdapter<FileProtocol>()
    val stringListAdapter = object : ColumnAdapter<List<String>, String> {
        override fun decode(databaseValue: String): List<String> =
            databaseValue.takeIf(String::isNotEmpty)?.split(',') ?: emptyList()
        override fun encode(value: List<String>): String = value.joinToString(",")
    }
    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = roleEnumAdapter<DeviceType>()),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = roleEnumAdapter<DeviceConnectType>(),
            categoryAdapter = roleEnumAdapter<DeviceCategory>(),
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = roleEnumAdapter<DrawerBookmarkType>(),
            protocolAdapter = protocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = protocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = protocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = roleEnumAdapter<FileFilterType>(),
            extensionsAdapter = stringListAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = roleEnumAdapter<DeviceConnectType>(),
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = protocolAdapter,
            sortAdapter = roleEnumAdapter<FileFilterSort>(),
            ignoreFilesAdapter = stringListAdapter,
        ),
    )
}

private inline fun <reified T : Enum<T>> roleEnumAdapter() = object : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
    override fun encode(value: T): String = value.name
}
