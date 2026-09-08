package com.folderspan.service.bookmark

import com.folderspan.data.file.FileProtocol
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface DeviceBookmarkImportOutcome {
    data object AlreadyImported : DeviceBookmarkImportOutcome
    data class Imported(val count: Int) : DeviceBookmarkImportOutcome
}

class DeviceBookmarkImporter(
    private val database: FolderSpanDatabase,
    private val repository: ScopedBookmarkRepository,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val importMutex = Mutex()

    suspend fun importIfNeeded(
        deviceId: String,
        fetchRemoteBookmarks: suspend () -> Result<List<DrawerBookmark>>,
    ): Result<DeviceBookmarkImportOutcome> = runCatching {
        val scope = BookmarkScope.of(FileProtocol.Device, deviceId)
        importMutex.withLock {
            if (isImported(scope)) return@withLock DeviceBookmarkImportOutcome.AlreadyImported

            val remoteBookmarks = fetchRemoteBookmarks().getOrThrow()
                .sortedWith(compareBy<DrawerBookmark> { bookmark -> bookmark.sort }.thenBy { bookmark -> bookmark.id })
            remoteBookmarks.forEach { bookmark ->
                repository.create(
                    scope = scope,
                    input = ScopedBookmarkInput(
                        name = bookmark.name,
                        type = bookmark.type,
                        path = bookmark.path,
                        icon = bookmark.icon,
                    ),
                )
            }
            database.bookmarkScopeImportQueries.markImported(
                protocol = scope.protocol.name,
                sourceId = scope.sourceId,
                importedAt = nowMillis(),
            ).awaitDatabaseReady()
            DeviceBookmarkImportOutcome.Imported(remoteBookmarks.size)
        }
    }

    suspend fun isImported(deviceId: String): Boolean =
        isImported(BookmarkScope.of(FileProtocol.Device, deviceId))

    private suspend fun isImported(scope: BookmarkScope): Boolean =
        database.bookmarkScopeImportQueries.selectByScope(
            protocol = scope.protocol.name,
            sourceId = scope.sourceId,
        ).executeAsOneOrNullAwait() != null
}
