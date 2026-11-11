package com.folderspan.data.main.network

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.http.archive.FolderSpanArchiveCodec
import com.folderspan.extensions.formatBytes
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatPercent
import com.folderspan.extensions.formatSpeed
import com.folderspan.extensions.fileOperationKey
import com.folderspan.service.network.ChunkReadableNetworkClient
import com.folderspan.service.network.StorageUploadStatus
import com.folderspan.service.network.StorageProvider
import com.folderspan.service.network.StorageCapabilities
import com.folderspan.service.network.StorageContentPlatform
import com.folderspan.service.network.storageContentContains
import com.folderspan.service.network.storageContentIdentityError
import com.folderspan.service.network.storageContentAncestor
import com.folderspan.service.network.uploadManagedStorageContent
import com.folderspan.service.network.downloadVerifiedStorageChunks
import com.folderspan.service.network.finishStorageContentStageWrite
import com.folderspan.service.network.StorageAdvancedCapabilities
import com.folderspan.service.network.StoragePage
import com.folderspan.service.network.StorageVersion
import com.folderspan.service.network.StorageShare
import com.folderspan.service.network.StorageShareRequest
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.NetworkConnectionConfig
import com.folderspan.service.network.NetworkClientFactory
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.containsUnsafeNetworkPathSegment
import com.folderspan.service.network.isRemotePathWithinRoot
import com.folderspan.service.network.isUnsafeNetworkPathSegment
import com.folderspan.service.network.unsafeNetworkWritePathError
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.service.operation.resolveOperationParallelism
import com.folderspan.service.operation.resolveOperationRuntimeMaxParallelism
import com.folderspan.service.operation.resolveTraversalParallelism
import com.folderspan.service.operation.resolveTraversalRuntimeMaxParallelism
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import io.ktor.util.date.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings
import kotlin.math.ceil
import korlibs.crypto.SHA256
import kotlinx.serialization.json.Json

private const val NETWORK_PROGRESS_BLOCK_SIZE = 1024 * 1024L
private const val NETWORK_PROGRESS_INTERVAL_MS = 1000L
enum class NetworkProtocol {
    FTP,
    SFTP,
    SMB,
    WebDav,
    S3,
    HttpDirectory,
    NFS,
    GoogleDrive,
    Dropbox,
    Nextcloud,
    Seafile,
}

