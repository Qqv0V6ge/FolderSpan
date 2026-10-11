package com.folderspan.service.bookmark

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.share.Share
import com.folderspan.db.FileBookmark
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlin.time.Clock

class BookmarkScope private constructor(
    val protocol: FileProtocol,
    val sourceId: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is BookmarkScope && protocol == other.protocol && sourceId == other.sourceId)

    override fun hashCode(): Int = 31 * protocol.hashCode() + sourceId.hashCode()

    override fun toString(): String = "BookmarkScope(protocol=$protocol, sourceId='$sourceId')"

    companion object {
        fun of(protocol: FileProtocol, sourceId: String = ""): BookmarkScope {
            val normalizedSourceId = if (protocol == FileProtocol.Local) "" else sourceId.trim()
            require(protocol == FileProtocol.Local || normalizedSourceId.isNotEmpty()) {
                "sourceId is required for $protocol bookmarks"
            }
            return BookmarkScope(protocol, normalizedSourceId)
        }

        val Local: BookmarkScope = BookmarkScope(FileProtocol.Local, "")
    }
}

fun DiskBase.toBookmarkScope(): BookmarkScope = when (this) {
    is Local -> BookmarkScope.Local
    is Device -> BookmarkScope.of(FileProtocol.Device, id)
    is Share -> BookmarkScope.of(FileProtocol.Share, id)
    is Network -> BookmarkScope.of(FileProtocol.Network, protocolId)
    else -> error("Unsupported bookmark source: ${this::class.simpleName}")
}

fun interface BookmarkSourceAvailability {
    suspend fun isAvailable(scope: BookmarkScope): Boolean
}

data class ScopedBookmark(
    val id: Long,
    val scope: BookmarkScope,
    val name: String,
    val type: DrawerBookmarkType,
    val path: String,
    val icon: String?,
    val sort: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceAvailable: Boolean,
) {
    fun toDrawerBookmark(): DrawerBookmark = DrawerBookmark(
        id = id,
        name = name,
        type = type,
        path = path,
        icon = icon,
        sort = sort,
    )
}

data class ScopedBookmarkPage(
    val items: List<ScopedBookmark>,
    val offset: Long,
    val limit: Long,
    val totalCount: Long,
    val sourceAvailable: Boolean,
) {
    val hasMore: Boolean get() = offset + items.size < totalCount
}

data class ScopedBookmarkInput(
    val name: String,
    val type: DrawerBookmarkType,
    val path: String,
    val icon: String? = null,
    val sort: Long? = null,
)

class ScopedBookmarkRepository(
    private val database: FolderSpanDatabase,
    private val sourceAvailability: BookmarkSourceAvailability = BookmarkSourceAvailability { scope ->
        scope.protocol == FileProtocol.Local
    },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun list(
        scope: BookmarkScope,
        offset: Long = 0,
        limit: Long = DEFAULT_PAGE_SIZE,
    ): ScopedBookmarkPage {
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        val available = sourceAvailability.isAvailable(scope)
        val items = database.fileBookmarkQueries.selectByScope(
            protocol = scope.protocol,
            sourceId = scope.sourceId,
            limit = limit,
            offset = offset,
        ).executeAsListAwait().map { bookmark -> bookmark.toScoped(scope, available) }
        val count = database.fileBookmarkQueries.countByScope(scope.protocol, scope.sourceId).executeAsOneAwait()
        return ScopedBookmarkPage(items, offset, limit, count, available)
    }

    suspend fun get(scope: BookmarkScope, id: Long): ScopedBookmark? {
        val available = sourceAvailability.isAvailable(scope)
        return database.fileBookmarkQueries.selectByScopedId(
            id = id,
            protocol = scope.protocol,
            sourceId = scope.sourceId,
        ).executeAsOneOrNullAwait()?.toScoped(scope, available)
    }

    suspend fun create(scope: BookmarkScope, input: ScopedBookmarkInput): ScopedBookmark {
        val normalized = input.normalized()
        val now = nowMillis()
        val sort = normalized.sort ?: database.fileBookmarkQueries.selectNextSortByScope(
            protocol = scope.protocol,
            sourceId = scope.sourceId,
        ).executeAsOneAwait()
        database.fileBookmarkQueries.insertScoped(
            name = normalized.name,
            type = normalized.type,
            path = normalized.path,
            icon = normalized.icon,
            sort = sort,
            protocol = scope.protocol,
            sourceId = scope.sourceId,
            createdAt = now,
            updatedAt = now,
        ).awaitDatabaseReady()
        val id = database.fileBookmarkQueries.selectLastInsertedId().executeAsOneAwait()
        return requireNotNull(get(scope, id)) { "Created bookmark $id was not found in its scope" }
    }

    suspend fun update(scope: BookmarkScope, id: Long, input: ScopedBookmarkInput): ScopedBookmark? {
        val current = get(scope, id) ?: return null
        val normalized = input.normalized()
        database.fileBookmarkQueries.updateScopedById(
            name = normalized.name,
            type = normalized.type,
            path = normalized.path,
            icon = normalized.icon,
            sort = normalized.sort ?: current.sort,
            updatedAt = nowMillis(),
            id = id,
            protocol = scope.protocol,
            sourceId = scope.sourceId,
        ).awaitDatabaseReady()
        return get(scope, id)
    }

    suspend fun delete(scope: BookmarkScope, id: Long): Boolean {
        if (get(scope, id) == null) return false
        database.fileBookmarkQueries.deleteScopedById(id, scope.protocol, scope.sourceId).awaitDatabaseReady()
        return true
    }

    suspend fun reorder(scope: BookmarkScope, orderedIds: List<Long>): Boolean {
        if (orderedIds.size != orderedIds.toSet().size) return false
        val currentIds = database.fileBookmarkQueries.selectIdsByScope(
            scope.protocol,
            scope.sourceId,
        ).executeAsListAwait()
        if (orderedIds.toSet() != currentIds.toSet()) return false
        val now = nowMillis()
        orderedIds.forEachIndexed { index, id ->
            database.fileBookmarkQueries.updateScopedSort(
                sort = index.toLong(),
                updatedAt = now,
                id = id,
                protocol = scope.protocol,
                sourceId = scope.sourceId,
            ).awaitDatabaseReady()
        }
        return true
    }

    private fun ScopedBookmarkInput.normalized(): ScopedBookmarkInput {
        val normalizedName = name.trim()
        val normalizedPath = path.trim()
        require(normalizedName.isNotEmpty()) { "Bookmark name must not be blank" }
        require(normalizedPath.isNotEmpty()) { "Bookmark path must not be blank" }
        require(sort == null || sort >= 0) { "Bookmark sort must not be negative" }
        return copy(
            name = normalizedName,
            path = normalizedPath,
            icon = icon?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50L
        const val MAX_PAGE_SIZE = 500L
    }
}

private fun FileBookmark.toScoped(scope: BookmarkScope, sourceAvailable: Boolean): ScopedBookmark = ScopedBookmark(
    id = id,
    scope = scope,
    name = name,
    type = type,
    path = path,
    icon = icon,
    sort = sort,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceAvailable = sourceAvailable,
)
