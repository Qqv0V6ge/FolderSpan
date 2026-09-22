package com.folderspan.service.mcp.automation

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.exception.AuthorityException
import com.folderspan.service.bookmark.BookmarkScope
import com.folderspan.service.bookmark.ScopedBookmark
import com.folderspan.service.bookmark.ScopedBookmarkInput
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.mcp.file.FileConflictPolicy
import com.folderspan.service.mcp.file.FileContentReader
import com.folderspan.service.mcp.file.FileContentWriter
import com.folderspan.service.mcp.file.FileEndpointEntry
import com.folderspan.service.mcp.file.FileEndpointErrorCode
import com.folderspan.service.mcp.file.FileEndpointException
import com.folderspan.service.mcp.file.FileEndpointResolver
import com.folderspan.service.mcp.file.FileGatewayTaskSubmitter
import com.folderspan.service.mcp.file.FileLocator
import com.folderspan.service.mcp.file.FileContentEncoding
import com.folderspan.service.mcp.file.FileReadResult
import com.folderspan.service.mcp.file.FileWriteMode
import com.folderspan.service.mcp.file.FileWriteResult
import com.folderspan.service.http.server.HttpShareFileServerInterface
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.SyncRunStatus
import com.folderspan.ui.state.main.SyncState
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.io.encoding.Base64

@Serializable
data class McpPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

object McpOpaqueCursor {
    private const val VERSION = "v1"
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    fun offset(cursor: String?): Int {
        if (cursor.isNullOrBlank()) return 0
        val decoded = runCatching { Base64.UrlSafe.decode(cursor).decodeToString() }.getOrNull()
            ?: throw McpAutomationException("invalid_argument", "cursor is invalid")
        val parts = decoded.split(':')
        if (parts.size != 2 || parts[0] != VERSION) {
            throw McpAutomationException("invalid_argument", "cursor is invalid")
        }
        return parts[1].toIntOrNull()?.takeIf { item -> item >= 0 }
            ?: throw McpAutomationException("invalid_argument", "cursor is invalid")
    }

    fun limit(value: Int?): Int {
        val limit = value ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) {
            throw McpAutomationException("invalid_argument", "limit must be between 1 and $MAX_LIMIT")
        }
        return limit
    }

    fun next(offset: Int, returned: Int, total: Int): String? =
        if (offset + returned >= total) null
        else Base64.UrlSafe.encode("$VERSION:${offset + returned}".encodeToByteArray())

    fun <T> page(items: List<T>, cursor: String?, limit: Int?): McpPage<T> {
        val offset = offset(cursor)
        val pageLimit = limit(limit)
        if (offset > items.size) throw McpAutomationException("invalid_argument", "cursor is out of range")
        val page = items.drop(offset).take(pageLimit)
        return McpPage(page, next(offset, page.size, items.size))
    }
}

class McpAutomationException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun resolveMcpLinkShareAllowUpload(
    requestedAllowUpload: Boolean,
    hostAllowUpload: Boolean,
): Boolean {
    if (!requestedAllowUpload) return false
    if (!hostAllowUpload) {
        throw McpAutomationException(
            "permission_denied",
            "writable link share requires host upload to be enabled",
        )
    }
    return true
}

internal fun Throwable.toMcpAutomationException(): McpAutomationException = when (this) {
    is McpAutomationException -> this
    is FileEndpointException -> McpAutomationException(code.wireValue, message.orEmpty(), this)
    is AuthorityException -> McpAutomationException("permission_denied", "local filesystem access is denied", this)
    is IllegalArgumentException -> McpAutomationException("invalid_argument", message ?: "invalid argument", this)
    is NoSuchElementException -> McpAutomationException("not_found", "requested item was not found", this)
    else -> McpAutomationException("internal_error", "operation failed", this)
}

@Serializable
data class McpBookmarkDto(
    val id: Long,
    val protocol: FileProtocol,
    val sourceId: String,
    val name: String,
    val type: String,
    val path: String,
    val icon: String? = null,
    val sort: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceAvailable: Boolean,
)