open class Network(
    override val name: String,
    override val pathSeparator: String,
    open val protocol: String,
    open val host: String,
    open val username: String,
    open val password: String,
    open val pinned: Boolean = false,
    open val extras: NetworkDriveExtras = NetworkDriveExtras(),
) : DiskBase(), NetworkAccess, ChunkReadableNetworkAccess, KoinComponent {
    override val menuPermission: DiskMenuPermission
        get() = client.capabilities.let { capabilities ->
            DiskMenuPermission(
                read = capabilities.read,
                write = capabilities.write,
                paste = capabilities.write,
                copy = capabilities.read,
                move = capabilities.read && capabilities.delete,
                delete = capabilities.delete,
                rename = capabilities.rename,
                setting = false,
                favorite = capabilities.read,
                share = false,
                info = capabilities.read,
            )
        }

    open suspend fun close() = client.close()

    private fun contentMetadataPathError(path: String): Throwable? =
        if (path.replace('\\', '/').split('/').any { it.equals(".folderspan-vault", ignoreCase = true) })
            AuthorityException("Private content metadata is unavailable through ordinary file operations") else null

    private fun publicNetworkWritePathError(path: String): Throwable? =
        contentMetadataPathError(path) ?: unsafeNetworkWritePathError(path) ?:
            if (storageContentAncestor(connectionConfig(), StoragePath(path)))
                AuthorityException("A password location parent cannot be changed through ordinary file operations") else null

    private fun publicStoragePathError(path: StoragePath, write: Boolean = false): Throwable? =
        storageContentIdentityError(connectionConfig(), path) ?:
            if (write) publicNetworkWritePathError(path.path) else contentMetadataPathError(path.path)

    private fun contentRelated(path: StoragePath): Boolean =
        storageContentContains(connectionConfig(), path) || extras.contentEncryption?.let {
            isRemotePathWithinRoot(path.path, it.root.path, pathSeparator)
        } == true

    private fun nativeContentOperationError(path: StoragePath): Throwable? =
        contentMetadataPathError(path.path) ?: if (contentRelated(path))
            NetworkUnsupportedException("This operation is unavailable for password location content") else null

    suspend fun storageAdvancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> {
        nativeContentOperationError(path)?.let { return Result.success(StorageAdvancedCapabilities()) }
        return client.advancedCapabilities(path)
    }
    suspend fun storageVersions(path: StoragePath, cursor: String? = null): Result<StoragePage<StorageVersion>> {
        nativeContentOperationError(path)?.let { return Result.failure(it) }
        return client.listVersions(path, cursor)
    }
    suspend fun storageShares(path: StoragePath, cursor: String? = null): Result<StoragePage<StorageShare>> {
        nativeContentOperationError(path)?.let { return Result.failure(it) }
        return client.listShares(path, cursor)
    }
    suspend fun restoreStorageVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> {
        nativeContentOperationError(version.file)?.let { return Result.failure(it) }
        if (!confirmed) return client.restoreVersion(version, false)
        StorageContentPlatform.beforeMutation(connectionConfig(), version.file).getOrElse { return Result.failure(it) }
        return client.restoreVersion(version, true)
    }
    suspend fun createStorageShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> {
        nativeContentOperationError(path)?.let { return Result.failure(it) }
        return client.createShare(path, request, confirmed)
    }
    suspend fun revokeStorageShare(share: StorageShare): Result<Unit> = client.revokeShare(share)

    internal suspend fun verifyStorageUpload(
        taskKey: Long, checkpoint: TaskRuntimeTransferCheckpoint,
    ): Result<Unit> = runCatching {
        checkpoint.requireSupportedFormat()
        val reference = checkNotNull(checkpoint.uploadSession) { AppStrings.ui_storage_uncertain }
        check(reference.connectionId == protocolId && reference.ownerRuntimeId == client.uploadSessionOwnerId().getOrThrow()) {
            AppStrings.ui_storage_uncertain
        }
        val session = client.loadUploadSession(taskKey, reference).getOrThrow()
            ?: error(AppStrings.ui_storage_uncertain)
        val verified = client.queryUpload(session).getOrThrow()
        check(verified.status == StorageUploadStatus.COMPLETED ||
            verified.status == StorageUploadStatus.UPLOADING) { AppStrings.ui_storage_uncertain }
    }

    val supportsSync: Boolean get() = client.capabilities.let { it.sync && (it.read || it.write) }

    suspend fun syncReadWrite(): Result<Pair<Boolean, Boolean>> = client.effectiveCapabilities().map {
        (it.sync && it.read) to (it.sync && it.write)
    }

    suspend fun syncCapabilities(): Result<StorageCapabilities> = client.effectiveCapabilities()

    fun connectionConfig() = NetworkConnectionConfig(protocol, host, username, password, pathSeparator, extras)

    protected open val client: StorageProvider by lazy { NetworkClientFactory.create(connectionConfig()) }
    protected open val taskState: TaskState by inject()
    override val protocolId: String
        get() = buildProtocolId()

    private fun buildFileEntry(entry: NetworkFileEntry): FileSimpleInfo {
        val mineType = if (entry.isDirectory) "" else extensionFromName(entry.name)
        return FileSimpleInfo(
            name = entry.name,
            description = "",
            isDirectory = entry.isDirectory,
            isHidden = entry.isHidden,
            path = entry.path,
            mineType = mineType,
            size = entry.size,
            createdDate = entry.createdDate,
            updatedDate = entry.updatedDate,
            protocol = FileProtocol.Network,
            protocolId = protocolId,
            isSymbolicLink = entry.isSymbolicLink,
            isSymbolicLinkKnown = entry.isSymbolicLinkKnown,
            storageReference = entry.reference,
        )
    }

    private fun extensionFromName(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == name.length - 1) return ""
        return name.substring(dotIndex).lowercase()
    }

    private fun joinPath(parent: String, name: String): String {
        require(!isUnsafeNetworkPathSegment(name)) { AppStrings.ui_remote_path_invalid }
        val normalizedParent = parent.ifBlank { pathSeparator }
        return if (normalizedParent.endsWith(pathSeparator)) {
            normalizedParent + name
        } else {
            normalizedParent + pathSeparator + name
        }
    }

    private fun joinRemoteRelative(parent: String, relative: String): String {
        require(!containsUnsafeNetworkPathSegment(relative)) { AppStrings.ui_remote_path_invalid }
        val normalizedParent = parent.ifBlank { pathSeparator }
        val joined = if (normalizedParent.endsWith(pathSeparator)) {
            normalizedParent + relative
        } else {
            normalizedParent + pathSeparator + relative
        }
        require(isRemotePathWithinRoot(parent, joined, pathSeparator)) {
            AppStrings.ui_download_path_exceeds_the_target_directory
        }
        return joined
    }

    private fun buildEndpoint(): String {
        val hostValue = host.trim()
        if (protocol == NetworkProtocol.SMB.name) {
            val shareName = extras.smb.share.trim()
            if (shareName.isNotBlank()) {
                return "$hostValue/$shareName"
            }
        }
        if (protocol == NetworkProtocol.S3.name) {
            val bucket = extras.s3.bucket.trim()
            if (bucket.isNotBlank()) {
                return if (hostValue.isBlank()) bucket else "$bucket@$hostValue"
            }
        }
        if (hostValue.isBlank()) return hostValue
        return hostValue
    }

    private fun updateTaskDetails(task: Task, sourcePath: String, targetPath: String) {
        val updates = buildList {
            add("protocolLabel" to protocol)
            val endpoint = buildEndpoint()
            if (endpoint.isNotBlank()) {
                add("endpoint" to endpoint)
            }
            if (sourcePath.isNotBlank()) {
                add("sourcePath" to sourcePath)
            }
            if (targetPath.isNotBlank()) {
                add("targetPath" to targetPath)
            }
        }
        taskState.putValues(task, updates)
    }

    private fun updateTaskPath(task: Task, path: String) {
        if (path.isBlank()) return
        taskState.putValue(task, "path", path)
    }

    private fun updateTaskProgress(task: Task, current: Int, total: Int) {
        taskState.putValues(
            task,
            listOf(
                "progressMax" to total.toString(),
                "progressCur" to current.coerceAtLeast(0).toString(),
            )
        )
    }

    private fun calculateTotalBlocks(totalBytes: Long): Int {
        if (totalBytes <= 0L) return 1
        val blocks = ceil(totalBytes / NETWORK_PROGRESS_BLOCK_SIZE.toDouble()).toLong()
        return blocks.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun calculateDoneBlocks(doneBytes: Long, totalBlocks: Int): Int {
        if (totalBlocks <= 1) return totalBlocks
        val blocks = ((doneBytes + NETWORK_PROGRESS_BLOCK_SIZE - 1) / NETWORK_PROGRESS_BLOCK_SIZE)
        return blocks.coerceIn(0L, totalBlocks.toLong()).toInt()
    }

    override suspend fun getList(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Result<List<FileSimpleInfo>> {
        return getList(getFile(path).getOrElse { return Result.failure(it) }, requestId, batchId)
    }

    override suspend fun getList(directory: FileSimpleInfo, requestId: String?, batchId: String?): Result<List<FileSimpleInfo>> {
        publicStoragePathError(directory.storagePath)?.let { return Result.failure(it) }
        val logical = StorageContentPlatform.logicalList(connectionConfig(), directory.storagePath)
            .getOrElse { return Result.failure(it) }
        if (logical == null && storageContentContains(connectionConfig(), directory.storagePath))
            return Result.failure(com.folderspan.service.network.StorageEntryNotFoundException())
        val entries = logical?.let { Result.success(it) } ?: client.list(directory.storagePath, requestId, batchId)
        return entries.map { values -> values.filter { contentMetadataPathError(it.path) == null }.map(::buildFileEntry) }
    }

    override fun traverse(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> = flow {
        val pendingDirectories = ArrayDeque<FileSimpleInfo>()
        val visitedDirectories = mutableSetOf<String>()
        pendingDirectories.add(getFile(path).getOrThrow())
        while (pendingDirectories.isNotEmpty()) {
            val directory = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(directory.fileOperationKey())) continue
            val listResult = getList(directory, requestId, batchId)
            emit(listResult)
            val entries = listResult.getOrNull() ?: continue
            if (entries.any { item -> item.isDirectory && !item.isSymbolicLinkKnown }) {
                emit(Result.failure(IllegalStateException(AppStrings.network_untrusted_symbolic_link_metadata)))
                return@flow
            }
            entries
                .asSequence()
                .filter { item -> item.isDirectory && !item.isSymbolicLink }
                .forEach { item -> pendingDirectories.add(item) }
        }
    }

    override fun getRootPaths(): List<PathInfo> {
        return listOf(PathInfo(pathSeparator, 0L, 0L))
    }

    override suspend fun getFile(file: FileSimpleInfo): Result<FileSimpleInfo> {
        publicStoragePathError(file.storagePath)?.let { return Result.failure(it) }
        val logical = StorageContentPlatform.logicalFile(connectionConfig(), file).getOrElse { return Result.failure(it) }
        if (logical == null && storageContentContains(connectionConfig(), file.storagePath))
            return Result.failure(com.folderspan.service.network.StorageEntryNotFoundException())
        return logical?.let { Result.success(it.copy(protocol = FileProtocol.Network, protocolId = protocolId)) }
            ?: client.stat(file.storagePath).map(::buildFileEntry)
    }

    override fun getFile(path: String): Result<FileSimpleInfo> {
        contentMetadataPathError(path)?.let { return Result.failure(it) }
        val info = FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
            protocol = FileProtocol.Network,
            protocolId = protocolId,
            storageReference = if (path == pathSeparator) client.rootLocation.reference else null,
        )
        return Result.success(info)
    }

    override suspend fun copyFileWithinEndpoint(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        publicStoragePathError(srcFileSimpleInfo.storagePath)?.let { return Result.failure(it) }
        publicStoragePathError(destFileSimpleInfo.storagePath, true)?.let { return Result.failure(it) }
        if (!client.capabilities.write) return writeUnsupported()
        if (srcFileSimpleInfo.isDirectory || destFileSimpleInfo.isDirectory) {
            return Result.failure(
                NetworkUnsupportedException(AppStrings.network_same_endpoint_directory_copy_requires_file_tasks)
            )
        }
        if (
            srcFileSimpleInfo.protocol != FileProtocol.Network ||
            destFileSimpleInfo.protocol != FileProtocol.Network ||
            srcFileSimpleInfo.protocolId.isBlank() ||
            srcFileSimpleInfo.protocolId != destFileSimpleInfo.protocolId ||
            srcFileSimpleInfo.protocolId != protocolId
        ) {
            return Result.failure(IllegalArgumentException(AppStrings.network_source_target_endpoint_mismatch))
        }
        val sourceId = srcFileSimpleInfo.storageReference?.id.orEmpty()
        val targetId = destFileSimpleInfo.storageReference?.id.orEmpty()
        if ((sourceId.isNotEmpty() && sourceId == targetId) ||
            (srcFileSimpleInfo.storageReference == null && destFileSimpleInfo.storageReference == null && srcFileSimpleInfo.path == destFileSimpleInfo.path)) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_source_target_cannot_same))
        }
        if (!taskState.awaitIfPaused(task.key) || taskState.isTaskCancelled(task.key)) {
            return Result.failure(CancellationException(AppStrings.message_task_cancelled))
        }

        updateTaskDetails(task, srcFileSimpleInfo.path, destFileSimpleInfo.path)
        updateTaskPath(task, destFileSimpleInfo.path)
        updateTaskProgress(task, 0, 1)
        taskState.putResult(task, destFileSimpleInfo.path, AppStrings.ui_copying)

        nativeContentOperationError(srcFileSimpleInfo.storagePath)?.let { return Result.failure(it) }
        nativeContentOperationError(destFileSimpleInfo.storagePath)?.let { return Result.failure(it) }
        if (extras.contentEncryption == null && contentTransformAvailable()) {
            return Result.failure(NetworkUnsupportedException("Managed content copy requires verified transfer"))
        }
        val result = client.copyFile(
            sourcePath = srcFileSimpleInfo.storagePath,
            targetPath = destFileSimpleInfo.storagePath,
        )
        if (result.isSuccess && result.getOrDefault(false)) {
            updateTaskProgress(task, 1, 1)
            taskState.removeResult(task, destFileSimpleInfo.path)
        } else {
            val message = result.exceptionOrNull()?.message
                ?.takeIf { it.isNotBlank() }
                ?: AppStrings.network_endpoint_copy_failed
            taskState.putResult(task, destFileSimpleInfo.path, message)
        }
        return result
    }

    override suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        if (srcFileSimpleInfo.protocol == FileProtocol.Network) {
            publicStoragePathError(srcFileSimpleInfo.storagePath)?.let { return Result.failure(it) }
        }
        if (destFileSimpleInfo.protocol == FileProtocol.Network) {
            publicNetworkWritePathError(destFileSimpleInfo.path)?.let { return Result.failure(it) }
        }
        if (srcFileSimpleInfo.protocol == FileProtocol.Local &&
            destFileSimpleInfo.protocol == FileProtocol.Network && !menuPermission.write) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported))
        }
        if (srcFileSimpleInfo.protocol == FileProtocol.Network &&
            destFileSimpleInfo.protocol == FileProtocol.Network &&
            (srcFileSimpleInfo.protocolId.isBlank() || srcFileSimpleInfo.protocolId != destFileSimpleInfo.protocolId)) {
            return Result.failure(NetworkUnsupportedException("Cross-endpoint copies require the common verified transfer coordinator"))
        }
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        fun markCopySuccess(src: FileSimpleInfo, dest: FileSimpleInfo) {
            taskState.removeResult(task, dest.path)
        }

        fun markCopyFailure(
            src: FileSimpleInfo,
            dest: FileSimpleInfo,
            message: String,
            fallback: String,
        ): String {
            return taskState.recordRetryFailure(
                task = task,
                entry = buildCopyRetryEntry(task.taskType, src, dest),
                message = message,
                fallback = fallback,
            )
        }

        suspend fun ensureRunning() {
            if (!taskState.awaitIfPaused(task.key)) {
                throw cancellation
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        return try {
            when (srcFileSimpleInfo.protocol) {
                FileProtocol.Network if
                    destFileSimpleInfo.protocol == FileProtocol.Network &&
                    srcFileSimpleInfo.protocolId.isNotBlank() &&
                    srcFileSimpleInfo.protocolId == destFileSimpleInfo.protocolId -> {
                    copyFileWithinEndpoint(task, srcFileSimpleInfo, destFileSimpleInfo)
                }

                FileProtocol.Local if destFileSimpleInfo.protocol == FileProtocol.Network -> {
                    updateTaskDetails(task, srcFileSimpleInfo.path, destFileSimpleInfo.path)
                    rejectUnsafeLocalUploadSource(srcFileSimpleInfo)?.let { error ->
                        markCopyFailure(
                            srcFileSimpleInfo,
                            destFileSimpleInfo,
                            error.message.orEmpty(),
                            error.message.orEmpty(),
                        )
                        return Result.failure(error)
                    }
                    if (!srcFileSimpleInfo.isDirectory) {
                        ensureRunning()
                        val totalBytes = srcFileSimpleInfo.size.coerceAtLeast(0L)
                        val totalBlocks = calculateTotalBlocks(totalBytes)
                        updateTaskPath(task, destFileSimpleInfo.path)
                        updateTaskProgress(task, 0, totalBlocks)
                        taskState.putResult(
                            task,
                            destFileSimpleInfo.path,
                            AppStrings.task_start_operation.format(operation = AppStrings.ui_upload),
                        )
                        val tracker = NetworkProgressTracker(
                            task = task,
                            destPath = destFileSimpleInfo.path,
                            totalBytes = totalBytes,
                            totalBlocks = totalBlocks,
                            taskState = taskState
                        )
                        val result = uploadLocalTaskFile(
                            task = task,
                            source = srcFileSimpleInfo,
                            target = destFileSimpleInfo,
                            ensureRunning = { ensureRunning() },
                            onProgress = { doneBytes, total ->
                                if (taskState.isTaskCancelled(task.key)) {
                                    throw cancellation
                                }
                                val resolvedTotal = if (total > 0L) total else totalBytes
                                val resolvedBlocks = if (resolvedTotal > 0L) {
                                    calculateTotalBlocks(resolvedTotal)
                                } else {
                                    totalBlocks
                                }
                                val blocks = calculateDoneBlocks(doneBytes, resolvedBlocks)
                                updateTaskProgress(task, blocks, resolvedBlocks)
                                tracker.update(doneBytes, AppStrings.ui_upload_progress)
                            }
                        )
                        if (result.isSuccess) {
                            updateTaskProgress(task, totalBlocks, totalBlocks)
                            tracker.update(totalBytes, AppStrings.ui_upload_progress)
                            markCopySuccess(srcFileSimpleInfo, destFileSimpleInfo)
                        }
                        if (taskState.isTaskCancelled(task.key)) {
                            throw cancellation
                        }
                        if (result.isFailure) {
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                result.exceptionOrNull()?.message.orEmpty(),
                                AppStrings.network_upload_failed,
                            )
                        }
                        result
                    } else {
                        val localSeparator = PathUtils.getPathSeparator()
                        val srcRoot = if (srcFileSimpleInfo.path.endsWith(localSeparator)) {
                            srcFileSimpleInfo.path
                        } else {
                            srcFileSimpleInfo.path + localSeparator
                        }
                        ensureRunning()
                        taskState.putCopyScanProgress(task)
                        val traversalEntries: List<FileSimpleInfo>
                        val rejectedEntries = mutableListOf<Pair<FileSimpleInfo, Throwable>>()
                        try {
                            var discoveredEntries = 0
                            val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
                            traversalEntries = collectDirectoryEntriesAdaptive(
                                root = srcFileSimpleInfo,
                                config = resolveTraversalParallelism(TraversalEndpointKind.Local),
                                pathSeparator = localSeparator,
                                ensureRunning = { ensureRunning() },
                                onScanProgress = scanProgressPublisher,
                                onEntriesDiscovered = { entries ->
                                    discoveredEntries += entries.size
                                    taskState.putCopyScanProgress(task, discoveredEntries)
                                },
                                dynamicMaxParallelismProvider = {
                                    resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Local)
                                },
                                rejectSymbolicLinkEntries = true,
                                onRejectedEntry = { entry, error ->
                                    rejectedEntries += entry to error
                                    discoveredEntries++
                                    taskState.putCopyScanProgress(task, discoveredEntries)
                                },
                            ) { directory ->
                                PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).map { entries ->
                                    entries.map { entry ->
                                        entry.withCopy(protocol = FileProtocol.Local, protocolId = "")
                                    }
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            val message = error.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_failed_read_source_directory
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                message,
                                AppStrings.ui_failed_read_source_directory,
                            )
                            return Result.failure(error)
                        }
                        val plannedEntries = traversalEntries.size + rejectedEntries.size
                        taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                        val rootCreateResult = createFolder(destFileSimpleInfo.storagePath)
                        if (rootCreateResult.isFailure || !rootCreateResult.getOrDefault(false)) {
                            taskState.removeResult(task, "")
                            val message = rootCreateResult.exceptionOrNull()?.message
                                ?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_folder_creation_failed
                            markCopyFailure(srcFileSimpleInfo, destFileSimpleInfo, message, AppStrings.ui_folder_creation_failed)
                            return Result.failure(rootCreateResult.exceptionOrNull() ?: Exception(message))
                        }
                        updateTaskPath(task, destFileSimpleInfo.path)
                        var processed = 0
                        var failureCount = 0
                        var firstFailedPath: String? = null
                        var firstError: String? = null
                        val progressMutex = Mutex()

                        val resolvedProgressMax = plannedEntries.coerceAtLeast(1)
                        updateTaskProgress(task, processed, resolvedProgressMax)

                        fun recordFailure(path: String, message: String, isDirectory: Boolean) {
                            val errorText = message.ifBlank { AppStrings.network_upload_failed }
                            val relative = path.removePrefix(destFileSimpleInfo.path).removePrefix(pathSeparator)
                            val srcPath = if (relative.isBlank()) {
                                srcFileSimpleInfo.path
                            } else {
                                srcRoot.removeSuffix(localSeparator) + localSeparator + relative.replace(pathSeparator, localSeparator)
                            }
                            markCopyFailure(
                                src = buildRetryFileSimpleInfo(
                                    path = srcPath,
                                    protocol = srcFileSimpleInfo.protocol,
                                    protocolId = srcFileSimpleInfo.protocolId,
                                    isDirectory = isDirectory,
                                ),
                                dest = buildRetryFileSimpleInfo(
                                    path = path,
                                    protocol = destFileSimpleInfo.protocol,
                                    protocolId = destFileSimpleInfo.protocolId,
                                    isDirectory = isDirectory,
                                    storageReference = destFileSimpleInfo.withCopy(path = path).storageReference,
                                ),
                                message = errorText,
                                fallback = AppStrings.network_upload_failed,
                            )
                            failureCount++
                            if (firstFailedPath == null) {
                                firstFailedPath = path
                            }
                            if (firstError == null) {
                                firstError = errorText
                            }
                        }

                        suspend fun updateEntryProgress(success: Boolean, path: String) {
                            progressMutex.withLock {
                                processed++
                                updateTaskProgress(task, processed, resolvedProgressMax)
                                if (success) {
                                    taskState.removeResult(task, path)
                                }
                            }
                        }

                        fun buildRemotePath(entry: FileSimpleInfo): String {
                            val relative = entry.path.removePrefix(srcRoot)
                                .replace(localSeparator, pathSeparator)
                            if (relative.isBlank()) return destFileSimpleInfo.path
                            return joinRemoteRelative(destFileSimpleInfo.path, relative)
                        }

                        rejectedEntries.forEach { (entry, error) ->
                            val remotePath = buildRemotePath(entry)
                            recordFailure(remotePath, error.message.orEmpty(), entry.isDirectory)
                            updateEntryProgress(false, remotePath)
                        }

                        suspend fun processDirectoryEntry(entry: FileSimpleInfo) {
                            ensureRunning()
                            val relative = entry.path.removePrefix(srcRoot)
                            if (relative.isBlank()) return
                            val remotePath = buildRemotePath(entry)
                            rejectUnsafeLocalUploadSource(entry)?.let { error ->
                                recordFailure(remotePath, error.message.orEmpty(), true)
                                updateEntryProgress(false, remotePath)
                                return
                            }
                            taskState.putCreatingFolderProgress(task, remotePath)
                            val result = createFolder(destFileSimpleInfo.withCopy(path = remotePath).storagePath)
                            val success = result.isSuccess && result.getOrDefault(false)
                            if (success) {
                                markCopySuccess(
                                    src = entry.copy(protocol = srcFileSimpleInfo.protocol, protocolId = srcFileSimpleInfo.protocolId),
                                    dest = buildRetryFileSimpleInfo(
                                        path = remotePath,
                                        protocol = destFileSimpleInfo.protocol,
                                        protocolId = destFileSimpleInfo.protocolId,
                                        isDirectory = true,
                                    ),
                                )
                            } else {
                                recordFailure(
                                    remotePath,
                                    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                        ?: AppStrings.ui_folder_creation_failed,
                                    true,
                                )
                            }
                            updateEntryProgress(success, remotePath)
                        }

                        suspend fun uploadFileEntry(entry: FileSimpleInfo) {
                            ensureRunning()
                            val relative = entry.path.removePrefix(srcRoot)
                            if (relative.isBlank()) return
                            val remotePath = buildRemotePath(entry)
                            rejectUnsafeLocalUploadSource(entry)?.let { error ->
                                recordFailure(remotePath, error.message.orEmpty(), false)
                                updateEntryProgress(false, remotePath)
                                return
                            }
                            updateTaskPath(task, remotePath)
                            val totalBytes = entry.size.coerceAtLeast(0L)
                            val totalBlocks = calculateTotalBlocks(totalBytes)
                            taskState.putResult(
                                task,
                                remotePath,
                                AppStrings.task_start_operation.format(operation = AppStrings.ui_upload),
                            )
                            val tracker = NetworkProgressTracker(
                                task = task,
                                destPath = remotePath,
                                totalBytes = totalBytes,
                                totalBlocks = totalBlocks,
                                taskState = taskState
                            )
                            val result = uploadLocalTaskFile(
                                task = task,
                                source = entry,
                                target = destFileSimpleInfo.withCopy(path = remotePath),
                                ensureRunning = { ensureRunning() },
                                onProgress = { doneBytes, _ ->
                                    if (taskState.isTaskCancelled(task.key)) {
                                        throw cancellation
                                    }
                                    tracker.update(doneBytes, AppStrings.ui_upload_progress)
                                }
                            )
                            if (result.isSuccess) {
                                tracker.update(totalBytes, AppStrings.ui_upload_progress)
                                markCopySuccess(
                                    src = entry.copy(protocol = srcFileSimpleInfo.protocol, protocolId = srcFileSimpleInfo.protocolId),
                                    dest = buildRetryFileSimpleInfo(
                                        path = remotePath,
                                        protocol = destFileSimpleInfo.protocol,
                                        protocolId = destFileSimpleInfo.protocolId,
                                        isDirectory = false,
                                    ),
                                )
                            } else if (result.exceptionOrNull() is CancellationException) {
                                throw cancellation
                            } else {
                                recordFailure(
                                    remotePath,
                                    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                        ?: AppStrings.network_upload_failed,
                                    false,
                                )
                            }
                            updateEntryProgress(result.isSuccess, remotePath)
                            ensureRunning()
                        }

                        suspend fun enqueueTraversalBatch(
                            entries: List<FileSimpleInfo>,
                        ) {
                            if (entries.isEmpty()) return
                            val normalizedEntries = entries.sortedWith(
                                compareBy<FileSimpleInfo> { item -> !item.isDirectory }
                                    .thenBy { item -> item.path.length }
                            )
                            normalizedEntries.filter { it.isDirectory }.forEach { entry ->
                                processDirectoryEntry(entry)
                            }
                            taskState.removeResult(task, "")
                            processItemsAdaptive(
                                items = normalizedEntries.filterNot { it.isDirectory },
                                config = resolveOperationParallelism(TraversalEndpointKind.Network),
                                ensureRunning = { ensureRunning() },
                                dynamicMaxParallelismProvider = {
                                    resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Network)
                                },
                            ) { entry ->
                                uploadFileEntry(entry)
                            }
                        }

                        if (plannedEntries == 0) {
                            taskState.removeResult(task, "")
                            updateTaskProgress(task, resolvedProgressMax, resolvedProgressMax)
                            taskState.removeResult(task, destFileSimpleInfo.path)
                            return Result.success(true)
                        }

                        try {
                            enqueueTraversalBatch(traversalEntries)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            val message = error.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_failed_read_source_directory
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                message,
                                AppStrings.ui_failed_read_source_directory,
                            )
                            return Result.failure(error)
                        }
                        taskState.removeResult(task, "")
                        if (failureCount > 0) {
                            val summary = buildBatchTaskFailureMessage(
                                operation = AppStrings.ui_upload,
                                failureCount = failureCount,
                                firstFailedPath = firstFailedPath,
                                firstError = firstError
                            )
                            taskState.putResult(task, destFileSimpleInfo.path, summary)
                            Result.failure(Exception(summary))
                        } else {
                            taskState.removeResult(task, destFileSimpleInfo.path)
                            Result.success(true)
                        }
                    }
                }
                FileProtocol.Network if destFileSimpleInfo.protocol == FileProtocol.Local -> {
                    updateTaskDetails(task, srcFileSimpleInfo.path, destFileSimpleInfo.path)
                    if (!srcFileSimpleInfo.isDirectory) {
                        ensureRunning()
                        val totalBytes = srcFileSimpleInfo.size.coerceAtLeast(0L)
                        val totalBlocks = calculateTotalBlocks(totalBytes)
                        updateTaskPath(task, destFileSimpleInfo.path)
                        updateTaskProgress(task, 0, totalBlocks)
                        taskState.putResult(
                            task,
                            destFileSimpleInfo.path,
                            AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
                        )
                        val tracker = NetworkProgressTracker(
                            task = task,
                            destPath = destFileSimpleInfo.path,
                            totalBytes = totalBytes,
                            totalBlocks = totalBlocks,
                            taskState = taskState
                        )
                        val result = downloadManagedFile(
                            taskKey = task.key,
                            file = srcFileSimpleInfo,
                            localPath = destFileSimpleInfo.path,
                            onProgress = { doneBytes, total ->
                                if (taskState.isTaskCancelled(task.key)) {
                                    throw cancellation
                                }
                                val resolvedTotal = if (total > 0L) total else totalBytes
                                val resolvedBlocks = if (resolvedTotal > 0L) {
                                    calculateTotalBlocks(resolvedTotal)
                                } else {
                                    totalBlocks
                                }
                                val blocks = calculateDoneBlocks(doneBytes, resolvedBlocks)
                                updateTaskProgress(task, blocks, resolvedBlocks)
                                tracker.update(doneBytes, AppStrings.ui_download_progress)
                            }
                        )
                        if (result.isSuccess) {
                            updateTaskProgress(task, totalBlocks, totalBlocks)
                            tracker.update(totalBytes, AppStrings.ui_download_progress)
                            markCopySuccess(srcFileSimpleInfo, destFileSimpleInfo)
                        }
                        if (taskState.isTaskCancelled(task.key)) {
                            throw cancellation
                        }
                        if (result.isFailure) {
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                result.exceptionOrNull()?.message.orEmpty(),
                                AppStrings.ui_file_download_failed,
                            )
                        }
                        result
                    } else {
                        val localSeparator = PathUtils.getPathSeparator()
                        val srcRoot = if (srcFileSimpleInfo.path.endsWith(pathSeparator)) {
                            srcFileSimpleInfo.path
                        } else {
                            srcFileSimpleInfo.path + pathSeparator
                        }
                        val destRoot = destFileSimpleInfo.path
                        ensureRunning()
                        taskState.putCopyScanProgress(task)
                        var plannedEntries = 0
                        val traversalEntries: List<FileSimpleInfo>
                        val rejectedEntries = mutableListOf<Pair<FileSimpleInfo, Throwable>>()
                        try {
                            val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
                            traversalEntries = collectDirectoryEntriesAdaptive(
                                root = srcFileSimpleInfo,
                                config = resolveTraversalParallelism(TraversalEndpointKind.Network),
                                pathSeparator = pathSeparator,
                                ensureRunning = { ensureRunning() },
                                onScanProgress = scanProgressPublisher,
                                onEntriesDiscovered = { entries ->
                                    plannedEntries += entries.size
                                    taskState.putCopyScanProgress(task, plannedEntries)
                                },
                                dynamicMaxParallelismProvider = {
                                    resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Network)
                                },
                                requireKnownSymbolicLinkMetadata = true,
                                rejectSymbolicLinkEntries = true,
                                onRejectedEntry = { entry, error ->
                                    rejectedEntries += entry to error
                                    plannedEntries++
                                    taskState.putCopyScanProgress(task, plannedEntries)
                                },
                            ) { directory ->
                                getList(directory, null, null)
                            }
                            val targetPaths = mutableSetOf<String>()
                            for (entry in traversalEntries) {
                                val relative = entry.path.removePrefix(srcRoot)
                                    .replace('\\', '/')
                                    .replace(pathSeparator, "/")
                                val target = FolderSpanArchiveCodec.normalizeRelativePath(relative)
                                check(targetPaths.add(target)) { AppStrings.storage_ambiguous_copy_names }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            val message = error.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_failed_read_source_directory
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                message,
                                AppStrings.ui_failed_read_source_directory,
                            )
                            return Result.failure(error)
                        }
                        taskState.putCreatingFolderProgress(task, destRoot)
                        val rootFolder = FileUtils.createFolder(FileAccessPermission.Allowed, destRoot)
                        if (rootFolder.isFailure || !rootFolder.getOrDefault(false)) {
                            taskState.removeResult(task, "")
                            val message = rootFolder.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_folder_creation_failed
                            markCopyFailure(srcFileSimpleInfo, destFileSimpleInfo, message, AppStrings.ui_folder_creation_failed)
                            return Result.failure(rootFolder.exceptionOrNull() ?: Exception(message))
                        }
                        updateTaskPath(task, destRoot)
                        var processed = 0
                        var failureCount = 0
                        var firstFailedPath: String? = null
                        var firstError: String? = null
                        val resolvedProgressMax = plannedEntries.coerceAtLeast(1)
                        val progressMutex = Mutex()

                        fun recordFailure(path: String, message: String, isDirectory: Boolean) {
                            val errorText = message.ifBlank { AppStrings.ui_file_download_failed }
                            val relative = path.removePrefix(destRoot).removePrefix(localSeparator)
                            val remotePath = if (relative.isBlank()) {
                                srcFileSimpleInfo.path
                            } else {
                                srcRoot.removeSuffix(pathSeparator) + pathSeparator + relative.replace(localSeparator, pathSeparator)
                            }
                            markCopyFailure(
                                src = buildRetryFileSimpleInfo(
                                    path = remotePath,
                                    protocol = srcFileSimpleInfo.protocol,
                                    protocolId = srcFileSimpleInfo.protocolId,
                                    isDirectory = isDirectory,
                                    storageReference = traversalEntries.firstOrNull { it.path == remotePath }?.storageReference,
                                ),
                                dest = buildRetryFileSimpleInfo(
                                    path = path,
                                    protocol = destFileSimpleInfo.protocol,
                                    protocolId = destFileSimpleInfo.protocolId,
                                    isDirectory = isDirectory,
                                    storageReference = destFileSimpleInfo.withCopy(path = path).storageReference,
                                ),
                                message = errorText,
                                fallback = AppStrings.ui_file_download_failed,
                            )
                            failureCount++
                            if (firstFailedPath == null) {
                                firstFailedPath = path
                            }
                            if (firstError == null) {
                                firstError = errorText
                            }
                        }

                        suspend fun updateEntryProgress(success: Boolean, path: String) {
                            progressMutex.withLock {
                                processed++
                                updateTaskProgress(task, processed, resolvedProgressMax)
                                if (success) {
                                    taskState.removeResult(task, path)
                                }
                            }
                        }

                        suspend fun buildLocalPath(remotePath: String): String {
                            val relative = remotePath.removePrefix(srcRoot)
                                .replace('\\', '/')
                                .replace(pathSeparator, "/")
                            val normalizedRelative = FolderSpanArchiveCodec.normalizeRelativePath(relative)
                            val localPath = FolderSpanArchiveCodec.buildTargetPath(
                                rootPath = destRoot,
                                relativePath = normalizedRelative,
                                separator = localSeparator,
                            )
                            if (
                                !PathUtils.isPathWithinRoot(
                                    FileAccessPermission.Allowed,
                                    destRoot,
                                    localPath,
                                    allowNonExistentLeaf = true,
                                )
                            ) {
                                throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
                            }
                            SensitiveFileAccessPolicy.deniedException(localPath)?.let { error -> throw error }
                            return localPath
                        }

                        rejectedEntries.forEach { (entry, error) ->
                            val localPath = buildLocalPath(entry.path)
                            recordFailure(localPath, error.message.orEmpty(), entry.isDirectory)
                            updateEntryProgress(false, localPath)
                        }

                        suspend fun processDirectoryEntry(entry: FileSimpleInfo) {
                            ensureRunning()
                            val relative = entry.path.removePrefix(srcRoot)
                            if (relative.isBlank()) return
                            val localPath = buildLocalPath(entry.path)
                            updateTaskPath(task, localPath)
                            taskState.putCreatingFolderProgress(task, localPath)
                            val createFolder = FileUtils.createFolder(FileAccessPermission.Allowed, localPath)
                            val success = createFolder.isSuccess && createFolder.getOrDefault(false)
                            if (success) {
                                markCopySuccess(
                                    src = buildRetryFileSimpleInfo(
                                        path = entry.path,
                                        protocol = srcFileSimpleInfo.protocol,
                                        protocolId = srcFileSimpleInfo.protocolId,
                                        isDirectory = true,
                                        storageReference = entry.storageReference,
                                    ),
                                    dest = buildRetryFileSimpleInfo(
                                        path = localPath,
                                        protocol = destFileSimpleInfo.protocol,
                                        protocolId = destFileSimpleInfo.protocolId,
                                        isDirectory = true,
                                    ),
                                )
                            } else {
                                recordFailure(
                                    localPath,
                                    createFolder.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                        ?: AppStrings.ui_folder_creation_failed,
                                    true,
                                )
                            }
                            updateEntryProgress(success, localPath)
                        }

                        suspend fun downloadFileEntry(entry: FileSimpleInfo) {
                            ensureRunning()
                            val relative = entry.path.removePrefix(srcRoot)
                            if (relative.isBlank()) return
                            val localPath = buildLocalPath(entry.path)
                            updateTaskPath(task, localPath)
                            taskState.removeResult(task, "")
                            val totalBytes = entry.size.coerceAtLeast(0L)
                            val totalBlocks = calculateTotalBlocks(totalBytes)
                            taskState.putResult(
                                task,
                                localPath,
                                AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
                            )
                            val tracker = NetworkProgressTracker(
                                task = task,
                                destPath = localPath,
                                totalBytes = totalBytes,
                                totalBlocks = totalBlocks,
                                taskState = taskState
                            )
                            val result = downloadManagedFile(
                                taskKey = task.key,
                                file = entry,
                                localPath = localPath,
                                onProgress = { doneBytes, _ ->
                                    if (taskState.isTaskCancelled(task.key)) {
                                        throw cancellation
                                    }
                                    tracker.update(doneBytes, AppStrings.ui_download_progress)
                                }
                            )
                            if (result.isSuccess) {
                                tracker.update(totalBytes, AppStrings.ui_download_progress)
                                markCopySuccess(
                                    src = buildRetryFileSimpleInfo(
                                        path = entry.path,
                                        protocol = srcFileSimpleInfo.protocol,
                                        protocolId = srcFileSimpleInfo.protocolId,
                                        isDirectory = false,
                                        storageReference = entry.storageReference,
                                    ),
                                    dest = buildRetryFileSimpleInfo(
                                        path = localPath,
                                        protocol = destFileSimpleInfo.protocol,
                                        protocolId = destFileSimpleInfo.protocolId,
                                        isDirectory = false,
                                    ),
                                )
                            } else if (result.exceptionOrNull() is CancellationException) {
                                throw cancellation
                            } else {
                                recordFailure(
                                    localPath,
                                    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                        ?: AppStrings.ui_file_download_failed,
                                    false,
                                )
                            }
                            updateEntryProgress(result.isSuccess, localPath)
                            ensureRunning()
                        }

                        updateTaskProgress(task, processed, resolvedProgressMax)

                        if (plannedEntries == 0) {
                            taskState.removeResult(task, "")
                            updateTaskProgress(task, resolvedProgressMax, resolvedProgressMax)
                            taskState.removeResult(task, destRoot)
                            return Result.success(true)
                        }

                        try {
                            val entries = traversalEntries.sortedWith(
                                compareBy<FileSimpleInfo> { item -> !item.isDirectory }
                                    .thenBy { item -> item.path.length }
                            )
                            entries.filter { it.isDirectory }.forEach { entry ->
                                processDirectoryEntry(entry)
                            }
                            processItemsAdaptive(
                                items = entries.filterNot { it.isDirectory },
                                config = resolveOperationParallelism(TraversalEndpointKind.Network),
                                ensureRunning = { ensureRunning() },
                                dynamicMaxParallelismProvider = {
                                    resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Network)
                                },
                            ) { entry ->
                                downloadFileEntry(entry)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            val message = error.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_failed_read_source_directory
                            markCopyFailure(
                                srcFileSimpleInfo,
                                destFileSimpleInfo,
                                message,
                                AppStrings.ui_failed_read_source_directory,
                            )
                            return Result.failure(error)
                        }

                        taskState.removeResult(task, "")
                        if (failureCount > 0) {
                            val summary = buildBatchTaskFailureMessage(
                                operation = AppStrings.ui_download,
                                failureCount = failureCount,
                                firstFailedPath = firstFailedPath,
                                firstError = firstError,
                            )
                            taskState.putResult(task, destRoot, summary)
                            Result.failure(Exception(summary))
                        } else {
                            taskState.removeResult(task, destRoot)
                            Result.success(true)
                        }
                    }
                }
                else -> Result.failure(Exception(AppStrings.ui_copy_failed))
            }
        } catch (cancel: CancellationException) {
            Result.failure(cancel)
        }
    }

    override suspend fun rename(file: FileSimpleInfo, newName: String): Result<Boolean> {
        if (!client.capabilities.rename) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(newName)) return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        publicStoragePathError(file.storagePath, true)?.let { return Result.failure(it) }
        val parent = file.path.removeSuffix(pathSeparator).substringBeforeLast(pathSeparator, pathSeparator)
        val newPath = joinPath(parent, newName)
        StorageContentPlatform.renameLogical(connectionConfig(), file.storagePath, StoragePath(newPath))
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), file.storagePath).getOrElse { return Result.failure(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), StoragePath(newPath)).getOrElse { return Result.failure(it) }
        return client.rename(file.storagePath, newPath)
    }

    override suspend fun move(file: FileSimpleInfo, to: StoragePath): Result<Boolean> {
        publicStoragePathError(file.storagePath, true)?.let { return Result.failure(it) }
        publicStoragePathError(to, true)?.let { return Result.failure(it) }
        StorageContentPlatform.renameLogical(connectionConfig(), file.storagePath, to)
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), file.storagePath).getOrElse { return Result.failure(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), to).getOrElse { return Result.failure(it) }
        return client.move(file.storagePath, to)
    }

    override suspend fun delete(file: FileSimpleInfo): Result<Boolean> {
        if (!client.capabilities.delete) return writeUnsupported()
        publicStoragePathError(file.storagePath, true)?.let { return Result.failure(it) }
        StorageContentPlatform.deleteLogical(connectionConfig(), file.storagePath, file.isDirectory)
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), file.storagePath).getOrElse { return Result.failure(it) }
        return client.delete(file.storagePath, file.isDirectory)
    }

    override suspend fun deleteEmptyDirectory(file: FileSimpleInfo): Result<Boolean> {
        if (!file.isDirectory || file.isSymbolicLink) return Result.failure(IllegalArgumentException("Not a directory"))
        if (!client.effectiveCapabilities().getOrElse { return Result.failure(it) }.delete) return writeUnsupported()
        publicStoragePathError(file.storagePath, true)?.let { return Result.failure(it) }
        val logical = StorageContentPlatform.logicalList(connectionConfig(), file.storagePath)
            .getOrElse { return Result.failure(it) }
        if (logical != null) {
            if (logical.isNotEmpty()) return Result.failure(IllegalStateException("Directory is not empty"))
            StorageContentPlatform.deleteLogical(connectionConfig(), file.storagePath, true)
                .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
            return Result.failure(IllegalStateException("Logical directory deletion was not handled"))
        }
        StorageContentPlatform.beforeMutation(connectionConfig(), file.storagePath).getOrElse { return Result.failure(it) }
        return client.deleteEmptyDirectory(file.storagePath)
    }

    override suspend fun createFolder(path: StoragePath): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        publicStoragePathError(path, true)?.let { return Result.failure(it) }
        StorageContentPlatform.createLogicalDirectory(connectionConfig(), path)
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        return client.createFolder(path)
    }
    override suspend fun createFile(path: StoragePath): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        publicStoragePathError(path, true)?.let { return Result.failure(it) }
        return uploadManagedStorageContent(client, connectionConfig(), path, 0, onProgress = { _, _ -> }, readChunk = { null })
            ?: client.createFile(path)
    }

    override suspend fun createFolder(directory: FileSimpleInfo, name: String): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(name)) return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        publicStoragePathError(directory.storagePath)?.let { return Result.failure(it) }
        return createFolder(directory.withCopy(path = joinPath(directory.path, name)).storagePath)
    }

    override suspend fun createFile(directory: FileSimpleInfo, name: String): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(name)) return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        publicStoragePathError(directory.storagePath)?.let { return Result.failure(it) }
        return createFile(directory.withCopy(path = joinPath(directory.path, name), isDirectory = false).storagePath)
    }

    private suspend fun uploadForegroundLocal(
        localPath: String, target: StoragePath, size: Long, onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> {
        return try {
            val source = FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrThrow()
            var offset = 0L
            uploadManagedStorageContent(
                provider = client, config = connectionConfig(), target = target, size = source.size,
                source = source, onProgress = onProgress,
                readChunk = {
                    if (offset >= source.size) null else {
                        val end = minOf(offset + 1024 * 1024, source.size)
                        FileUtils.readFileRange(FileAccessPermission.Allowed, localPath, offset, end).getOrThrow().also { offset = end }
                    }
                },
            ) ?: client.upload(localPath, target, source.size, onProgress)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { Result.failure(error) }
    }

    internal suspend fun contentTransformAvailable(): Boolean =
        StorageContentPlatform.status(connectionConfig()).getOrThrow().available

    private suspend fun downloadManagedFile(
        taskKey: Long,
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> {
        publicStoragePathError(file.storagePath)?.let { return Result.failure(it) }
        var stage: String? = null
        return try {
            val resolved = StorageContentPlatform.resolveDownload(connectionConfig(), file).getOrThrow()
            if (resolved == null && storageContentContains(connectionConfig(), file.storagePath))
                throw com.folderspan.service.network.StorageEntryNotFoundException()
            val frozen = resolved?.file ?: file
            val legacy = extras.contentEncryption == null && contentTransformAvailable()
            if (resolved == null && !legacy) {
                if (file.size == 0L && file.storageReference == null) return FileUtils.createFile(FileAccessPermission.Allowed, localPath)
                return client.download(file.storagePath, localPath, file.size, onProgress)
            }
            stage = StorageContentPlatform.createStage(taskKey, "download:$localPath").getOrThrow()
            val expectedIdentity = StorageContentPlatform.stageIdentity(taskKey, stage).getOrThrow()
            var downloadFailure: Throwable? = null
            val result = try {
                val grant = TaskUploadSourceIdentity(frozen, stage, expectedIdentity, taskKey, StorageContentPlatform, true)
                withContext(grant) {
                    client.download(resolved?.target ?: file.storagePath, stage, resolved?.size ?: file.size, onProgress)
                }.also { downloadFailure = it.exceptionOrNull() }
            } catch (error: Throwable) {
                downloadFailure = error
                throw error
            } finally {
                finishStorageContentStageWrite(StorageContentPlatform, taskKey, stage, expectedIdentity, downloadFailure)
            }
            if (!result.getOrThrow()) return result
            StorageContentPlatform.publishDownload(connectionConfig(), taskKey, frozen, stage, localPath)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { Result.failure(error) }
        finally {
            stage?.let { path -> withContext(NonCancellable) { FileUtils.deleteFile(FileAccessPermission.Allowed, path) } }
        }
    }

    internal suspend fun uploadTaskFile(
        task: Task,
        source: FileSimpleInfo,
        target: FileSimpleInfo,
        ensureRunning: suspend () -> Unit,
        readRange: suspend (Long, Long) -> Result<ByteArray>,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Boolean>? {
        if (!client.capabilities.write) return writeUnsupported()
        publicStoragePathError(target.storagePath, true)?.let { return Result.failure(it) }
        return uploadTaskFileWithCheckpoint(
            task = task, taskState = taskState, provider = client, config = connectionConfig(), source = source, target = target,
            connectionId = protocolId, ensureRunning = ensureRunning, readRange = readRange,
            onProgress = onProgress,
        )
    }

    private suspend fun uploadLocalTaskFile(
        task: Task,
        source: FileSimpleInfo,
        target: FileSimpleInfo,
        ensureRunning: suspend () -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> {
        val current = FileUtils.getFile(FileAccessPermission.Allowed, source.path).getOrElse {
            return Result.failure(it)
        }.copy(protocol = source.protocol, protocolId = source.protocolId, storageReference = source.storageReference)
        return uploadTaskFile(
            task = task, source = current, target = target, ensureRunning = ensureRunning,
            readRange = { start, end ->
                ensureRunning()
                FileUtils.readFileRange(FileAccessPermission.Allowed, source.path, start, end)
            },
            onProgress = onProgress,
        ) ?: client.upload(source.path,
            StorageContentPlatform.resolveUploadTarget(connectionConfig(), target.storagePath).getOrElse { return Result.failure(it) },
            current.size, onProgress)
    }

    override suspend fun uploadFileFromLocal(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        publicStoragePathError(remotePath, true)?.let { return Result.failure(it) }
        rejectUnsafeLocalUploadSource(FileSimpleInfo.nullFileSimpleInfo().withCopy(path = localPath))?.let { return Result.failure(it) }
        return uploadForegroundLocal(localPath, remotePath, size, onProgress)
    }

    override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        publicStoragePathError(remotePath, true)?.let { return Result.failure(it) }
        return uploadManagedStorageContent(client, connectionConfig(), remotePath, size, onProgress = onProgress, readChunk = readChunk)
            ?: client.uploadFromSource(remotePath, size, onProgress, readChunk)
    }

    override suspend fun setModifiedTime(path: String, millis: Long): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        publicNetworkWritePathError(path)?.let { return Result.failure(it) }
        nativeContentOperationError(StoragePath(path))?.let { return Result.failure(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), StoragePath(path)).getOrElse { return Result.failure(it) }
        return client.setModifiedTime(path, millis)
    }

    override suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> {
        if (!client.capabilities.rename) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(oldName) || isUnsafeNetworkPathSegment(newName)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        (contentMetadataPathError(path) ?: unsafeNetworkWritePathError(path))?.let { return Result.failure(it) }
        val oldPath = joinPath(path, oldName)
        val newPath = joinPath(path, newName)
        publicNetworkWritePathError(oldPath)?.let { return Result.failure(it) }
        publicNetworkWritePathError(newPath)?.let { return Result.failure(it) }
        StorageContentPlatform.renameLogical(connectionConfig(), StoragePath(oldPath), StoragePath(newPath))
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), StoragePath(oldPath)).getOrElse { return Result.failure(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), StoragePath(newPath)).getOrElse { return Result.failure(it) }
        return client.rename(oldPath, newPath)
    }

    override suspend fun downloadFileToLocal(
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> {
        if (file.isDirectory) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_directory_editor_source_not_supported))
        }
        return downloadManagedFile(0L, file, localPath, onProgress)
    }

    override suspend fun uploadFileFromLocal(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> {
        if (menuPermission.write.not()) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported))
        }
        rejectUnsafeLocalUploadSource(
            FileSimpleInfo.nullFileSimpleInfo().withCopy(
                path = localPath,
                protocol = FileProtocol.Local,
                isDirectory = false,
            ),
        )?.let { error ->
            return Result.failure(error)
        }
        publicNetworkWritePathError(remotePath)?.let { error -> return Result.failure(error) }
        return uploadForegroundLocal(localPath, StoragePath(remotePath), size, onProgress)
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        if (menuPermission.write.not()) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported))
        }
        publicNetworkWritePathError(remotePath)?.let { error -> return Result.failure(error) }
        return uploadManagedStorageContent(
            client, connectionConfig(), StoragePath(remotePath), size, onProgress = onProgress, readChunk = readChunk,
        ) ?: client.uploadFromSource(remotePath, size, onProgress, readChunk)
    }

    private suspend fun rejectUnsafeLocalUploadSource(entry: FileSimpleInfo): Throwable? {
        // This unexported coroutine capability names only this copy's private staging file.
        val privateStage = currentCoroutineContext()[TaskUploadSourceIdentity]?.privateStagePath
        if (privateStage != entry.path) {
            SensitiveFileAccessPolicy.deniedException(entry.path)?.let { error -> return error }
        }
        if (entry.isSymbolicLink || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)) {
            return IllegalStateException(
                AppStrings.file_symbolic_link_copy_not_supported
            )
        }
        return null
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        if (!client.capabilities.delete) return writeUnsupported()
        publicNetworkWritePathError(path)?.let { return Result.failure(it) }
        StorageContentPlatform.deleteLogical(connectionConfig(), StoragePath(path), isDirectory)
            .getOrElse { return Result.failure(it) }?.let { return Result.success(it) }
        StorageContentPlatform.beforeMutation(connectionConfig(), StoragePath(path)).getOrElse { return Result.failure(it) }
        return client.delete(path, isDirectory)
    }

    override suspend fun createFolder(path: String, name: String): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(name)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        contentMetadataPathError(path)?.let { error -> return Result.failure(error) }
        return createFolder(StoragePath(joinPath(path, name)))
    }

    override suspend fun createFile(path: String, name: String): Result<Boolean> {
        if (!client.capabilities.write) return writeUnsupported()
        if (isUnsafeNetworkPathSegment(name)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        contentMetadataPathError(path)?.let { error -> return Result.failure(error) }
        return createFile(StoragePath(joinPath(path, name)))
    }

    override suspend fun downloadFileByChunks(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        if (srcFileSimpleInfo.isDirectory) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_directory_stream_relay_not_supported))
        }
        publicStoragePathError(srcFileSimpleInfo.storagePath)?.let { return Result.failure(it) }
        if (storageContentContains(connectionConfig(), srcFileSimpleInfo.storagePath) ||
            extras.contentEncryption == null && contentTransformAvailable()) {
            return downloadVerifiedStorageChunks(
                provider = client, config = connectionConfig(), taskKey = task.key, file = srcFileSimpleInfo,
                ensureRunning = {
                    if (!taskState.awaitIfPaused(task.key) || taskState.isTaskCancelled(task.key)) {
                        throw CancellationException(AppStrings.message_task_cancelled)
                    }
                },
                onChunk = onChunk,
            )
        }
        val chunkClient = client as? ChunkReadableNetworkClient
            ?: return Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_stream_relay_not_supported))
        return chunkClient.downloadByChunks(
            remotePath = srcFileSimpleInfo.storagePath,
            size = srcFileSimpleInfo.size,
            onChunk = onChunk,
        )
    }

    private fun writeUnsupported(): Result<Boolean> =
        Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Network) return false
        return name == other.name &&
                pathSeparator == other.pathSeparator &&
                protocol == other.protocol &&
                host == other.host &&
                username == other.username &&
                password == other.password &&
                pinned == other.pinned &&
                extras == other.extras
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + pathSeparator.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + pinned.hashCode()
        result = 31 * result + extras.hashCode()
        return result
    }

    override fun toString(): String {
        return "Network(name=$name, protocol=$protocol, pinned=$pinned)"
    }
}

