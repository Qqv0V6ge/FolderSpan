package com.folderspan.service.bookmark

import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType

interface DeviceBookmarkClient {
    suspend fun getBookmarks(): Result<List<DrawerBookmark>>

    suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean>

    suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean>

    suspend fun deleteBookmark(id: Long): Result<Boolean>

    suspend fun reorderBookmarks(orderedIds: List<Long>): Result<Boolean>
}
