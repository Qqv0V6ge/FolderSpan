package com.folderspan.service.mcp.automation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.service.mcp.file.FileEndpointResolver
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class McpCatalogFacadeTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createMcpHttpTestDatabase(driver)
    private val facade = McpCatalogFacade(
        bookmarks = ScopedBookmarkRepository(database, nowMillis = { 100L }),
        database = database,
        resolver = FileEndpointResolver(),
    )

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun scopedBookmarksSupportCreateListUpdateAndDelete() = runTest {
        val created = facade.createBookmark(
            protocol = FileProtocol.Local,
            sourceId = "",
            name = "Home",
            type = "home",
            path = "/home",
            icon = null,
            sort = null,
        )
        assertEquals("Home", facade.listBookmarks(FileProtocol.Local, "", null, 10).items.single().name)

        val updated = facade.updateBookmark(
            protocol = FileProtocol.Local,
            sourceId = "",
            id = created.id,
            name = "Workspace",
            type = "document",
            path = "/workspace",
            icon = "folder",
            sort = 2,
        )
        assertEquals("Workspace", updated.name)
        assertEquals("document", updated.type)
        assertTrue(facade.deleteBookmark(FileProtocol.Local, "", created.id))
        assertTrue(facade.listBookmarks(FileProtocol.Local, "", null, 10).items.isEmpty())
    }

    @Test
    fun favoritesCanBePinnedAndRecentsCanBeDeletedOrCleared() = runTest {
        database.fileFavoriteQueries.insert(
            name = "alpha.txt",
            isDirectory = false,
            isFixed = false,
            path = "/alpha.txt",
            mineType = "text/plain",
            size = 10,
            createdDate = 1,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = null,
        )
        val favoriteId = database.fileFavoriteQueries.selectAll().executeAsList().single().id
        assertTrue(facade.pinFavorite(favoriteId, pinned = true).pinned)
        assertTrue(facade.listFavorites(null, 10).items.single().pinned)

        database.fileRecentQueries.upsert(
            name = "one.txt",
            isDirectory = false,
            path = "/one.txt",
            mineType = "text/plain",
            size = 1,
            createdDate = 1,
            updatedDate = 1,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 10,
        )
        database.fileRecentQueries.upsert(
            name = "two.txt",
            isDirectory = false,
            path = "/two.txt",
            mineType = "text/plain",
            size = 2,
            createdDate = 2,
            updatedDate = 2,
            protocol = FileProtocol.Local,
            protocolId = "",
            lastAccessed = 20,
        )
        val recents = facade.listRecents(null, 10).items
        assertEquals(listOf("two.txt", "one.txt"), recents.map { it.name })
        assertEquals(1, facade.deleteRecents(listOf(recents.first().id)))
        assertEquals(listOf("one.txt"), facade.listRecents(null, 10).items.map { it.name })
        assertEquals(1, facade.clearRecents())
        assertFalse(facade.listRecents(null, 10).items.isNotEmpty())
    }

    @Test
    fun networkListingNeverReturnsStoredCredentials() = runTest {
        val state = NetworkState(database)
        state.addNetwork(
            Network(
                name = "Private SFTP",
                pathSeparator = "/",
                protocol = NetworkProtocol.SFTP.name,
                host = "files.example.test",
                username = "alice",
                password = "must-not-leak",
            ),
            persist = false,
        )

        val response = McpNetworkFacade(state).list(cursor = null, limit = 10)
        val encoded = Json.encodeToString(response)
        assertEquals("Private SFTP", response.items.single().name)
        assertFalse(encoded.contains("must-not-leak"))
    }

    @Test
    fun syncFacadeListsTasksAndRejectsDuplicateActiveRun() = runTest {
        val runningTask = SyncTask(
            id = 7,
            name = "Documents mirror",
            sourceType = SyncEndpointType.Local,
            sourcePath = "/source",
            targetType = SyncEndpointType.Local,
            targetPath = "/target",
            lastStatus = SyncRunStatus.Running,
        )
        val state = SyncState(
            object : SyncTaskStore by EmptySyncTaskStore {
                override fun loadTasks(): List<SyncTask> = listOf(runningTask)
            },
        )
        state.tasks.clear()
        state.tasks += runningTask
        val facade = McpSyncFacade(state)

        assertEquals("Documents mirror", facade.list(null, 10).items.single().name)
        val failure = assertFailsWith<McpAutomationException> { facade.run(7) }
        assertEquals("conflict", failure.code)
    }
}

private object EmptySyncTaskStore : SyncTaskStore {
    override fun loadTasks(): List<SyncTask> = emptyList()
    override fun saveTask(task: SyncTask): SyncTask = task
    override fun deleteTask(taskId: Long) = Unit
    override fun appendRun(record: SyncRunRecord) = Unit
    override fun loadRuns(taskId: Long): List<SyncRunRecord> = emptyList()
    override fun deleteRuns(taskId: Long) = Unit
}