@Serializable
data class McpFavoriteDto(
    val id: Long,
    val locator: FileLocator,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
)

@Serializable
data class McpRecentDto(
    val id: Long,
    val locator: FileLocator,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessed: Long,
)

class McpCatalogFacade(
    private val bookmarks: ScopedBookmarkRepository,
    private val database: FolderSpanDatabase,
    private val resolver: FileEndpointResolver,
) {
    suspend fun listBookmarks(
        protocol: FileProtocol,
        sourceId: String,
        cursor: String?,
        limit: Int?,
    ): McpPage<McpBookmarkDto> = catchingSuspend {
        val scope = BookmarkScope.of(protocol, sourceId)
        val offset = McpOpaqueCursor.offset(cursor)
        val pageLimit = McpOpaqueCursor.limit(limit)
        val page = bookmarks.list(scope, offset.toLong(), pageLimit.toLong())
        McpPage(
            items = page.items.map(ScopedBookmark::toMcpDto),
            nextCursor = McpOpaqueCursor.next(offset, page.items.size, page.totalCount.toInt()),
        )
    }

    suspend fun createBookmark(
        protocol: FileProtocol,
        sourceId: String,
        name: String,
        type: String,
        path: String,
        icon: String?,
        sort: Long?,
    ): McpBookmarkDto = catchingSuspend {
        bookmarks.create(
            BookmarkScope.of(protocol, sourceId),
            ScopedBookmarkInput(name, parseBookmarkType(type), path, icon, sort),
        ).toMcpDto().also { SyncSnapshotChangeNotifier.onBookmarksChanged() }
    }

    suspend fun updateBookmark(
        protocol: FileProtocol,
        sourceId: String,
        id: Long,
        name: String,
        type: String,
        path: String,
        icon: String?,
        sort: Long?,
    ): McpBookmarkDto = catchingSuspend {
        bookmarks.update(
            BookmarkScope.of(protocol, sourceId),
            id,
            ScopedBookmarkInput(name, parseBookmarkType(type), path, icon, sort),
        )?.toMcpDto()?.also { SyncSnapshotChangeNotifier.onBookmarksChanged() }
            ?: throw NoSuchElementException()
    }

    suspend fun deleteBookmark(protocol: FileProtocol, sourceId: String, id: Long): Boolean = catchingSuspend {
        val deleted = bookmarks.delete(BookmarkScope.of(protocol, sourceId), id)
        if (!deleted) throw NoSuchElementException()
        SyncSnapshotChangeNotifier.onBookmarksChanged()
        true
    }

    suspend fun listFavorites(cursor: String?, limit: Int?): McpPage<McpFavoriteDto> = catchingSuspend {
        McpOpaqueCursor.page(
            database.fileFavoriteQueries.selectAll().executeAsListAwait().map(FileFavorite::toMcpDto),
            cursor,
            limit,
        )
    }

    suspend fun addFavorite(locator: FileLocator): McpFavoriteDto = catchingSuspend {
        val (gateway, path) = resolver.resolve(locator)
        val entry = gateway.info(path).getOrThrow()
        findFavorite(entry)?.let { return@catchingSuspend it.toMcpDto() }
        database.fileFavoriteQueries.insert(
            name = entry.name,
            isDirectory = entry.isDirectory,
            isFixed = false,
            path = entry.path,
            mineType = entry.mimeType,
            size = entry.size,
            createdDate = entry.createdAt,
            updatedDate = entry.updatedAt,
            protocol = entry.endpoint.protocol,
            protocolId = entry.endpoint.sourceId,
        ).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onFavoritesChanged()
        requireNotNull(findFavorite(entry)).toMcpDto()
    }

    suspend fun removeFavorite(locator: FileLocator): Boolean = catchingSuspend {
        val (gateway, path) = resolver.resolve(locator)
        val entry = gateway.info(path).getOrThrow()
        val favorite = findFavorite(entry) ?: throw NoSuchElementException()
        database.fileFavoriteQueries.deleteById(favorite.id).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onFavoritesChanged()
        true
    }

    suspend fun pinFavorite(id: Long, pinned: Boolean): McpFavoriteDto = catchingSuspend {
        val favorite = database.fileFavoriteQueries.selectAll().executeAsListAwait()
            .firstOrNull { item -> item.id == id } ?: throw NoSuchElementException()
        database.fileFavoriteQueries.updateIsFixedById(pinned, id).awaitDatabaseReady()
        SyncSnapshotChangeNotifier.onFavoritesChanged()
        favorite.copy(isFixed = pinned).toMcpDto()
    }

    suspend fun listRecents(cursor: String?, limit: Int?): McpPage<McpRecentDto> = catchingSuspend {
        McpOpaqueCursor.page(
            database.fileRecentQueries.selectAll().executeAsListAwait().map(FileRecent::toMcpDto),
            cursor,
            limit,
        )
    }

    suspend fun deleteRecents(ids: List<Long>): Int = catchingSuspend {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        val existing = database.fileRecentQueries.selectAll().executeAsListAwait()
            .count { item -> item.id in ids }
        database.fileRecentQueries.deleteByIds(ids.distinct()).awaitDatabaseReady()
        existing
    }

    suspend fun clearRecents(): Int = catchingSuspend {
        val count = database.fileRecentQueries.selectAll().executeAsListAwait().size
        database.fileRecentQueries.deleteAll().awaitDatabaseReady()
        count
    }

    private suspend fun findFavorite(entry: FileEndpointEntry): FileFavorite? {
        val id = database.fileFavoriteQueries.queryByPathProtocol(
            entry.path,
            entry.endpoint.protocol,
            entry.endpoint.sourceId,
        ).executeAsOneOrNullAwait() ?: return null
        return database.fileFavoriteQueries.selectAll().executeAsListAwait().firstOrNull { item -> item.id == id }
    }
}

