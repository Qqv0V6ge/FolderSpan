package com.folderspan.pro.domain.usecase

import com.folderspan.createSettings
import com.folderspan.data.file.FileProtocol
import com.folderspan.db.FileFavorite
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.editor.EditorSearchHistoryEntry
import com.folderspan.editor.EditorSearchHistoryStore
import com.russhwolf.settings.Settings
import com.folderspan.pro.domain.model.SyncBookmarkSnapshotItem
import com.folderspan.pro.domain.model.SyncFavoriteSnapshotItem
import com.folderspan.pro.domain.model.SyncSnapshots
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.utils.BookmarkManager
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultSyncSnapshots(
    settings: Settings = createSettings(),
) : SyncSnapshots, KoinComponent {
    private val database by inject<FolderSpanDatabase>()
    private val editorSearchHistory = EditorSearchHistoryStore(settings)

    override suspend fun readBookmarks(): List<SyncBookmarkSnapshotItem> =
        withContext(Dispatchers.Default) {
            BookmarkManager.getBookmarks()
                .sortedWith(compareBy<DrawerBookmark> { item -> item.sort }.thenBy { item -> item.id })
                .map { item -> item.toSyncSnapshotItem() }
        }

    override suspend fun replaceBookmarks(items: List<SyncBookmarkSnapshotItem>) {
        withContext(Dispatchers.Default) {
            BookmarkManager.replaceBookmarks(items.mapIndexed { index, item -> item.toDrawerBookmark(index + 1L) })
        }
    }

    override suspend fun readFavorites(): List<SyncFavoriteSnapshotItem> =
        withContext(Dispatchers.Default) {
            database.fileFavoriteQueries.selectAll()
                .executeAsListAwait()
                .filter { item -> item.protocol != FileProtocol.Share }
                .map { item -> item.toSyncSnapshotItem() }
        }

    override suspend fun replaceFavorites(items: List<SyncFavoriteSnapshotItem>) {
        withContext(Dispatchers.Default) {
            database.fileFavoriteQueries.deleteAllExceptProtocol(FileProtocol.Share).awaitDatabaseReady()
            items
                .filter { item -> item.protocol != FileProtocol.Share }
                .forEach { item ->
                    database.fileFavoriteQueries.insert(
                        name = item.name,
                        isDirectory = item.isDirectory,
                        isFixed = item.isFixed,
                        path = item.path,
                        mineType = item.mineType,
                        size = item.size,
                        createdDate = item.createdDate,
                        updatedDate = item.updatedDate,
                        protocol = item.protocol,
                        protocolId = item.protocolId,
                    ).awaitDatabaseReady()
                }
        }
    }

    override suspend fun readEditorSearchHistory(): List<EditorSearchHistoryEntry> =
        editorSearchHistory.list()

    override suspend fun replaceEditorSearchHistory(items: List<EditorSearchHistoryEntry>) {
        editorSearchHistory.replace(items)
    }
}

private fun DrawerBookmark.toSyncSnapshotItem(): SyncBookmarkSnapshotItem =
    SyncBookmarkSnapshotItem(
        name = name,
        type = type,
        path = path,
        icon = icon,
        sort = sort,
    )

private fun SyncBookmarkSnapshotItem.toDrawerBookmark(sortFallback: Long): DrawerBookmark =
    DrawerBookmark(
        name = name,
        type = type,
        path = path,
        icon = icon,
        sort = sort.takeIf { it > 0L } ?: sortFallback,
    )

private fun FileFavorite.toSyncSnapshotItem(): SyncFavoriteSnapshotItem =
    SyncFavoriteSnapshotItem(
        name = name,
        isDirectory = isDirectory,
        isFixed = isFixed,
        path = path,
        mineType = mineType,
        size = size,
        createdDate = createdDate,
        updatedDate = updatedDate,
        protocol = protocol,
        protocolId = protocolId,
    )
