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
import com.folderspan.service.network.ChunkReadableNetworkClient
import com.folderspan.service.network.NetworkClient
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

private const val NETWORK_PROGRESS_BLOCK_SIZE = 1024 * 1024L
private const val NETWORK_PROGRESS_INTERVAL_MS = 1000L
enum class NetworkProtocol {
    FTP,
    SFTP,
    SMB,
    WebDav,
    S3,
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
        get() = when (protocol) {
            NetworkProtocol.FTP.name,
            NetworkProtocol.SFTP.name,
            NetworkProtocol.SMB.name,
            NetworkProtocol.WebDav.name,
            NetworkProtocol.S3.name -> DiskMenuPermission(
                read = true,
                write = true,
                paste = true,
                copy = true,
                move = true,
                delete = true,
                rename = true,
                setting = false,
                favorite = true,
                share = false,
                info = true,
            )
            else -> DiskMenuPermission()
        }

    protected open val client: NetworkClient by lazy { NetworkClientFactory.create(this) }
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
        return client.list(path, requestId, batchId)
            .map { list -> list.map(::buildFileEntry) }
    }

    override fun traverse(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> = flow {
        val pendingDirectories = ArrayDeque<String>()
        val visitedDirectories = mutableSetOf<String>()
        pendingDirectories.add(path)
        while (pendingDirectories.isNotEmpty()) {
            val currentPath = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(currentPath)) continue
            val listResult = getList(currentPath, requestId, batchId)
            emit(listResult)
            val entries = listResult.getOrNull() ?: continue
            if (entries.any { item -> item.isDirectory && !item.isSymbolicLinkKnown }) {
                emit(Result.failure(IllegalStateException(AppStrings.network_untrusted_symbolic_link_metadata)))
                return@flow
            }
            entries
                .asSequence()
                .filter { item -> item.isDirectory && !item.isSymbolicLink }
                .forEach { item -> pendingDirectories.add(item.path) }
        }
    }

    override fun getRootPaths(): List<PathInfo> {
        return listOf(PathInfo(pathSeparator, 0L, 0L))
    }

    override fun getFile(path: String): Result<FileSimpleInfo> {
        val info = FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
            protocol = FileProtocol.Network,
            protocolId = protocolId,
        )
        return Result.success(info)
    }

    override suspend fun copyFileWithinEndpoint(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
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
        if (srcFileSimpleInfo.path == destFileSimpleInfo.path) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_source_target_cannot_same))
        }
        if (!taskState.awaitIfPaused(task.key) || taskState.isTaskCancelled(task.key)) {
            return Result.failure(CancellationException(AppStrings.message_task_cancelled))
        }

        updateTaskDetails(task, srcFileSimpleInfo.path, destFileSimpleInfo.path)
        updateTaskPath(task, destFileSimpleInfo.path)
        updateTaskProgress(task, 0, 1)
        taskState.putResult(task, destFileSimpleInfo.path, AppStrings.ui_copying)

        val result = client.copyFile(
            sourcePath = srcFileSimpleInfo.path,
            targetPath = destFileSimpleInfo.path,
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
                        val result = client.upload(
                            localPath = srcFileSimpleInfo.path,
                            remotePath = destFileSimpleInfo.path,
                            size = srcFileSimpleInfo.size,
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
                        val rootCreateResult = client.createFolder(destFileSimpleInfo.path)
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
                            val result = client.createFolder(remotePath)
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
                            val result = client.upload(
                                localPath = entry.path,
                                remotePath = remotePath,
                                size = entry.size,
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
                        val result = client.download(
                            remotePath = srcFileSimpleInfo.path,
                            localPath = destFileSimpleInfo.path,
                            size = srcFileSimpleInfo.size,
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
                                client.list(directory.path).map { entries ->
                                    entries.map(::buildFileEntry)
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
                                ),
                                dest = buildRetryFileSimpleInfo(
                                    path = path,
                                    protocol = destFileSimpleInfo.protocol,
                                    protocolId = destFileSimpleInfo.protocolId,
                                    isDirectory = isDirectory,
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

                        fun buildLocalPath(remotePath: String): String {
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
                            val result = client.download(
                                remotePath = entry.path,
                                localPath = localPath,
                                size = entry.size,
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

    override suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> {
        if (isUnsafeNetworkPathSegment(oldName) || isUnsafeNetworkPathSegment(newName)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        unsafeNetworkWritePathError(path)?.let { error -> return Result.failure(error) }
        val oldPath = joinPath(path, oldName)
        val newPath = joinPath(path, newName)
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
        if (file.size == 0L) {
            return FileUtils.createFile(FileAccessPermission.Allowed, localPath)
        }
        return client.download(
            remotePath = file.path,
            localPath = localPath,
            size = file.size,
            onProgress = onProgress,
        )
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
        unsafeNetworkWritePathError(remotePath)?.let { error -> return Result.failure(error) }
        return client.upload(
            localPath = localPath,
            remotePath = remotePath,
            size = size,
            onProgress = onProgress,
        )
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
        unsafeNetworkWritePathError(remotePath)?.let { error -> return Result.failure(error) }
        return client.uploadFromSource(
            remotePath = remotePath,
            size = size,
            onProgress = onProgress,
            readChunk = readChunk,
        )
    }

    private fun rejectUnsafeLocalUploadSource(entry: FileSimpleInfo): Throwable? {
        SensitiveFileAccessPolicy.deniedException(entry.path)?.let { error -> return error }
        if (entry.isSymbolicLink || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)) {
            return IllegalStateException(
                AppStrings.file_symbolic_link_copy_not_supported
            )
        }
        return null
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return client.delete(path, isDirectory)
    }

    override suspend fun createFolder(path: String, name: String): Result<Boolean> {
        if (isUnsafeNetworkPathSegment(name)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        unsafeNetworkWritePathError(path)?.let { error -> return Result.failure(error) }
        return client.createFolder(joinPath(path, name))
    }

    override suspend fun createFile(path: String, name: String): Result<Boolean> {
        if (isUnsafeNetworkPathSegment(name)) {
            return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
        }
        unsafeNetworkWritePathError(path)?.let { error -> return Result.failure(error) }
        return client.createFile(joinPath(path, name))
    }

    override suspend fun downloadFileByChunks(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        if (srcFileSimpleInfo.isDirectory) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_directory_stream_relay_not_supported))
        }
        val chunkClient = client as? ChunkReadableNetworkClient
            ?: return Result.failure(NetworkUnsupportedException(AppStrings.network_protocol_stream_relay_not_supported))
        return chunkClient.downloadByChunks(
            remotePath = srcFileSimpleInfo.path,
            size = srcFileSimpleInfo.size,
            onChunk = onChunk,
        )
    }

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
        return "Network(name=$name, pathSeparator=$pathSeparator, protocol=$protocol, host=$host, username=$username, password=$password, pinned=$pinned, extras=$extras)"
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
    val protocolSegment = when (protocol) {
        NetworkProtocol.SMB.name -> extras.smb.share.takeIf { item -> item.isNotBlank() }
        NetworkProtocol.S3.name -> extras.s3.bucket.takeIf { item -> item.isNotBlank() }
        else -> null
    }
    val suffix = protocolSegment?.let { item -> ":$item" }.orEmpty()
    return "network:$protocol:$host:$username$suffix"
}