@Serializable
data class McpTaskDto(
    val id: Long,
    val type: String,
    val status: String,
    val protocol: FileProtocol,
    val sourceId: String,
    val values: Map<String, String>,
    val progressCurrent: Long? = null,
    val progressTotal: Long? = null,
    val failureCount: Int,
    val failurePath: String? = null,
    val failureMessage: String? = null,
    val availableActions: List<String>,
)

class McpTaskFacade(private val tasks: TaskState) {
    fun list(cursor: String?, limit: Int?): McpPage<McpTaskDto> = catchingSync {
        McpOpaqueCursor.page(tasks.snapshotTasks().sortedByDescending { item -> item.key }.map(::snapshot), cursor, limit)
    }

    fun get(id: Long): McpTaskDto = catchingSync {
        snapshot(tasks.getTask(id) ?: throw NoSuchElementException())
    }

    fun pause(id: Long): McpTaskDto = control(id, "pause", setOf(StatusEnum.LOADING)) { task ->
        tasks.requestPause(task)
    }

    fun resume(id: Long): McpTaskDto = control(id, "resume", setOf(StatusEnum.PAUSE)) { task ->
        tasks.requestResume(task)
    }

    fun cancel(id: Long): McpTaskDto = control(id, "cancel", setOf(StatusEnum.LOADING, StatusEnum.PAUSE)) { task ->
        tasks.requestCancel(task, "MCP cancellation requested")
    }

    fun delete(id: Long): Boolean = catchingSync {
        val task = tasks.getTask(id) ?: throw NoSuchElementException()
        if (task.status !in setOf(StatusEnum.SUCCESS, StatusEnum.FAILURE)) {
            throw McpAutomationException("invalid_state", "only terminal tasks can be deleted")
        }
        tasks.delete(task)
    }

    private fun control(
        id: Long,
        action: String,
        allowed: Set<StatusEnum>,
        invoke: (Task) -> Unit,
    ): McpTaskDto = catchingSync {
        val task = tasks.getTask(id) ?: throw NoSuchElementException()
        if (task.status !in allowed) throw McpAutomationException("invalid_state", "$action is not valid for this task")
        invoke(task)
        snapshot(tasks.getTask(id) ?: task)
    }