private class NetworkProgressTracker(
    private val task: Task,
    private val destPath: String,
    private val totalBytes: Long,
    private val totalBlocks: Int,
    private val taskState: TaskState,
) {
    private val startMs = getTimeMillis()
    private var lastLogMs = startMs
    private var doneBytes = 0L
    private val rateSampler = TaskProgressRateSampler(initialAt = startMs)

    fun update(doneBytes: Long, message: String) {
        taskState.recordRuntimeByteProgress(task, destPath, doneBytes)
        this.doneBytes = doneBytes.coerceAtLeast(0L)
        val now = getTimeMillis()
        if (now - lastLogMs < NETWORK_PROGRESS_INTERVAL_MS && doneBytes < totalBytes) {
            return
        }
        val rateSample = rateSampler.update(
            completed = this.doneBytes,
            total = totalBytes,
            now = now,
            force = totalBytes > 0L && this.doneBytes >= totalBytes,
        )
        val text = if (totalBytes > 0) {
            val doneBlocks = ((this.doneBytes + NETWORK_PROGRESS_BLOCK_SIZE - 1) / NETWORK_PROGRESS_BLOCK_SIZE)
                .coerceAtMost(totalBlocks.toLong())
            AppStrings.message_task_progress_with_metrics.format(
                progress = message,
                percent = this.doneBytes.formatPercent(totalBytes),
                current = doneBlocks.toString(),
                total = totalBlocks.toString(),
                speed = rateSample.speedPerSecond.formatSpeed(),
                remaining = rateSample.etaMs.formatDuration(),
            )
        } else {
            AppStrings.message_task_progress_with_bytes.format(
                progress = message,
                bytes = this.doneBytes.formatBytes(),
                speed = rateSample.speedPerSecond.formatSpeed(),
                remaining = rateSample.etaMs.formatDuration(),
            )
        }
        taskState.putResult(task, destPath, text)
        lastLogMs = now
    }
}

