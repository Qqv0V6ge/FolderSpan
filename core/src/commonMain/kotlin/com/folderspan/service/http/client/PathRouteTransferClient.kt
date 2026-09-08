package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.*
import com.folderspan.service.data.*
import com.folderspan.service.network.isUnsafeRemoteListEntry
import com.folderspan.service.operation.*
import com.folderspan.ui.state.file.resolveDirectoryCopyTargetPath
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

internal class PathRouteTransferClient(
    private val manager: HttpRouteClientManager,
    private val deviceState: DeviceState,
    private val taskState: TaskState,
    private val transferStatusProvider: () -> HttpTransferStatus,
    private val listPathBlock: suspend (ListRequest, String?) -> Result<List<FileSimpleInfo>>,
    private val ensureCopyActiveBlock: suspend (Task, FileSimpleInfo?, FileSimpleInfo?) -> Unit,
) {
    private fun transferStatus(): HttpTransferStatus = transferStatusProvider()

    private suspend fun listPath(
        request: ListRequest,
        batchId: String? = null,
    ): Result<List<FileSimpleInfo>> {
        return listPathBlock(request, batchId)
    }

    private suspend fun ensureCopyActive(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo? = null,
        destFileSimpleInfo: FileSimpleInfo? = null,
    ) {
        ensureCopyActiveBlock(task, srcFileSimpleInfo, destFileSimpleInfo)
    }

    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo
    ): Result<Boolean> {
        try {
        LogKit.d("copyFile: $srcFileSimpleInfo -> $destFileSimpleInfo")
        var successCount = 0
        var failureCount = 0
        var rejectedEntryCount = 0
        var firstFailedPath: String? = null
        var firstError: String? = null

        fun buildTrackedEntry(srcPath: String, destPath: String, isDirectory: Boolean) = buildCopyRetryEntry(
            taskType = task.taskType,
            src = buildRetryFileSimpleInfo(
                path = srcPath,
                protocol = srcFileSimpleInfo.protocol,
                protocolId = srcFileSimpleInfo.protocolId,
                isDirectory = isDirectory,
            ),
            dest = buildRetryFileSimpleInfo(
                path = destPath,
                protocol = destFileSimpleInfo.protocol,
                protocolId = destFileSimpleInfo.protocolId,
                isDirectory = isDirectory,
            ),
        )

        fun markCopySuccess(srcPath: String, destPath: String, isDirectory: Boolean) {
            taskState.removeResult(task, destPath)
        }

        fun markCopyFailure(
            srcPath: String,
            destPath: String,
            isDirectory: Boolean,
            message: String,
            fallback: String,
        ): String {
            return taskState.recordRetryFailure(
                task = task,
                entry = buildTrackedEntry(srcPath, destPath, isDirectory),
                message = message,
                fallback = fallback,
            )
        }

        suspend fun ensureActive() {
            ensureCopyActive(task, srcFileSimpleInfo, destFileSimpleInfo)
        }

        fun sourceCopySeparator(): String {
            return if (srcFileSimpleInfo.protocol == FileProtocol.Local) {
                PathUtils.getPathSeparator().ifBlank { "/" }
            } else {
                "/"
            }
        }

        fun destCopySeparator(): String {
            return if (destFileSimpleInfo.protocol == FileProtocol.Local) {
                PathUtils.getPathSeparator().ifBlank { "/" }
            } else {
                "/"
            }
        }

        fun resolveCopyTarget(sourcePath: String): String {
            return resolveDirectoryCopyTargetPath(
                sourceRoot = srcFileSimpleInfo.path,
                sourcePath = sourcePath,
                destRoot = destFileSimpleInfo.path,
                sourceSeparator = sourceCopySeparator(),
                destSeparator = destCopySeparator(),
                destIsLocal = destFileSimpleInfo.protocol == FileProtocol.Local,
            )
        }

        fun rejectUnsafeListEntries(entries: List<FileSimpleInfo>) {
            if (entries.any { entry -> isUnsafeRemoteListEntry(entry.name, entry.path) }) {
                throw AuthorityException(AppStrings.ui_remote_path_invalid)
            }
        }

        fun recordRejectedEntry(entry: FileSimpleInfo, error: Throwable) {
            val targetPath = runCatching { resolveCopyTarget(entry.path) }
                .getOrDefault(destFileSimpleInfo.path)
            val message = error.message.orEmpty().ifBlank { AppStrings.file_symbolic_link_copy_not_supported }
            markCopyFailure(
                srcPath = entry.path,
                destPath = targetPath,
                isDirectory = entry.isDirectory,
                message = message,
                fallback = AppStrings.ui_copy_failed,
            )
            failureCount++
            rejectedEntryCount++
            if (firstFailedPath == null) firstFailedPath = targetPath
            if (firstError == null) firstError = message
        }

        suspend fun collectSourceTraversalEntries(): List<FileSimpleInfo> {
            var discoveredEntries = 0
            val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
            fun updateDiscovered(entries: List<FileSimpleInfo>) {
                discoveredEntries += entries.size
                taskState.putCopyScanProgress(task, discoveredEntries)
            }

            return when (srcFileSimpleInfo.protocol) {
                FileProtocol.Local -> collectDirectoryEntriesAdaptive(
                    root = srcFileSimpleInfo,
                    config = resolveTraversalParallelism(TraversalEndpointKind.Local),
                    pathSeparator = PathUtils.getPathSeparator(),
                    ensureRunning = { ensureActive() },
                    onScanProgress = scanProgressPublisher,
                    onEntriesDiscovered = { entries -> updateDiscovered(entries) },
                    dynamicMaxParallelismProvider = {
                        resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Local)
                    },
                    rejectSymbolicLinkEntries = true,
                    onRejectedEntry = ::recordRejectedEntry,
                ) { directory ->
                    PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).map { entries ->
                        entries.map { entry -> entry.withCopy(protocol = FileProtocol.Local, protocolId = "") }
                    }
                }

                FileProtocol.Device -> {
                    fun currentTransferStatus() = transferStatus()
                        .clamped()
                        .takeIf { status -> status.sampledAtMillis > 0L }
                    fun isRemoteBusy(): Boolean {
                        val status = currentTransferStatus() ?: return false
                        return status.busy ||
                            (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
                    }
                    val transferStatus = currentTransferStatus()
                    collectDirectoryEntriesAdaptive(
                        root = srcFileSimpleInfo,
                        config = resolveTraversalParallelism(
                            endpointKind = TraversalEndpointKind.Device,
                            remoteRecommendedParallelism = transferStatus?.recommendedParallelRequests,
                            remoteBusy = isRemoteBusy(),
                        ),
                        pathSeparator = "/",
                        ensureRunning = { ensureActive() },
                        onScanProgress = scanProgressPublisher,
                        onEntriesDiscovered = { entries -> updateDiscovered(entries) },
                        dynamicMaxParallelismProvider = {
                            val latestStatus = currentTransferStatus()
                            resolveTraversalRuntimeMaxParallelism(
                                endpointKind = TraversalEndpointKind.Device,
                                remoteRecommendedParallelism = latestStatus?.recommendedParallelRequests,
                                remoteBusy = isRemoteBusy(),
                            )
                        },
                        requireKnownSymbolicLinkMetadata = true,
                        rejectSymbolicLinkEntries = true,
                        onRejectedEntry = ::recordRejectedEntry,
                    ) { directory ->
                        listPath(
                            request = ListRequest(directory.path),
                            batchId = "path-copy-scan:${task.key}",
                        ).map { entries ->
                            rejectUnsafeListEntries(entries)
                            entries.map { entry ->
                                entry.withCopy(protocol = FileProtocol.Device, protocolId = srcFileSimpleInfo.protocolId)
                            }
                        }
                    }
                }

                else -> emptyList()
            }
        }

        val progressMutex = Mutex()
        val deviceTransferSemaphore = Semaphore(DEVICE_COPY_MAX_CONCURRENT_REQUESTS)

        fun fileClientForDeviceProtocol(protocolId: String): FileRouteClient? {
            if (protocolId.isBlank()) return null
            if (srcFileSimpleInfo.protocol == FileProtocol.Device && srcFileSimpleInfo.protocolId == protocolId) {
                return manager.fileRouteClient
            }
            return deviceState.socketDevices
                .firstOrNull { device -> device.id == protocolId && device.httpClient != null }
                ?.httpClient
                ?.fileRouteClient
        }

        fun currentOperationTransferStatuses() = listOfNotNull(
            if (srcFileSimpleInfo.protocol == FileProtocol.Device) {
                fileClientForDeviceProtocol(srcFileSimpleInfo.protocolId)
            } else {
                null
            }?.transferStatus()?.clamped()?.takeIf { status -> status.sampledAtMillis > 0L },
            if (destFileSimpleInfo.protocol == FileProtocol.Device) {
                fileClientForDeviceProtocol(destFileSimpleInfo.protocolId)
            } else {
                null
            }?.transferStatus()?.clamped()?.takeIf { status -> status.sampledAtMillis > 0L },
        )

        fun currentOperationRemoteRecommended() =
            currentOperationTransferStatuses().minOfOrNull { status -> status.recommendedParallelRequests }

            fun currentOperationRemoteBusy() = currentOperationTransferStatuses().any { status ->
            status.busy || (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
        }

        fun recordFailure(path: String, message: String, isDirectory: Boolean) {
            val errorText = message.ifBlank { AppStrings.ui_copy_failed }
            val relativePath = path.removePrefix(destFileSimpleInfo.path)
            val resolvedSrcPath = if (relativePath.isBlank()) {
                srcFileSimpleInfo.path
            } else {
                srcFileSimpleInfo.path + relativePath
            }
            markCopyFailure(
                srcPath = resolvedSrcPath,
                destPath = path,
                isDirectory = isDirectory,
                message = errorText,
                fallback = if (isDirectory) AppStrings.ui_folder_creation_failed else AppStrings.ui_copy_failed,
            )
            failureCount++
            if (firstFailedPath == null) {
                firstFailedPath = path
            }
            if (firstError == null) {
                firstError = errorText
            }
        }

        suspend fun recordEntrySuccess(srcPath: String, destPath: String, isDirectory: Boolean) {
            progressMutex.withLock {
                successCount++
                markCopySuccess(srcPath, destPath, isDirectory)
                taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
            }
        }

        suspend fun recordEntryFailure(path: String, message: String, isDirectory: Boolean) {
            progressMutex.withLock {
                recordFailure(path, message, isDirectory)
                taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
            }
        }

        // 只复制一个文件
        if (!srcFileSimpleInfo.isDirectory) {
            ensureActive()
            val result = writeBytes(
                task,
                srcFileSimpleInfo,
                destFileSimpleInfo,
                FileSimpleInfo.nullFileSimpleInfo().copy(
                    name = srcFileSimpleInfo.name,
                    description = srcFileSimpleInfo.description,
                    isDirectory = srcFileSimpleInfo.isDirectory,
                    isHidden = srcFileSimpleInfo.isHidden,
                    mineType = srcFileSimpleInfo.mineType,
                    size = srcFileSimpleInfo.size,
                    createdDate = srcFileSimpleInfo.createdDate,
                    updatedDate = srcFileSimpleInfo.updatedDate,
                ),
                sharedTransferSemaphore = deviceTransferSemaphore,
                allowDuplexDeviceTransfer = true,
            ) { current, total ->
                taskState.putValue(task, "progressMax", total.toString())
                taskState.putValue(task, "progressCur", current.toString())
            }
            if (result.isSuccess && result.getOrDefault(false)) {
                markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, false)
            } else if (result.isFailure) {
                markCopyFailure(
                    srcFileSimpleInfo.path,
                    destFileSimpleInfo.path,
                    false,
                    result.exceptionOrNull()?.message.orEmpty(),
                    AppStrings.ui_copy_failed,
                )
            }
            return result
        }

        // 只复制一个空文件夹
        if (srcFileSimpleInfo.isDirectory && srcFileSimpleInfo.size == 0L) {
            ensureActive()
            if (destFileSimpleInfo.protocol == FileProtocol.Device) {
                val socketDevice =
                    deviceState.socketDevices.firstOrNull { device -> device.id == destFileSimpleInfo.protocolId && device.httpClient != null }
                if (socketDevice == null) {
                    return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    )
                }
                val fileService: FileRouteClient = socketDevice.httpClient!!.fileRouteClient

                taskState.putValue(task, "progressMax", "1")
                taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                val result = fileService.createFolders(
                    listOf(destFileSimpleInfo.path)
                )

                taskState.putValue(task, "progressCur", "1")
                return result.fold(
                    onSuccess = { results ->
                        if (results.isNotEmpty()) {
                            val first = results.first()
                            if (first.isSuccess && first.getOrDefault(false)) {
                                taskState.removeResult(task, "")
                                markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, true)
                            } else {
                                markCopyFailure(
                                    srcFileSimpleInfo.path,
                                    destFileSimpleInfo.path,
                                    true,
                                    first.exceptionOrNull()?.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                                    AppStrings.ui_folder_creation_failed,
                                )
                            }
                            first
                        } else {
                            markCopyFailure(srcFileSimpleInfo.path, destFileSimpleInfo.path, true, AppStrings.ui_folder_creation_failed, AppStrings.ui_folder_creation_failed)
                            Result.success(false)
                        }
                    },
                    onFailure = { item ->
                        markCopyFailure(
                            srcFileSimpleInfo.path,
                            destFileSimpleInfo.path,
                            true,
                            item.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                            AppStrings.ui_folder_creation_failed,
                        )
                        Result.failure(item)
                    }
                )
            }

            taskState.putValue(task, "progressMax", "1")
            taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
            val result = FileUtils.createFolder(FileAccessPermission.Allowed, destFileSimpleInfo.path)
            taskState.putValue(task, "progressCur", "1")
            return result.fold(
                onSuccess = { status ->
                    if (status) {
                        taskState.removeResult(task, "")
                        markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, true)
                    } else {
                        markCopyFailure(srcFileSimpleInfo.path, destFileSimpleInfo.path, true, AppStrings.ui_folder_creation_failed, AppStrings.ui_folder_creation_failed)
                    }
                    Result.success(status)
                },
                onFailure = { item ->
                    markCopyFailure(
                        srcFileSimpleInfo.path,
                        destFileSimpleInfo.path,
                        true,
                        item.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                        AppStrings.ui_folder_creation_failed,
                    )
                    Result.failure(item)
                }
            )
        }

        // 获取本地所有的文件和文件夹
        if (srcFileSimpleInfo.protocol == FileProtocol.Local) {
            taskState.putValue(task, "progressMax", "1")
            // 只有一个文件夹或文件
            if (srcFileSimpleInfo.size == 0L) {
                ensureActive()
                if (srcFileSimpleInfo.isDirectory) {
                    taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                }
                val result = if (srcFileSimpleInfo.isDirectory)
                    FileUtils.createFolder(FileAccessPermission.Allowed, destFileSimpleInfo.path)
                else
                    FileUtils.createFile(FileAccessPermission.Allowed, destFileSimpleInfo.path)

                taskState.putValue(task, "progressCur", "1")
                if (srcFileSimpleInfo.isDirectory && result.isSuccess && result.getOrDefault(false)) {
                    taskState.removeResult(task, "")
                    markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, true)
                }
                result.onFailure { failure ->
                    markCopyFailure(
                        srcFileSimpleInfo.path,
                        destFileSimpleInfo.path,
                        srcFileSimpleInfo.isDirectory,
                        failure.message?.ifBlank {
                            if (srcFileSimpleInfo.isDirectory) AppStrings.ui_folder_creation_failed else AppStrings.ui_write_failed
                        } ?: if (srcFileSimpleInfo.isDirectory) AppStrings.ui_folder_creation_failed else AppStrings.ui_write_failed,
                        if (srcFileSimpleInfo.isDirectory) AppStrings.ui_folder_creation_failed else AppStrings.ui_write_failed,
                    )
                }
                return result
            }
        }

        if (destFileSimpleInfo.isDirectory) {
            ensureActive()
            if (destFileSimpleInfo.protocol == FileProtocol.Local) {
                ensureActive()
                taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                val createFolder = FileUtils.createFolder(FileAccessPermission.Allowed, destFileSimpleInfo.path)
                taskState.putValue(task, "progressCur", "1")
                if (createFolder.isFailure) {
                    markCopyFailure(
                        srcFileSimpleInfo.path,
                        destFileSimpleInfo.path,
                        true,
                        createFolder.exceptionOrNull()?.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                        AppStrings.ui_folder_creation_failed,
                    )
                    return Result.failure(createFolder.exceptionOrNull() ?: Exception())
                } else {
                    markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, true)
                }
            }

            if (destFileSimpleInfo.protocol == FileProtocol.Device) {
                ensureActive()
                taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                var fileService: FileRouteClient = manager.fileRouteClient
                if (srcFileSimpleInfo.protocol == FileProtocol.Device) {
                    ensureActive()
                    val socketDevice =
                        deviceState.socketDevices
                            .firstOrNull { device -> device.id == destFileSimpleInfo.protocolId && device.httpClient != null }
                    if (socketDevice == null) {
                        return Result.failure(
                            DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                        )
                    }
                    fileService = socketDevice.httpClient!!.fileRouteClient
                }

                val path = destFileSimpleInfo.path
                val createFolder = fileService.createFolders(listOf(path))
                if (!createFolder.isSuccess) {
                    markCopyFailure(
                        srcFileSimpleInfo.path,
                        destFileSimpleInfo.path,
                        true,
                        createFolder.exceptionOrNull()?.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                        AppStrings.ui_folder_creation_failed,
                    )
                    return Result.failure(createFolder.exceptionOrNull() ?: Exception())
                } else {
                    val results = createFolder.getOrDefault(listOf())
                    if (results.isNotEmpty()) {
                        if (!results.first().isSuccess) {
                            markCopyFailure(
                                srcFileSimpleInfo.path,
                                destFileSimpleInfo.path,
                                true,
                                results.first().exceptionOrNull()?.message?.ifBlank { AppStrings.ui_folder_creation_failed } ?: AppStrings.ui_folder_creation_failed,
                                AppStrings.ui_folder_creation_failed,
                            )
                            return Result.failure(results.first().exceptionOrNull() ?: Exception())
                        }
                        markCopySuccess(srcFileSimpleInfo.path, destFileSimpleInfo.path, true)
                    } else {
                        markCopyFailure(srcFileSimpleInfo.path, destFileSimpleInfo.path, true, AppStrings.ui_folder_creation_failed, AppStrings.ui_folder_creation_failed)
                        return Result.failure(Exception(AppStrings.ui_folder_creation_failed))
                    }
                }
            }
        }

        taskState.putCopyScanProgress(task)
        val traversalEntries: List<FileSimpleInfo>

        try {
            traversalEntries = collectSourceTraversalEntries()
            traversalEntries.forEach { entry ->
                resolveCopyTarget(entry.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isTaskLevelTransferFailure()) {
                return Result.failure(e)
            }
            if (e.isMissingSourceFileFailure()) {
                LogKit.w(AppStrings.ui_source_path_missing_skip_copy_arg0_destination_arg1.format(arg0 = (srcFileSimpleInfo.path), arg1 = (destFileSimpleInfo.path)))
                taskState.removeResult(task, destFileSimpleInfo.path)
                return Result.success(true)
            }
            markCopyFailure(
                srcFileSimpleInfo.path,
                destFileSimpleInfo.path,
                true,
                e.message ?: AppStrings.ui_failed_to_traverse,
                if (srcFileSimpleInfo.protocol == FileProtocol.Device) AppStrings.ui_failed_to_traverse else AppStrings.ui_read_failed,
            )
            return Result.failure(e)
        }

        val plannedEntries = traversalEntries.size + rejectedEntryCount
        val resolvedProgressMax = plannedEntries.coerceAtLeast(1)
        taskState.putValue(task, "progressMax", resolvedProgressMax.toString())
        taskState.putValue(task, "progressCur", "0")

        suspend fun createDirectories(directories: List<FileSimpleInfo>) {
            if (directories.isNotEmpty()) {
                var fileService: FileRouteClient = manager.fileRouteClient
                if (srcFileSimpleInfo.protocol == FileProtocol.Device && destFileSimpleInfo.protocol == FileProtocol.Device) {
                    val socketDevice =
                        deviceState.socketDevices
                            .firstOrNull { device -> device.id == destFileSimpleInfo.protocolId && device.httpClient != null }
                    if (socketDevice == null) {
                        taskState.removeResult(task, "")
                        throw DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    }
                    fileService = socketDevice.httpClient!!.fileRouteClient
                }

                val directoryTargets = directories.map { entry ->
                    entry to resolveCopyTarget(entry.path)
                }
                for (chunk in directoryTargets.chunked(30)) {
                    ensureActive()
                    if (destFileSimpleInfo.protocol == FileProtocol.Local) {
                        for ((entry, targetPath) in chunk) {
                            ensureActive()
                            taskState.putCreatingFolderProgress(task, targetPath)
                            val result = FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
                            if (result.isSuccess && result.getOrDefault(false)) {
                                recordEntrySuccess(entry.path, targetPath, true)
                            } else {
                                recordEntryFailure(
                                    targetPath,
                                    result.exceptionOrNull()?.message.orEmpty().ifBlank { AppStrings.ui_folder_creation_failed },
                                    true
                                )
                            }
                        }
                    } else if (destFileSimpleInfo.protocol == FileProtocol.Device) {
                        chunk.forEach { (_, targetPath) ->
                            taskState.putCreatingFolderProgress(task, targetPath)
                        }
                        val paths = chunk.map { (_, targetPath) -> targetPath }
                        val result = fileService.createFolders(paths)
                        if (result.isSuccess) {
                            result.getOrDefault(emptyList()).forEachIndexed { index, item ->
                                ensureActive()
                                val (entry, targetPath) = chunk.getOrNull(index) ?: return@forEachIndexed
                                if (item.isSuccess && item.getOrDefault(false)) {
                                    recordEntrySuccess(entry.path, targetPath, true)
                                } else {
                                    recordEntryFailure(
                                        targetPath,
                                        item.exceptionOrNull()?.message.orEmpty().ifBlank { AppStrings.ui_folder_creation_failed },
                                        true
                                    )
                                }
                            }
                        } else {
                            paths.forEach { targetPath ->
                                recordEntryFailure(
                                    targetPath,
                                    result.exceptionOrNull()?.message.orEmpty().ifBlank { AppStrings.ui_folder_creation_failed },
                                    true
                                )
                            }
                        }
                    }
                }
                taskState.removeResult(task, "")
            }
        }

        suspend fun copyFileEntry(file: FileSimpleInfo) {
            ensureActive()
            val targetPath = resolveCopyTarget(file.path)
            val result = writeBytes(
                task,
                srcFileSimpleInfo.withCopy(path = file.path),
                destFileSimpleInfo.withCopy(path = targetPath),
                file.withCopy(path = ""),
                sharedTransferSemaphore = deviceTransferSemaphore,
            )
            if (result.getOrNull() == true) {
                recordEntrySuccess(file.path, targetPath, false)
            } else {
                recordEntryFailure(
                    targetPath,
                    taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = targetPath,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_copy_failed,
                    ),
                    false,
                )
            }
        }

        suspend fun enqueueTraversalBatch(
            rawEntries: List<FileSimpleInfo>,
        ) {
            if (rawEntries.isEmpty()) return

            val normalizedEntries = rawEntries
                .sortedWith(
                    compareBy<FileSimpleInfo> { item -> !item.isDirectory }
                        .thenBy { item -> item.path.pathLevel() }
                )

            val directories = normalizedEntries.filter { item -> item.isDirectory }
            val files = normalizedEntries.filter { item -> !item.isDirectory }

            createDirectories(directories)
            val baseOperationConfig = resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = currentOperationRemoteRecommended(),
                remoteBusy = currentOperationRemoteBusy(),
            )
            processItemsAdaptive(
                items = files,
                config = baseOperationConfig,
                ensureRunning = { ensureActive() },
                dynamicMaxParallelismProvider = {
                    resolveOperationRuntimeMaxParallelism(
                        endpointKind = TraversalEndpointKind.Device,
                        remoteRecommendedParallelism = currentOperationRemoteRecommended(),
                        remoteBusy = currentOperationRemoteBusy(),
                    )
                },
            ) { file ->
                copyFileEntry(file)
            }
        }

        if (plannedEntries == 0) {
            taskState.removeResult(task, "")
            taskState.putValue(task, "progressCur", resolvedProgressMax.toString())
            return Result.success(true)
        }

        try {
            enqueueTraversalBatch(traversalEntries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markCopyFailure(
                srcFileSimpleInfo.path,
                destFileSimpleInfo.path,
                true,
                e.message ?: AppStrings.ui_failed_to_traverse,
                if (srcFileSimpleInfo.protocol == FileProtocol.Device) AppStrings.ui_failed_to_traverse else AppStrings.ui_read_failed,
            )
            return Result.failure(e)
        }

        taskState.removeResult(task, "")
        if (failureCount > 0) {
            val summary = buildBatchTaskFailureMessage(
                operation = AppStrings.ui_copy,
                failureCount = failureCount,
                firstFailedPath = firstFailedPath,
                firstError = firstError,
            )
            LogKit.w(summary)
            return Result.success(successCount + failureCount == plannedEntries)
        }

        return Result.success(successCount == plannedEntries)
        } catch (e: CancellationException) {
            return Result.failure(e)
        }
    }

    private val writeTransferClient = PathRouteWriteTransferClient(
        manager = manager,
        deviceState = deviceState,
        taskState = taskState,
        ensureCopyActiveBlock = { task, src, dest ->
            ensureCopyActive(task, src, dest)
        },
    )

    suspend fun writeBytes(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
        fileSimpleInfo: FileSimpleInfo,
        sharedTransferSemaphore: Semaphore = Semaphore(DEVICE_COPY_MAX_CONCURRENT_REQUESTS),
        allowDuplexDeviceTransfer: Boolean = false,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null
    ): Result<Boolean> {
        return writeTransferClient.writeBytes(
            task = task,
            srcFileSimpleInfo = srcFileSimpleInfo,
            destFileSimpleInfo = destFileSimpleInfo,
            fileSimpleInfo = fileSimpleInfo,
            sharedTransferSemaphore = sharedTransferSemaphore,
            allowDuplexDeviceTransfer = allowDuplexDeviceTransfer,
            onProgress = onProgress,
        )
    }

}
