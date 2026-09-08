package com.folderspan.service.mcp.auth

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTokenRepositoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createDatabase(driver)
    private var now = 1_000L
    private var randomCounter = 1
    private val repository = McpTokenRepository(
        database = database,
        nowMillis = { now++ },
        randomBytes = { size -> ByteArray(size) { randomCounter++.toByte() } },
    )

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun createAuthenticateAndRotateNeverPersistTheSecret() = runTest {
        val created = repository.create(
            name = "Automation",
            scopes = setOf(McpTokenScope.FilesRead, McpTokenScope.BookmarksRead),
        )
        val parsed = assertNotNull(parseMcpToken(created.token))
        val stored = assertNotNull(database.mcpTokenQueries.selectByLookupId(parsed.lookupId).executeAsOneOrNull())

        assertEquals("Automation", stored.name)
        assertEquals(64, stored.secretHash.length)
        assertFalse(created.token.contains(stored.secretHash))
        assertNotEquals(parsed.secretBytes.joinToString(), stored.secretHash)

        val principal = assertNotNull(repository.authenticate(created.token))
        assertEquals(setOf(McpTokenScope.BookmarksRead, McpTokenScope.FilesRead), principal.scopes)
        assertNotNull(repository.get(parsed.lookupId)?.lastUsedAt)
        assertNull(repository.authenticate(created.token.dropLast(1) + "0"))

        val rotated = assertNotNull(repository.rotate(parsed.lookupId))
        assertNotEquals(created.token, rotated.token)
        assertNull(repository.authenticate(created.token))
        assertNotNull(repository.authenticate(rotated.token))
    }

    @Test
    fun metadataScopesDisableAndDeleteFollowCrudContract() = runTest {
        val created = repository.create("Initial", setOf(McpTokenScope.FilesRead))
        val lookupId = created.record.lookupId

        assertTrue(repository.rename(lookupId, "Renamed"))
        assertTrue(repository.setScopes(lookupId, setOf(McpTokenScope.FilesWrite, McpTokenScope.FilesShare)))
        assertEquals("Renamed", repository.get(lookupId)?.name)
        assertEquals(
            setOf(McpTokenScope.FilesWrite, McpTokenScope.FilesShare),
            repository.get(lookupId)?.scopes,
        )

        assertTrue(repository.setEnabled(lookupId, false))
        assertNull(repository.authenticate(created.token))
        assertTrue(repository.setEnabled(lookupId, true))
        assertNotNull(repository.authenticate(created.token))

        assertTrue(repository.delete(lookupId))
        assertNull(repository.get(lookupId))
        assertFalse(repository.delete(lookupId))
    }
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