    private fun snapshot(task: Task): McpTaskDto {
        val failure = tasks.getFailureSummarySnapshot(task)
        return McpTaskDto(
            id = task.key,
            type = task.taskType.name.lowercase(),
            status = task.status.name.lowercase(),
            protocol = task.protocol,
            sourceId = task.protocolId,
            values = task.values.filterKeys { key -> !key.contains("token", ignoreCase = true) && !key.contains("password", ignoreCase = true) },
            progressCurrent = task.values["__overall_progress_cur"]?.toLongOrNull()
                ?: task.values["progressCur"]?.toLongOrNull(),
            progressTotal = task.values["__overall_progress_max"]?.toLongOrNull()
                ?: task.values["progressMax"]?.toLongOrNull(),
            failureCount = tasks.getFailureCountSnapshot(task),
            failurePath = failure?.first,
            failureMessage = failure?.second,
            availableActions = when (task.status) {
                StatusEnum.LOADING -> listOf("pause", "cancel")
                StatusEnum.PAUSE -> listOf("resume", "cancel")
                StatusEnum.SUCCESS, StatusEnum.FAILURE -> listOf("delete")
            },
        )
    }
}

@Serializable
data class McpDeviceDto(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val platform: String,
    val transport: String,
    val status: String,
)

@Serializable
data class McpDeviceGroups(
    val connected: List<McpDeviceDto>,
    val connecting: List<McpDeviceDto>,
    val approvalRequired: List<McpDeviceDto>,
    val discovered: List<McpDeviceDto>,
)

class McpDeviceFacade(
    private val state: DeviceState,
) {
    suspend fun list(transport: DeviceTransportType): McpDeviceGroups = catchingSuspend {
        groupOnlineDevices(
            devices = state.snapshotSocketDevices(transport),
            approvalRequiredIds = state.snapshotConnectionRequests().keys,
            transport = transport,
        )
    }

    suspend fun connect(id: String, transport: DeviceTransportType): McpDeviceDto = catchingSuspend {
        state.connectDiscoveredDevice(id, transport).toMcpDto(
            state.snapshotConnectionRequests().containsKey(id),
        )
    }
}

internal fun groupOnlineDevices(
    devices: List<SocketDevice>,
    approvalRequiredIds: Set<String>,
    transport: DeviceTransportType,
): McpDeviceGroups {
    val online = devices
        .filter { item ->
            item.transportType == transport &&
                item.connectType != ConnectType.Fail &&
                item.connectType != ConnectType.Rejected
        }
        .distinctBy { item -> item.id }
        .map { item -> item.toMcpDto(item.id in approvalRequiredIds) }
    return McpDeviceGroups(
        connected = online.filter { item -> item.status == "connected" },
        connecting = online.filter { item -> item.status == "connecting" },
        approvalRequired = online.filter { item -> item.status == "approval_required" },
        discovered = online.filter { item -> item.status == "discovered" },
    )
}

@Serializable
data class McpNetworkDto(
    val id: Long,
    val sourceId: String,
    val name: String,
    val protocol: String,
    val host: String,
    val connected: Boolean,
    val persisted: Boolean,
)

class McpNetworkFacade(private val state: NetworkState) {
    suspend fun list(cursor: String?, limit: Int?): McpPage<McpNetworkDto> = catchingSuspend {
        state.loadPersisted()
        val connected = state.snapshotConnectedEntryIds()
        McpOpaqueCursor.page(
            state.snapshotEntries().map { entry ->
                McpNetworkDto(
                    id = entry.id,
                    sourceId = entry.network.protocolId,
                    name = entry.network.name,
                    protocol = entry.network.protocol,
                    host = entry.network.host,
                    connected = entry.id in connected,
                    persisted = entry.isPersisted,
                )
            },
            cursor,
            limit,
        )
    }

