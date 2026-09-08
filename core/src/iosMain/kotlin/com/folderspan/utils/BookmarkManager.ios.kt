package com.folderspan.utils

import com.folderspan.createSettings
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.toDrawerBookmark
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual object BookmarkManager : KoinComponent {
    private val database by inject<FolderSpanDatabase>()
    private val settings by lazy { createSettings() }

    actual fun getBookmarks(): List<DrawerBookmark> {
        val allBookmarks = database.fileBookmarkQueries.selectAll().executeAsList().map { item ->  item.toDrawerBookmark() }
        if (allBookmarks.isNotEmpty()) {
            return allBookmarks
        }

        val hasInitializedDefaults =
            settings.getBoolean(SettingsUtils.KEY_BOOKMARK_DEFAULT_INITIALIZED, false)

        if (!hasInitializedDefaults) {
            initializeDefaultBookmarks()
            settings.putBoolean(SettingsUtils.KEY_BOOKMARK_DEFAULT_INITIALIZED, true)
            return database.fileBookmarkQueries.selectAll().executeAsList().map { item ->  item.toDrawerBookmark() }
        }
        return allBookmarks
    }

    private fun initializeDefaultBookmarks() {
        val homePath = PathUtils.getHomePath()
        val separator = PathUtils.getPathSeparator()
        val defaultBookmarks = listOf(
            Triple("Home", homePath, DrawerBookmarkType.Home),
            Triple("Documents", "$homePath${separator}Documents", DrawerBookmarkType.Document),
            Triple("Downloads", "$homePath${separator}Downloads", DrawerBookmarkType.Download)
        )

        defaultBookmarks.forEachIndexed { index, (name, path, type) ->
            database.fileBookmarkQueries.insert(
                name = name,
                type = type,
                path = path,
                icon = null,
                sort = (index + 1).toLong()
            )
        }
    }

    actual fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String
    ): Result<Boolean> {
        return try {
            database.fileBookmarkQueries.insert(
                name = name,
                type = iconType,
                path = path,
                icon = iconPath.ifEmpty { null },
                sort = database.fileBookmarkQueries.selectAll().executeAsList().size + 1L
            )
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String
    ): Result<Boolean> {
        return try {
            val existingBookmark = database.fileBookmarkQueries.selectAll().executeAsList()
                .find { item ->  item.id == id } ?: return Result.failure(Exception("Bookmark not found"))

            database.fileBookmarkQueries.updateById(
                id = id,
                name = name,
                type = iconType,
                path = path,
                icon = iconPath.ifEmpty { null },
                sort = existingBookmark.sort
            )
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun deleteBookmark(id: Long): Result<Boolean> {
        return try {
            database.fileBookmarkQueries.deleteById(id)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean> {
        return try {
            bookmarks.forEachIndexed { index, bookmark ->
                database.fileBookmarkQueries.updateSort(
                    sort = (index + 1).toLong(),
                    id = bookmark.id
                )
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun replaceBookmarks(bookmarks: List<DrawerBookmark>): Result<Boolean> {
        return try {
            database.fileBookmarkQueries.deleteAll()
            bookmarks.forEachIndexed { index, bookmark ->
                database.fileBookmarkQueries.insert(
                    name = bookmark.name,
                    type = bookmark.type,
                    path = bookmark.path,
                    icon = bookmark.icon?.takeIf { item -> item.isNotEmpty() },
                    sort = bookmark.sort.takeIf { item -> item > 0L } ?: (index + 1L)
                )
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun resetToDefaults(): Result<Boolean> {
        return try {
            database.fileBookmarkQueries.selectAll().executeAsList().forEach { bookmark ->
                database.fileBookmarkQueries.deleteById(bookmark.id)
            }
            initializeDefaultBookmarks()
            settings.putBoolean(SettingsUtils.KEY_BOOKMARK_DEFAULT_INITIALIZED, true)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
