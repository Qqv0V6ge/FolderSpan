package com.folderspan.utils

import com.folderspan.service.bookmark.*
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.service.webui.platformResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual object BookmarkManager : KoinComponent {
    private val repository by inject<ScopedBookmarkRepository>()
    private val database by inject<com.folderspan.db.FolderSpanDatabase>()
    actual suspend fun getBookmarks(): List<DrawerBookmark> = buildList {
        var offset = 0L
        do {
            val page = repository.list(BookmarkScope.Local, offset, ScopedBookmarkRepository.MAX_PAGE_SIZE)
            addAll(page.items.map { it.toDrawerBookmark() })
            offset += page.items.size
        } while (page.hasMore)
    }
    actual suspend fun createBookmark(name: String, path: String, iconType: DrawerBookmarkType, iconPath: String): Result<Boolean> = platformResult {
        repository.create(BookmarkScope.Local, ScopedBookmarkInput(name, iconType, path, iconPath.takeIf(String::isNotBlank)))
        true
    }
    actual suspend fun updateBookmark(id: Long, name: String, path: String, iconType: DrawerBookmarkType, iconPath: String): Result<Boolean> = platformResult {
        repository.update(BookmarkScope.Local, id, ScopedBookmarkInput(name, iconType, path, iconPath.takeIf(String::isNotBlank))) != null
    }
    actual suspend fun deleteBookmark(id: Long): Result<Boolean> = platformResult { repository.delete(BookmarkScope.Local, id) }
    actual suspend fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean> = platformResult {
        repository.reorder(BookmarkScope.Local, bookmarks.map { it.id })
    }
    actual suspend fun replaceBookmarks(bookmarks: List<DrawerBookmark>): Result<Boolean> = platformResult {
        require(bookmarks.all { it.name.isNotBlank() && it.path.isNotBlank() })
        database.fileBookmarkQueries.deleteAll().awaitDatabaseReady()
        bookmarks.forEachIndexed { index, item ->
            database.fileBookmarkQueries.insert(item.name, item.type, item.path, item.icon,
                item.sort.takeIf { it > 0 } ?: index + 1L).awaitDatabaseReady()
        }
        true
    }
    actual suspend fun resetToDefaults(): Result<Boolean> = replaceBookmarks(
        getPlatformDirectories().mapIndexed { index, item ->
            DrawerBookmark(name = item.title, type = DrawerBookmarkType.Custom, path = item.directory, sort = index.toLong())
        },
    )
}