    suspend fun connect(id: Long): McpNetworkDto = catchingSuspend {
        val entry = state.connectValidated(id).getOrElse { error ->
            throw McpAutomationException("not_connected", "network connection failed", error)
        }
        McpNetworkDto(
            id = entry.id,
            sourceId = entry.network.protocolId,
            name = entry.network.name,
            protocol = entry.network.protocol,
            host = entry.network.host,
            connected = true,
            persisted = entry.isPersisted,
        )
    }
}

@Serializable
data class McpSyncTaskDto(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val sourceType: String,
    val sourceRef: String,
    val sourcePath: String,
    val targetType: String,
    val targetRef: String,
    val targetPath: String,
    val lastStatus: String,
    val lastRunAt: Long,
    val nextRunAt: Long,
)

class McpSyncFacade(private val state: SyncState) {
    fun list(cursor: String?, limit: Int?): McpPage<McpSyncTaskDto> = catchingSync {
        McpOpaqueCursor.page(state.snapshotTasks().map(SyncTask::toMcpDto), cursor, limit)
    }

    fun run(id: Long): McpSyncTaskDto = catchingSync {
        val current = state.getTask(id) ?: throw NoSuchElementException()
        if (current.lastStatus == SyncRunStatus.Running || current.lastStatus == SyncRunStatus.Queued) {
            throw McpAutomationException("conflict", "sync task is already active")
        }
        state.runNowIfIdle(id)?.toMcpDto()
            ?: throw McpAutomationException("conflict", "sync task is already active")
    }
}

class McpFileFacade(
    private val resolver: FileEndpointResolver,
    private val reader: FileContentReader,
    private val submitter: FileGatewayTaskSubmitter,
    private val sharing: McpFileSharing? = null,
    private val writer: FileContentWriter = FileContentWriter(resolver),
) {
    suspend fun list(locator: FileLocator, cursor: String?, limit: Int?): McpPage<FileEndpointEntry> = catchingSuspend {
        val (gateway, path) = resolver.resolve(locator)
        if (!gateway.permissions.read) throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint does not allow reading")
        McpOpaqueCursor.page(gateway.list(path).getOrThrow(), cursor, limit)
    }

    suspend fun info(locator: FileLocator): FileEndpointEntry = catchingSuspend {
        val (gateway, path) = resolver.resolve(locator)
        if (!gateway.permissions.read) throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint does not allow reading")
        gateway.info(path).getOrThrow()
    }

    suspend fun read(
        locator: FileLocator,
        offset: Long,
        length: Int,
        encoding: FileContentEncoding,
    ): FileReadResult = catchingSuspend { reader.read(locator, offset, length, encoding).getOrThrow() }

    suspend fun write(
        locator: FileLocator,
        data: String,
        encoding: FileContentEncoding,
        mode: FileWriteMode,
        expectedSize: Long?,
        expectedUpdatedAt: Long?,
    ): FileWriteResult = catchingSuspend {
        writer.write(locator, data, encoding, mode, expectedSize, expectedUpdatedAt).getOrThrow()
    }

    suspend fun createDirectory(locator: FileLocator): FileEndpointEntry = catchingSuspend {
        writer.createDirectory(locator).getOrThrow()
    }

    suspend fun rename(locator: FileLocator, newName: String): FileEndpointEntry = catchingSuspend {
        val (gateway, path) = resolver.resolve(locator)
        if (!gateway.permissions.rename) throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint does not allow rename")
        gateway.rename(path, newName).getOrThrow()
    }

    fun copy(sources: List<FileLocator>, target: FileLocator, policy: FileConflictPolicy): Long = catchingSync {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        submitter.submitCopy(sources, target, policy)
    }

    fun move(sources: List<FileLocator>, target: FileLocator, policy: FileConflictPolicy): Long = catchingSync {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        submitter.submitMove(sources, target, policy)
    }

    fun delete(sources: List<FileLocator>): Long = catchingSync {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        submitter.submitDelete(sources)
    }

    suspend fun shareLink(
        locators: List<FileLocator>,
        allowHidden: Boolean,
        allowUpload: Boolean,
    ): McpLinkShareDto = catchingSuspend {
        (sharing ?: throw McpAutomationException("unsupported", "link sharing is unavailable"))
            .shareLink(locators, allowHidden, allowUpload)
    }

    suspend fun shareDevice(
        locators: List<FileLocator>,
        deviceId: String,
        allowHidden: Boolean,
    ): McpDeviceShareDto = catchingSuspend {
        (sharing ?: throw McpAutomationException("unsupported", "device sharing is unavailable"))
            .shareDevice(locators, deviceId, allowHidden)
    }
}

