package com.folderspan.utils

import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import strings.AppStrings

actual object BookmarkManager {
    private var nextId = 1L
    private val bookmarks = mutableListOf<DrawerBookmark>()

    actual fun getBookmarks(): List<DrawerBookmark> = bookmarks.toList()

    actual fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String
    ): Result<Boolean> {
        val bookmark = DrawerBookmark(
            id = nextId++,
            name = name,
            type = iconType,
            path = path,
            icon = iconPath.takeIf { item ->  item.isNotEmpty() }
        )
        bookmarks.add(bookmark)
        return Result.success(true)
    }

    actual fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String
    ): Result<Boolean> {
        val index = bookmarks.indexOfFirst { item ->  item.id == id }
        if (index == -1) return Result.failure(Exception(AppStrings.ui_bookmarks_do_not_exist))
        bookmarks[index] = bookmarks[index].copy(
            name = name,
            path = path,
            type = iconType,
            icon = iconPath.takeIf { item ->  item.isNotEmpty() }
        )
        return Result.success(true)
    }

    actual fun deleteBookmark(id: Long): Result<Boolean> {
        return Result.success(false)
    }

    actual fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean> {
        return try {
            this.bookmarks.clear()
            this.bookmarks.addAll(bookmarks)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun replaceBookmarks(bookmarks: List<DrawerBookmark>): Result<Boolean> {
        return try {
            this.bookmarks.clear()
            this.bookmarks.addAll(bookmarks.mapIndexed { index, item -> item.copy(id = (index + 1).toLong()) })
            nextId = this.bookmarks.size + 1L
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun resetToDefaults(): Result<Boolean> {
        bookmarks.clear()
        return Result.success(true)
    }
}
