package com.folderspan.utils

import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType

expect object BookmarkManager {
    suspend fun getBookmarks(): List<DrawerBookmark>
    suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = ""
    ): Result<Boolean>
    suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = ""
    ): Result<Boolean>

    suspend fun deleteBookmark(id: Long): Result<Boolean>
    suspend fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean>

    suspend fun replaceBookmarks(bookmarks: List<DrawerBookmark>): Result<Boolean>

    suspend fun resetToDefaults(): Result<Boolean>
}