fun interface McpShareAddressProvider {
    fun addresses(port: Int): List<String>
}

@Serializable
data class McpLinkShareDto(
    val urls: List<String>,
    val expiresAt: Long,
    val allowHidden: Boolean,
    val allowUpload: Boolean,
    val fileCount: Int,
    val tlsFingerprintSha256: String,
)

@Serializable
data class McpDeviceShareDto(
    val operationId: String,
    val deviceId: String,
    val status: String,
    val fileCount: Int,
)

interface McpFileSharing {
    suspend fun shareLink(
        locators: List<FileLocator>,
        allowHidden: Boolean,
        allowUpload: Boolean,
    ): McpLinkShareDto

    suspend fun shareDevice(
        locators: List<FileLocator>,
        deviceId: String,
        allowHidden: Boolean,
    ): McpDeviceShareDto
}

class McpFileSharingFacade(
    private val resolver: FileEndpointResolver,
    private val shareState: FileShareState,
    private val deviceState: DeviceState,
    private val shareServer: HttpShareFileServerInterface,
    private val addressProvider: McpShareAddressProvider,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : McpFileSharing {
    override suspend fun shareLink(
        locators: List<FileLocator>,
        allowHidden: Boolean,
        allowUpload: Boolean,
    ): McpLinkShareDto {
        val files = resolveShareFiles(locators)
        val filtered = shareState.filterShareableFiles(files)
        if (filtered.size != files.size) {
            throw McpAutomationException("permission_denied", "one or more endpoints do not allow link sharing")
        }
        val effectiveAllowUpload = resolveMcpLinkShareAllowUpload(allowUpload, shareState.allowUpload.value)
        val port = SettingsUtils.easyFileShare.getPort()
        if (!shareServer.isRunning()) shareServer.start(port)
        if (!shareServer.isRunning()) throw McpAutomationException("not_connected", "link sharing service failed to start")
        val ticket = shareState.issueLinkShareTicket(allowHidden, effectiveAllowUpload, filtered, nowMillis())
        val urls = addressProvider.addresses(port)
            .flatMap { host -> listOf("https://$host:$port/?ticket=${ticket.token}", "http://$host:$port/?ticket=${ticket.token}") }
            .distinct()
        if (urls.isEmpty()) throw McpAutomationException("not_connected", "no LAN address is available")
        return McpLinkShareDto(
            urls = urls,
            expiresAt = ticket.expiresAtMillis,
            allowHidden = allowHidden,
            allowUpload = effectiveAllowUpload,
            fileCount = filtered.size,
            tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
        )
    }

    override suspend fun shareDevice(
        locators: List<FileLocator>,
        deviceId: String,
        allowHidden: Boolean,
    ): McpDeviceShareDto {
        val files = resolveShareFiles(locators)
        val device = deviceState.snapshotSocketDevices(DeviceTransportType.Session)
            .firstOrNull { item -> item.id == deviceId && item.connectType == ConnectType.Connect }
            ?: throw McpAutomationException("not_connected", "target device is not online and connected")
        shareState.shareToDevices[deviceId] = allowHidden to files
        deviceState.share(device)
        return McpDeviceShareDto(
            operationId = "share_${nowMillis().toString(16)}_$deviceId",
            deviceId = deviceId,
            status = "accepted",
            fileCount = files.size,
        )
    }

    private suspend fun resolveShareFiles(locators: List<FileLocator>): List<FileSimpleInfo> {
        require(locators.isNotEmpty()) { "locators must not be empty" }
        return locators.map { locator ->
            val (gateway, path) = resolver.resolve(locator)
            if (!gateway.permissions.share) {
                throw McpAutomationException("permission_denied", "endpoint does not allow sharing")
            }
            val entry = gateway.info(path).getOrThrow()
            if (entry.isSymbolicLink) {
                throw McpAutomationException("permission_denied", "symbolic links cannot be shared")
            }
            FileSimpleInfo(
                name = entry.name,
                description = "",
                isDirectory = entry.isDirectory,
                isHidden = entry.isHidden,
                path = entry.path,
                mineType = entry.mimeType,
                size = entry.size,
                createdDate = entry.createdAt,
                updatedDate = entry.updatedAt,
                protocol = entry.endpoint.protocol,
                protocolId = entry.endpoint.sourceId,
                isSymbolicLink = entry.isSymbolicLink,
                isSymbolicLinkKnown = entry.isSymbolicLinkKnown,
            )
        }
    }
}

class McpAutomationFacade(
    val catalog: McpCatalogFacade,
    val task: McpTaskFacade,
    val device: McpDeviceFacade,
    val network: McpNetworkFacade,
    val sync: McpSyncFacade,
    val file: McpFileFacade,
)

private inline fun <T> catchingSync(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.toMcpAutomationException()
}

private suspend inline fun <T> catchingSuspend(crossinline block: suspend () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.toMcpAutomationException()
}

private fun parseBookmarkType(value: String): DrawerBookmarkType =
    DrawerBookmarkType.entries.firstOrNull { item -> item.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("bookmark type is invalid")

private fun ScopedBookmark.toMcpDto(): McpBookmarkDto = McpBookmarkDto(
    id = id,
    protocol = scope.protocol,
    sourceId = scope.sourceId,
    name = name,
    type = type.name.lowercase(),
    path = path,
    icon = icon,
    sort = sort,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceAvailable = sourceAvailable,
)

private fun FileFavorite.toMcpDto(): McpFavoriteDto = McpFavoriteDto(
    id = id,
    locator = FileLocator(com.folderspan.service.mcp.file.FileEndpointRef(protocol, protocolId.orEmpty()), path),
    name = name,
    isDirectory = isDirectory,
    mimeType = mineType,
    size = size,
    createdAt = createdDate,
    updatedAt = updatedDate,
    pinned = isFixed,
)

private fun FileRecent.toMcpDto(): McpRecentDto = McpRecentDto(
    id = id,
    locator = FileLocator(com.folderspan.service.mcp.file.FileEndpointRef(protocol, protocolId), path),
    name = name,
    isDirectory = isDirectory,
    mimeType = mineType,
    size = size,
    createdAt = createdDate,
    updatedAt = updatedDate,
    lastAccessed = lastAccessed,
)

private fun SocketDevice.toMcpDto(approvalRequired: Boolean): McpDeviceDto = McpDeviceDto(
    id = id,
    name = name,
    host = host,
    port = if (transportType == DeviceTransportType.Session) httpsPort else port,
    platform = type.name.lowercase(),
    transport = transportType.name.lowercase(),
    status = when {
        connectType == ConnectType.Connect -> "connected"
        approvalRequired -> "approval_required"
        connectType == ConnectType.Loading -> "connecting"
        else -> "discovered"
    },
)

private fun SyncTask.toMcpDto(): McpSyncTaskDto = McpSyncTaskDto(
    id = id,
    name = name,
    enabled = enabled,
    sourceType = sourceType.name.lowercase(),
    sourceRef = sourceRef,
    sourcePath = sourcePath,
    targetType = targetType.name.lowercase(),
    targetRef = targetRef,
    targetPath = targetPath,
    lastStatus = lastStatus.name.lowercase(),
    lastRunAt = lastRunAt,
    nextRunAt = nextRunAt,
)
