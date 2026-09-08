package com.folderspan.utils

import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType

expect object BookmarkManager {
    fun getBookmarks(): List<DrawerBookmark>
    fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = ""
    ): Result<Boolean>
    fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = ""
    ): Result<Boolean>

    fun deleteBookmark(id: Long): Result<Boolean>
    fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean>
    fun replaceBookmarks(bookmarks: List<DrawerBookmark>): Result<Boolean>
    fun resetToDefaults(): Result<Boolean>
}
