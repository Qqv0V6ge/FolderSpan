package com.folderspan.ui.state.file

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.db.FileBookmark
import com.folderspan.service.bookmark.BookmarkScope
import com.folderspan.service.bookmark.ScopedBookmarkInput
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.service.bookmark.toBookmarkScope
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

class FileBookmarkState(
    private val repository: ScopedBookmarkRepository,
    private val currentDesk: () -> DiskBase,
) {

    val bookmarks = mutableStateListOf<DrawerBookmark>()
    var activeScope by mutableStateOf(BookmarkScope.Local)
        private set
    var sourceAvailable by mutableStateOf(true)
        private set

    suspend fun load() {
        val desk = currentDesk()
        val scope = desk.toBookmarkScope()
        if (desk is Device) {
            desk.bookmarks.get()
                .onSuccess { remoteBookmarks ->
                    replaceBookmarks(scope, true, remoteBookmarks)
                }
                .onFailure {
                    replaceBookmarks(scope, false, emptyList())
                }
            return
        }
        val nextBookmarks = mutableListOf<DrawerBookmark>()
        var offset = 0L
        var available = true
        do {
            val page = repository.list(
                scope = scope,
                offset = offset,
                limit = ScopedBookmarkRepository.MAX_PAGE_SIZE,
            )
            available = page.sourceAvailable
            nextBookmarks += page.items.map { bookmark -> bookmark.toDrawerBookmark() }
            offset += page.items.size
        } while (page.hasMore)
        replaceBookmarks(scope, available, nextBookmarks)
    }

    private suspend fun replaceBookmarks(
        scope: BookmarkScope,
        available: Boolean,
        nextBookmarks: List<DrawerBookmark>,
    ) {
        withContext(Dispatchers.Main) {
            activeScope = scope
            sourceAvailable = available
            bookmarks.clear()
            bookmarks.addAll(nextBookmarks)
        }
    }

    suspend fun add(name: String, type: DrawerBookmarkType, path: String, icon: String? = null): Result<Boolean> {
        val desk = currentDesk()
        if (desk is Device) {
            return desk.bookmarks.create(
                name = name,
                path = path,
                iconType = type,
                iconPath = icon.orEmpty(),
            )
        }
        return runCatching {
            repository.create(
                scope = desk.toBookmarkScope(),
                input = ScopedBookmarkInput(name, type, path, icon),
            )
            SyncSnapshotChangeNotifier.onBookmarksChanged()
            true
        }
    }

    suspend fun update(
        id: Long,
        name: String,
        type: DrawerBookmarkType,
        path: String,
        icon: String? = null
    ): Result<Boolean> {
        val desk = currentDesk()
        if (desk is Device) {
            return desk.bookmarks.update(
                id = id,
                name = name,
                path = path,
                iconType = type,
                iconPath = icon.orEmpty(),
            )
        }
        return runCatching {
            val updated = repository.update(
                scope = desk.toBookmarkScope(),
                id = id,
                input = ScopedBookmarkInput(name, type, path, icon),
            ) ?: return@runCatching false
            SyncSnapshotChangeNotifier.onBookmarksChanged()
            updated.id == id
        }
    }

    suspend fun delete(id: Long): Result<Boolean> {
        val desk = currentDesk()
        if (desk is Device) {
            return desk.bookmarks.delete(id)
        }
        return runCatching {
            val deleted = repository.delete(desk.toBookmarkScope(), id)
            if (deleted) SyncSnapshotChangeNotifier.onBookmarksChanged()
            deleted
        }
    }

    suspend fun updateSort(bookmarks: List<DrawerBookmark>): Result<Boolean> {
        val desk = currentDesk()
        if (desk is Device) {
            return desk.bookmarks.reorder(bookmarks.map { bookmark -> bookmark.id })
        }
        return runCatching {
            val reordered = repository.reorder(
                scope = desk.toBookmarkScope(),
                orderedIds = bookmarks.map { bookmark -> bookmark.id },
            )
            if (reordered) SyncSnapshotChangeNotifier.onBookmarksChanged()
            reordered
        }
    }

}


enum class DrawerBookmarkType {
    Home,
    Image,
    Audio,
    Video,
    Document,
    Download,
    Custom
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DrawerBookmark(
    @ProtoNumber(1) val id: Long = 0L,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: DrawerBookmarkType,
    @ProtoNumber(4) val path: String,
    @ProtoNumber(5) val icon: String? = null,
    @ProtoNumber(6) val sort: Long = 0L,
) {
    /**
     * 将书签类型映射到对应的图标。
     *
     * 使用示例：`Icon(bookmark.icon(), null)`
     */
    fun icon(): ImageVector = when (type) {
        DrawerBookmarkType.Home -> Icons.Default.Home
        DrawerBookmarkType.Image -> Icons.Default.Image
        DrawerBookmarkType.Audio -> Icons.Default.Headphones
        DrawerBookmarkType.Video -> Icons.Default.Videocam
        DrawerBookmarkType.Document -> Icons.Default.Description
        DrawerBookmarkType.Download -> Icons.Default.Download
        DrawerBookmarkType.Custom -> Icons.Default.Bookmark
    }
}

// FileBookmark 和 DrawerBookmark 之间的转换扩展函数
fun FileBookmark.toDrawerBookmark(): DrawerBookmark {
    return DrawerBookmark(
        id = this.id,
        name = this.name,
        type = this.type,
        path = this.path,
        icon = this.icon,
        sort = this.sort
    )
}


fun DrawerBookmarkType.displayName(): String = when (this) {
    DrawerBookmarkType.Home -> AppStrings.ui_home_directory
    DrawerBookmarkType.Image -> AppStrings.ui_pictures
    DrawerBookmarkType.Audio -> AppStrings.ui_music
    DrawerBookmarkType.Video -> AppStrings.ui_video
    DrawerBookmarkType.Document -> AppStrings.ui_documentation
    DrawerBookmarkType.Download -> AppStrings.ui_download
    DrawerBookmarkType.Custom -> AppStrings.ui_others
}
