package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.FileRecent
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

internal fun supportsRecentTracking(file: FileSimpleInfo): Boolean {
    return file.protocol in setOf(
        com.folderspan.data.file.FileProtocol.Local,
        com.folderspan.data.file.FileProtocol.Share,
        com.folderspan.data.file.FileProtocol.Device,
        com.folderspan.data.file.FileProtocol.Network,
    )
}

class FileRecentState : KoinComponent {
    private val database by inject<FolderSpanDatabase>()

    private val _recents = MutableStateFlow<List<FileRecent>>(emptyList())
    val recents: StateFlow<List<FileRecent>> = _recents

    suspend fun loadAll() {
        _recents.value = withContext(Dispatchers.Default) {
            database.fileRecentQueries.selectAll().executeAsListAwait()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.Default) {
            database.fileRecentQueries.deleteAll().awaitDatabaseReady()
        }
        _recents.value = emptyList()
    }

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.Default) {
            database.fileRecentQueries.deleteByIds(ids).awaitDatabaseReady()
        }
        loadAll()
    }

    suspend fun record(file: FileSimpleInfo) {
        if (!supportsRecentTracking(file)) {
            return
        }
        val recents = withContext(Dispatchers.Default) {
            val now = Clock.System.now().toEpochMilliseconds()
            database.fileRecentQueries.upsert(
                name = file.name,
                isDirectory = file.isDirectory,
                path = file.path,
                mineType = file.mineType,
                size = file.size,
                createdDate = file.createdDate,
                updatedDate = file.updatedDate,
                protocol = file.protocol,
                protocolId = file.protocolId,
                lastAccessed = now,
            ).awaitDatabaseReady()
            database.fileRecentQueries.deleteExtras().awaitDatabaseReady()
            database.fileRecentQueries.selectAll().executeAsListAwait()
        }
        _recents.value = recents
    }
}