fun Network.buildProtocolId(): String {
    val base = buildBaseProtocolId()
    val ref = extras.contentEncryption ?: return base
    val cloud = extras.cloud
    val identity = Json.encodeToString(listOf(base, cloud.connectionId, cloud.accountId, cloud.rootId, cloud.libraryId,
        ref.formatVersion.toString(), ref.vaultId, Json.encodeToString(StoragePath.serializer(), ref.root)))
    val hash = SHA256()
    hash.update(identity.encodeToByteArray())
    return "$base:content:" + hash.digest().hexLower
}

private fun Network.buildBaseProtocolId(): String {
    if (protocol in setOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile") && extras.cloud.connectionId.isNotBlank()) return "network:$protocol:${extras.cloud.connectionId}"
    val protocolSegment = when (protocol) {
        NetworkProtocol.SMB.name -> extras.smb.share.takeIf { item -> item.isNotBlank() }
        NetworkProtocol.S3.name -> extras.s3.bucket.takeIf { item -> item.isNotBlank() }
        NetworkProtocol.NFS.name -> "${extras.nfs.version}:${extras.nfs.exportPath}:${extras.nfs.uid}:${extras.nfs.gid}:${extras.nfs.groups.joinToString(",")}"
        else -> null
    }
    val suffix = protocolSegment?.let { item -> ":$item" }.orEmpty()
    return "network:$protocol:$host:$username$suffix"
}
