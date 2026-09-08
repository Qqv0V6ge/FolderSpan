package com.folderspan.data.main.device

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatPercent
import com.folderspan.extensions.formatSpeed
import com.folderspan.service.bookmark.DeviceBookmarkClient
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.file.*
import com.folderspan.service.http.client.FileRouteClient
import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.http.archive.SmallFileArchiveTransferCoordinator
import com.folderspan.service.http.archive.SmallFileArchiveTransportPreference
import com.folderspan.service.http.archive.completedArchiveTransferResult
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.delayForHttpTransferBackoff
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.service.operation.resolveOperationParallelism
import com.folderspan.service.operation.resolveOperationRuntimeMaxParallelism
import com.folderspan.service.operation.resolveTraversalParallelism
import com.folderspan.service.operation.resolveTraversalRuntimeMaxParallelism
import com.folderspan.exception.AuthorityException
import com.folderspan.service.network.isUnsafeRemoteListEntry
import com.folderspan.service.path.DevicePathClient
import com.folderspan.ui.state.file.resolveDirectoryCopyTargetPath
import com.folderspan.service.session.DEVICE_SESSION_MAX_PAYLOAD_BYTES
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private fun launchDeviceControlMonitor(block: suspend CoroutineScope.() -> Unit): Job {
    return CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(block = block)
}

@Serializable
enum class DeviceType(type: String) {
    Android("Android"),
    IOS("IOS"),
    JVM("JVM"),
    JS("JS")
}

/**
 * 设备连接类型的枚举类，用于表示设备的连接状态。
 * @param type 设备连接类型的字符串标识。
 */
@Serializable
enum class DeviceConnectType(type: String) {
    /**
     * 表示设备连接的自动连接类型。
     *
     * AUTO_CONNECT 枚举值用于指示设备可以自动连接，无需进一步用户交互。
     */
    AUTO_CONNECT("AUTO_CONNECT"),

    /**
     * 表示设备连接状态为永久禁止连接。
     *
     * 用于指代设备被明确禁止连接到当前系统的状态。
     * 一旦设置为此状态,设备将不会再请求或尝试建立连接。
     */
    PERMANENTLY_BANNED("PERMANENTLY_BANNED"),

    /**
     * 表示设备连接已被批准的状态。
     */
    APPROVED("APPROVED"),

    /**
     * 表示设备连接已被拒绝的状态。
     *
     * REJECTED 枚举值用于指代设备的连接请求已经被明确拒绝。
     * 设备在此状态下不能建立连接，可能需要用户执行额外操作以更改状态。
     */
    REJECTED("REJECTED"),

    /**
     * 表示设备连接处于等待状态。
     *
     * WAITING 枚举值用于指代设备当前正在等待连接的状态。
     */
    WAITING("WAITING")
}

/**
 * 表示设备类别的枚举类。
 *
 * 该枚举类用于区分设备是客户端类型还是服务器类型。
 */
enum class DeviceCategory(type: String) {
    /**
     * 代表设备类别中的客户端类型。
     * CLIENT 表明该设备是客户端设备。
     * 枚举常量用来区分不同的设备类型。
     */
    CLIENT("CLIENT"),

    /**
     * 表示设备类别中的服务器类型。
     * SERVER 是设备类别的枚举常量之一，代表服务器设备。
     */
    SERVER("SERVER")
}

data class Device(
    val id: String,
    override val name: String,
    override val pathSeparator: String,
    val host: MutableMap<String, HttpRouteClientManager>,
    val type: DeviceType,
    val token: String,
    val pathClient: DevicePathClient? = null,
    val bookmarkClient: DeviceBookmarkClient? = null,
    val fileClient: DeviceFileClient? = null,
    val transportType: DeviceTransportType = DeviceTransportType.Session,
) : DiskBase(), KoinComponent {
    override val menuPermission: DiskMenuPermission = DiskMenuPermission(
        read = true,
        write = true,
        paste = true,
        copy = true,
        move = true,
        delete = true,
        rename = true,
        setting = true,
        favorite = true,
        share = true,
        info = true,
    )

    private val deviceState: DeviceState by inject()
    private val taskState: TaskState by inject()

    private fun getConnect(): HttpRouteClientManager {
        return host.values.firstOrNull() ?: throw IllegalStateException(AppStrings.ui_the_device_has_not_established_an_http_connection)
    }

    val paths: PathOperations = PathOperations()
    val bookmarks: BookmarkOperations = BookmarkOperations()
    val files: FileOperations = FileOperations()

    inner class PathOperations {
        private fun getPathClient(): DevicePathClient {
            return pathClient ?: getConnect().pathRouteClient
        }

        /**
         * 返回 Device Server 路径列表接口最近上报的运行状态，供复制/移动/删除/属性遍历动态并发使用。
         */
        fun transferStatus(): HttpTransferStatus {
            return runCatching { getPathClient().transferStatus() }
                .getOrDefault(HttpTransferStatus.default())
        }

        suspend fun getRootPaths(): Result<List<PathInfo>> {
            return try {
                getPathClient().getRootPaths()
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_get_root_directory_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun getList(
            path: String,
            requestId: String? = null,
            batchId: String? = null,
        ): Result<List<FileSimpleInfo>> {
            return try {
                getPathClient().listPath(
                    request = ListRequest(path),
                    requestId = requestId,
                    batchId = batchId,
                ).map { entries ->
                    entries.filterNot { entry ->
                        isUnsafeRemoteListEntry(entry.name, entry.path)
                    }
                }
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_failed_to_retrieve_file_list_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        fun traverse(path: String): Flow<Result<List<FileSimpleInfo>>> {
            return try {
                getPathClient().traversePath(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_traversal_failed_arg0.format(arg0 = (e.message).toString()), e)
                flowOf(Result.failure(e))
            }
        }

        suspend fun exists(path: String): Result<Boolean> {
            return try {
                getPathClient().exists(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_check_path_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun createDirectory(path: String): Result<Boolean> {
            return try {
                getPathClient().createDirectory(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_creation_directory_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun deleteDirectory(path: String): Result<Boolean> {
            return try {
                getPathClient().deleteDirectory(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_delete_directory_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    inner class BookmarkOperations {
        private fun getBookmarkClient(): DeviceBookmarkClient {
            return bookmarkClient ?: getConnect().bookmarkRouteClient
        }

        suspend fun get(): Result<List<DrawerBookmark>> {
            return try {
                getBookmarkClient().getBookmarks()
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_retrieves_bookmark_failure_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun create(
            name: String,
            path: String,
            iconType: DrawerBookmarkType,
            iconPath: String = ""
        ): Result<Boolean> {
            try {
                return getBookmarkClient().createBookmark(name, path, iconType, iconPath)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_bookmark_creation_failed_arg0.format(arg0 = (e.message).toString()), e)
                return Result.failure(e)
            }
        }

        suspend fun update(
            id: Long,
            name: String,
            path: String,
            iconType: DrawerBookmarkType,
            iconPath: String = "",
        ): Result<Boolean> {
            try {
                return getBookmarkClient().updateBookmark(id, name, path, iconType, iconPath)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_update_reminder_failed_arg0.format(arg0 = (e.message).toString()), e)
                return Result.failure(e)
            }
        }

        suspend fun delete(id: Long): Result<Boolean> {
            try {
                return getBookmarkClient().deleteBookmark(id)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_delete_bookmark_failed_arg0.format(arg0 = (e.message).toString()), e)
                return Result.failure(e)
            }
        }

        suspend fun reorder(orderedIds: List<Long>): Result<Boolean> {
            return try {
                getBookmarkClient().reorderBookmarks(orderedIds)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_adjustment_order_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    inner class FileOperations {
        private fun getFileClient(): DeviceFileClient {
            return fileClient ?: getConnect().fileRouteClient
        }

        private fun resolveTransferDevice(deviceId: String): Device? {
            if (deviceId.isBlank()) return null
            return deviceState.resolveConnectedDevice(
                deviceId = deviceId,
                preferredDevice = this@Device.takeIf { it.id == deviceId }
            )
        }

        private fun Device.resolveTransferFileClient(): DeviceFileClient {
            return fileClient ?: host.values.firstOrNull()?.fileRouteClient
            ?: throw DeviceEndpointUnavailableException(AppStrings.message_task_device_disconnected)
        }

        private fun Device.peekTransferFileClient(): DeviceFileClient? {
            return fileClient ?: host.values.firstOrNull()?.fileRouteClient
        }

        private fun Device.resolveTransferPathClient(): DevicePathClient {
            return pathClient ?: host.values.firstOrNull()?.pathRouteClient
            ?: throw DeviceEndpointUnavailableException(AppStrings.message_task_device_disconnected)
        }

        private fun resolveTransportPipelineConfig(
            sourceDevice: Device?,
            targetDevice: Device?,
        ): DeviceTransportPipelineConfig {
            val sourceStatus = sourceDevice?.peekTransferFileClient()?.transferStatus()?.clamped()
            val targetStatus = targetDevice?.peekTransferFileClient()?.transferStatus()?.clamped()
            val statuses = listOfNotNull(sourceStatus, targetStatus)
            val runtimePlan = HttpTransferRuntimeTuning.plan(
                maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
                maxParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
            )
            val chunkSize = (
                statuses.map { status -> status.recommendedChunkBytes } +
                    runtimePlan.recommendedChunkBytes
                ).minOrNull() ?: runtimePlan.recommendedChunkBytes
            val safeChunkSize = chunkSize.coerceIn(1, DEVICE_DIRECT_MAX_LENGTH)
            val memoryBound = (HttpRouteClientManager.DEVICE_DIRECT_MAX_IN_FLIGHT_BYTES / safeChunkSize.toLong())
                .coerceAtLeast(1L)
                .coerceAtMost(HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS.toLong())
                .toInt()
            fun boundedParallelism(requested: Int?): Int {
                val candidate = requested ?: HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS
                return minOf(
                    candidate.coerceAtLeast(1),
                    memoryBound,
                    runtimePlan.recommendedParallelRequests,
                )
            }
            val queueDepth = minOf(
                HttpRouteClientManager.DEVICE_DIRECT_PREFETCH_QUEUE_DEPTH,
                memoryBound,
                runtimePlan.recommendedParallelRequests,
            ).coerceAtLeast(1)
            return DeviceTransportPipelineConfig(
                chunkSize = safeChunkSize,
                readParallelism = boundedParallelism(sourceStatus?.recommendedParallelRequests),
                writeParallelism = sameTargetFileWriteParallelism(
                    boundedParallelism(targetStatus?.recommendedParallelRequests)
                ),
                queueDepth = queueDepth,
            )
        }

        private suspend fun ensureTransportCopyActive(
            task: Task,
            srcDevice: Device?,
            destDevice: Device?,
        ) {
            if (!taskState.awaitIfPaused(task.key)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
            if (srcDevice != null && srcDevice.id != this@Device.id && deviceState.devices.none { it.id == srcDevice.id }) {
                throw DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
            }
            if (destDevice != null && destDevice.id != this@Device.id && deviceState.devices.none { it.id == destDevice.id }) {
                throw DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
            }
        }

        private suspend fun createRemoteDirectory(path: String, client: DeviceFileClient): Result<Boolean> {
            return client.createFolders(listOf(path)).fold(
                onSuccess = { results ->
                    if (results.isNotEmpty()) results.first() else Result.failure(Exception(AppStrings.ui_folder_creation_failed))
                },
                onFailure = { error -> Result.failure(error) }
            )
        }

        private suspend fun createRemoteFile(path: String, client: DeviceFileClient): Result<Boolean> {
            return client.createFiles(listOf(path)).fold(
                onSuccess = { results ->
                    if (results.isNotEmpty()) results.first() else Result.failure(Exception(AppStrings.ui_creation_failed))
                },
                onFailure = { error -> Result.failure(error) }
            )
        }

        private suspend fun transferSingleFileViaClients(
            task: Task,
            sourcePath: String,
            targetPath: String,
            file: FileSimpleInfo,
            sourceDevice: Device?,
            targetDevice: Device?,
            reportTaskChunkProgress: Boolean = true,
        ): Result<Boolean> {
            suspend fun ensureRunning() = ensureTransportCopyActive(task, sourceDevice, targetDevice)
            val progressLabel = when {
                sourceDevice == null && targetDevice != null -> AppStrings.ui_upload
                sourceDevice != null && targetDevice == null -> AppStrings.ui_download
                else -> AppStrings.ui_transfer
            }
            val progressText = when {
                sourceDevice == null && targetDevice != null -> AppStrings.ui_upload_progress
                sourceDevice != null && targetDevice == null -> AppStrings.ui_download_progress
                else -> AppStrings.ui_transfer_progress
            }
            val startText = AppStrings.task_start_operation.format(operation = progressLabel)
            // Directory copies keep task counters on item granularity; only single-file
            // transfers should expose chunk counts as the task-level progress range.
            fun updateTaskChunkProgress(
                progressCur: String? = null,
                progressMax: String? = null,
            ) {
                if (!reportTaskChunkProgress) return
                progressCur?.let { taskState.putValue(task, "progressCur", it) }
                progressMax?.let { taskState.putValue(task, "progressMax", it) }
            }

            if (
                sourceDevice != null &&
                targetDevice != null &&
                sourceDevice.id == targetDevice.id
            ) {
                val copyRequestId = "device-copy:${task.key}:${file.path.ifBlank { file.name }}"
                val activeFileClient = sourceDevice.resolveTransferFileClient()
                return activeFileClient.copyPath(
                    srcPath = sourcePath,
                    destPath = targetPath,
                    requestId = copyRequestId,
                    onProgress = { progress ->
                        ensureRunning()
                        val resolvedPath = progress.path.ifBlank { targetPath }
                        taskState.putValue(task, "path", resolvedPath)
                        updateTaskChunkProgress(
                            progressCur = progress.progressCur.toString(),
                            progressMax = progress.progressMax.toString(),
                        )
                        if (progress.message.isNotBlank()) {
                            taskState.putResult(task, targetPath, progress.message)
                        }
                    }
                )
            }

            if (file.size == 0L) {
                return when {
                    targetDevice != null -> createRemoteFile(targetPath, targetDevice.resolveTransferFileClient())
                    else -> FileUtils.createFile(FileAccessPermission.Allowed, targetPath)
                }
            }

            val sourceClient = sourceDevice?.resolveTransferFileClient()
            val targetClient = targetDevice?.resolveTransferFileClient()
            val useSessionStreams = (sourceClient != null || targetClient != null) &&
                listOfNotNull(sourceClient, targetClient).any { client -> client !is FileRouteClient }
            if (useSessionStreams) {
                val startMs = Clock.System.now().toEpochMilliseconds()
                val rateSampler = TaskProgressRateSampler(initialAt = startMs)
                var lastProgressReportedAt = startMs
                taskState.putResult(task, targetPath, startText)
                updateTaskChunkProgress(progressMax = "1")
                var transferred = 0L
                suspend fun noteProgress(bytes: Int, force: Boolean = false) {
                    transferred += bytes
                    val now = Clock.System.now().toEpochMilliseconds()
                    val completed = file.size <= 0L || transferred >= file.size
                    if (!force && !completed && now - lastProgressReportedAt < 500L) return
                    val rateSample = rateSampler.update(
                        completed = transferred,
                        total = file.size,
                        now = now,
                        force = completed,
                    )
                    taskState.putValue(task, "path", targetPath)
                    updateTaskChunkProgress(progressCur = if (transferred >= file.size) "1" else "0")
                    taskState.putResult(
                        task,
                        targetPath,
                        AppStrings.message_task_progress_with_metrics.format(
                            progress = progressText,
                            percent = transferred.formatPercent(file.size),
                            current = transferred.toString(),
                            total = file.size.toString(),
                            speed = rateSample.speedPerSecond.formatSpeed(),
                            remaining = rateSample.etaMs.formatDuration(),
                        )
                    )
                    lastProgressReportedAt = now
                }
                return when {
                    sourceDevice != null && targetDevice != null -> {
                        val chunks = kotlinx.coroutines.flow.channelFlow {
                            sourceClient!!.readStream(sourcePath, 0L, file.size) { chunk ->
                                ensureRunning()
                                send(chunk)
                                noteProgress(chunk.size)
                            }.getOrThrow()
                        }
                        targetClient!!.writeStream(targetPath, file.size, 0L, chunks)
                    }
                    sourceDevice != null -> {
                        sourceClient!!.readStream(sourcePath, 0L, file.size) { chunk ->
                            ensureRunning()
                            FileUtils.writeBytes(
                                FileAccessPermission.Allowed,
                                path = targetPath,
                                fileSize = file.size,
                                data = chunk,
                                offset = transferred,
                            ).getOrThrow()
                            noteProgress(chunk.size)
                        }
                    }
                    else -> {
                        val chunks = kotlinx.coroutines.flow.flow {
                            FileUtils.readFileRangeChunks(
                                FileAccessPermission.Allowed,
                                path = sourcePath,
                                start = 0L,
                                end = file.size,
                                chunkSize = DEVICE_SESSION_MAX_PAYLOAD_BYTES.toLong(),
                            ).collect { result ->
                                ensureRunning()
                                val chunk = result.getOrThrow().second
                                emit(chunk)
                                noteProgress(chunk.size)
                            }
                        }
                        targetClient!!.writeStream(targetPath, file.size, 0L, chunks).also { result ->
                            if (result.isSuccess) noteProgress(0, force = true)
                        }
                    }
                }
            }

            val pipelineConfig = resolveTransportPipelineConfig(sourceDevice, targetDevice)
            val chunkCount = calculateDeviceTransportChunkCount(file.size, pipelineConfig.chunkSize)
            val startMs = Clock.System.now().toEpochMilliseconds()
            val rateSampler = TaskProgressRateSampler(initialAt = startMs)
            taskState.putResult(task, targetPath, startText)
            updateTaskChunkProgress(progressMax = chunkCount.toString())
            return runDeviceTransportPipeline(
                totalBytes = file.size,
                config = pipelineConfig,
                readChunk = { _, startOffset, endOffset ->
                    ensureRunning()
                    when {
                        sourceDevice != null -> {
                            val sourceClient = sourceDevice.resolveTransferFileClient()
                            delayForHttpTransferBackoff(sourceClient.transferStatus())
                            sourceClient.readBytes(
                                sourcePath,
                                startOffset,
                                endOffset,
                            )
                        }
                        else -> FileUtils.readFileRange(FileAccessPermission.Allowed, sourcePath, startOffset, endOffset)
                    }.mapCatching { bytes ->
                        val expectedBytes = (endOffset - startOffset).coerceAtLeast(0L)
                        if (bytes.size.toLong() != expectedBytes) {
                            throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                        }
                        bytes
                    }
                },
                writeChunk = { chunk: DeviceTransportChunk ->
                    ensureRunning()
                    when {
                        targetDevice != null -> {
                            val targetClient = targetDevice.resolveTransferFileClient()
                            delayForHttpTransferBackoff(targetClient.transferStatus())
                            targetClient.writeBytes(
                                fileSize = file.size,
                                blockIndex = chunk.index.toLong(),
                                blockLength = chunk.size.toLong(),
                                path = targetPath,
                                byteArray = chunk.bytes,
                                startOffset = chunk.startOffset,
                            )
                        }
                        else -> FileUtils.writeBytes(FileAccessPermission.Allowed,
                            path = targetPath,
                            fileSize = file.size,
                            data = chunk.bytes,
                            offset = chunk.startOffset,
                        )
                    }
                },
                onChunkCommitted = { progress ->
                    val now = Clock.System.now().toEpochMilliseconds()
                    val rateSample = rateSampler.update(
                        completed = progress.transferredBytes,
                        total = file.size,
                        now = now,
                        force = file.size > 0L && progress.transferredBytes >= file.size,
                    )
                    val doneBlocks = progress.completedChunks.toLong()
                    val totalBlocks = progress.totalChunks.toLong().coerceAtLeast(1L)
                    taskState.putValue(task, "path", targetPath)
                    updateTaskChunkProgress(progressCur = progress.completedChunks.toString())
                    taskState.putResult(
                        task,
                        targetPath,
                        AppStrings.message_task_progress_with_metrics.format(
                            progress = progressText,
                            percent = progress.transferredBytes.formatPercent(file.size),
                            current = doneBlocks.toString(),
                            total = totalBlocks.toString(),
                            speed = rateSample.speedPerSecond.formatSpeed(),
                            remaining = rateSample.etaMs.formatDuration(),
                        )
                    )
                },
            ).map { true }
        }

        private suspend fun copyViaTransportClients(
            task: Task,
            srcFileSimpleInfo: FileSimpleInfo,
            destFileSimpleInfo: FileSimpleInfo,
            sourceDevice: Device?,
            targetDevice: Device?,
        ): Result<Boolean> {
            suspend fun ensureRunning() = ensureTransportCopyActive(task, sourceDevice, targetDevice)

            var successCount = 0
            var failureCount = 0
            var rejectedEntryCount = 0
            var firstFailedPath: String? = null
            var firstError: String? = null
            val progressMutex = Mutex()

            fun recordFailure(targetPath: String, message: String) {
                failureCount++
                if (firstFailedPath == null) firstFailedPath = targetPath
                if (firstError == null) firstError = message.ifBlank { AppStrings.ui_copy_failed }
                taskState.putResult(task, targetPath, message.ifBlank { AppStrings.ui_copy_failed })
            }

            fun sourceCopySeparator(): String {
                return sourceDevice?.pathSeparator?.ifBlank { "/" }
                    ?: PathUtils.getPathSeparator().ifBlank { "/" }
            }

            fun destCopySeparator(): String {
                return targetDevice?.pathSeparator?.ifBlank { "/" }
                    ?: PathUtils.getPathSeparator().ifBlank { "/" }
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
                rejectedEntryCount++
                val targetPath = runCatching { resolveCopyTarget(entry.path) }
                    .getOrDefault(destFileSimpleInfo.path)
                recordFailure(
                    targetPath = targetPath,
                    message = error.message.orEmpty(),
                )
            }

            suspend fun recordEntryResult(
                success: Boolean,
                targetPath: String,
                message: String = "",
            ) {
                progressMutex.withLock {
                    if (success) {
                        successCount++
                        if (message.isNotEmpty()) {
                            taskState.putResult(task, targetPath, message)
                        } else {
                            taskState.removeResult(task, targetPath)
                        }
                    } else {
                        recordFailure(targetPath, message)
                    }
                    taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
                }
            }

            suspend fun createRootDirectoryIfNeeded(): Result<Boolean> {
                if (!destFileSimpleInfo.isDirectory) return Result.success(true)
                return when {
                    targetDevice != null -> createRemoteDirectory(destFileSimpleInfo.path, targetDevice.resolveTransferFileClient())
                    else -> FileUtils.createFolder(FileAccessPermission.Allowed, destFileSimpleInfo.path)
                }
            }

            if (!srcFileSimpleInfo.isDirectory) {
                ensureRunning()
                return transferSingleFileViaClients(
                    task = task,
                    sourcePath = srcFileSimpleInfo.path,
                    targetPath = destFileSimpleInfo.path,
                    file = srcFileSimpleInfo,
                    sourceDevice = sourceDevice,
                    targetDevice = targetDevice,
                )
            }

            if (srcFileSimpleInfo.size == 0L) {
                ensureRunning()
                taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
                return createRootDirectoryIfNeeded().also { result ->
                    if (result.isSuccess && result.getOrDefault(false)) {
                        taskState.removeResult(task, "")
                    }
                }
            }

            taskState.putCreatingFolderProgress(task, destFileSimpleInfo.path)
            val rootCreate = createRootDirectoryIfNeeded()
            if (rootCreate.isFailure || !rootCreate.getOrDefault(false)) {
                return rootCreate
            }

            suspend fun collectSourceTraversalEntries(): List<FileSimpleInfo> {
                var discoveredEntries = 0
                val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
                fun updateDiscovered(entries: List<FileSimpleInfo>) {
                    discoveredEntries += entries.size
                    taskState.putCopyScanProgress(task, discoveredEntries)
                }

                return if (sourceDevice == null) {
                    collectDirectoryEntriesAdaptive(
                        root = srcFileSimpleInfo,
                        config = resolveTraversalParallelism(TraversalEndpointKind.Local),
                        pathSeparator = PathUtils.getPathSeparator(),
                        ensureRunning = { ensureRunning() },
                        onScanProgress = scanProgressPublisher,
                        onEntriesDiscovered = { entries -> updateDiscovered(entries) },
                        dynamicMaxParallelismProvider = {
                            resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Local)
                        },
                        rejectSymbolicLinkEntries = true,
                        onRejectedEntry = { entry, error -> recordRejectedEntry(entry, error) },
                    ) { directory ->
                        PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).map { entries ->
                            entries.map { entry -> entry.withCopy(protocol = FileProtocol.Local, protocolId = "") }
                        }
                    }
                } else {
                    val sourcePathClient = sourceDevice.resolveTransferPathClient()
                    fun currentTransferStatus() = sourcePathClient
                        .transferStatus()
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
                        pathSeparator = sourceDevice.pathSeparator.ifBlank { PathUtils.getPathSeparator() },
                        ensureRunning = { ensureRunning() },
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
                        onRejectedEntry = { entry, error -> recordRejectedEntry(entry, error) },
                    ) { directory ->
                        sourcePathClient
                            .listPath(
                                request = ListRequest(directory.path),
                                batchId = "device-copy-scan:${task.key}",
                            )
                            .map { entries ->
                                rejectUnsafeListEntries(entries)
                                entries.map { entry ->
                                    entry.withCopy(
                                        protocol = FileProtocol.Device,
                                        protocolId = srcFileSimpleInfo.protocolId,
                                    )
                                }
                            }
                    }
                }
            }

            taskState.putCopyScanProgress(task)
            val traversalEntries = try {
                collectSourceTraversalEntries().also { entries ->
                    entries.forEach { entry ->
                        resolveCopyTarget(entry.path)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                return Result.failure(error)
            }
            val plannedEntries = traversalEntries.size + rejectedEntryCount
            val resolvedProgressMax = plannedEntries.coerceAtLeast(1)
            taskState.putValue(task, "progressMax", resolvedProgressMax.toString())
            taskState.putValue(task, "progressCur", "0")

            suspend fun processDirectoryEntry(entry: FileSimpleInfo) {
                ensureRunning()
                val targetPath = resolveCopyTarget(entry.path)
                taskState.putCreatingFolderProgress(task, targetPath)
                val result = when {
                    targetDevice != null -> createRemoteDirectory(targetPath, targetDevice.resolveTransferFileClient())
                    else -> FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
                }
                val success = result.isSuccess && result.getOrDefault(false)
                if (success) {
                    recordEntryResult(true, targetPath)
                } else {
                    recordEntryResult(
                        false,
                        targetPath,
                        taskState.resolveTaskFailureMessage(
                            task = task,
                            preferredPath = targetPath,
                            error = result.exceptionOrNull(),
                            fallback = AppStrings.ui_folder_creation_failed,
                        )
                    )
                }
            }

            suspend fun copyFileEntry(entry: FileSimpleInfo) {
                ensureRunning()
                val targetPath = resolveCopyTarget(entry.path)
                val sourcePath = entry.path
                taskState.removeResult(task, "")
                val result = transferSingleFileViaClients(
                    task = task,
                    sourcePath = sourcePath,
                    targetPath = targetPath,
                    file = entry,
                    sourceDevice = sourceDevice,
                    targetDevice = targetDevice,
                    reportTaskChunkProgress = false,
                )
                val success = result.isSuccess && result.getOrDefault(false)
                if (success) {
                    recordEntryResult(true, targetPath)
                } else {
                    recordEntryResult(
                        false,
                        targetPath,
                        taskState.resolveTaskFailureMessage(
                            task = task,
                            preferredPath = targetPath,
                            error = result.exceptionOrNull(),
                            fallback = AppStrings.ui_copy_failed,
                        )
                    )
                }
            }

            fun currentOperationTransferStatuses() = listOfNotNull(
                sourceDevice
                    ?.peekTransferFileClient()
                    ?.transferStatus()
                    ?.clamped()
                    ?.takeIf { status -> status.sampledAtMillis > 0L },
                targetDevice
                    ?.peekTransferFileClient()
                    ?.transferStatus()
                    ?.clamped()
                    ?.takeIf { status -> status.sampledAtMillis > 0L },
            )

            fun currentOperationRemoteRecommended() =
                currentOperationTransferStatuses().minOfOrNull { status -> status.recommendedParallelRequests }

            fun currentOperationRemoteBusy() = currentOperationTransferStatuses().any { status ->
                status.busy || (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
            }

            suspend fun enqueueTraversalBatch(
                entries: List<FileSimpleInfo>,
            ) {
                if (entries.isEmpty()) return
                val normalizedEntries = entries
                    .sortedWith(compareBy<FileSimpleInfo> { !it.isDirectory }.thenBy { it.path.length })
                normalizedEntries.filter { it.isDirectory }.forEach { entry ->
                    processDirectoryEntry(entry)
                }
                val files = normalizedEntries.filterNot { it.isDirectory }
                val sourceArchiveClient = sourceDevice?.resolveTransferFileClient()
                val targetArchiveClient = targetDevice?.resolveTransferFileClient()
                val archiveCoordinator = SmallFileArchiveTransferCoordinator(
                    sourceClient = sourceArchiveClient,
                    targetClient = targetArchiveClient,
                    transportPreference = SmallFileArchiveTransportPreference.LAN,
                )
                val archiveSelection = archiveCoordinator.select(
                    items = files,
                    sourcePath = { entry -> entry.path },
                    relativePath = { entry ->
                        val srcSeparator = sourceCopySeparator()
                        val srcRoot = if (srcFileSimpleInfo.path.endsWith(srcSeparator)) {
                            srcFileSimpleInfo.path
                        } else {
                            srcFileSimpleInfo.path + srcSeparator
                        }
                        entry.path.removePrefix(srcRoot).trimStart('/', '\\')
                    },
                    size = FileSimpleInfo::size,
                    hidden = FileSimpleInfo::isHidden,
                    mimeType = FileSimpleInfo::mineType,
                    modifiedTimeMillis = FileSimpleInfo::updatedDate,
                )
                val fallbackFiles = mutableListOf<FileSimpleInfo>()
                if (archiveSelection != null) {
                    archiveSelection.batches.forEach { batch ->
                        ensureRunning()
                        val completedBytes = mutableMapOf<String, Long>()
                        val targetByRelative = batch.requestEntries.mapIndexed { index, request ->
                            request.relativePath to resolveCopyTarget(batch.items[index].path)
                        }.toMap()
                        val result = archiveCoordinator.transferBatch(
                            entries = batch.requestEntries,
                            destinationRootPath = destFileSimpleInfo.path,
                            onFileBytes = { relativePath, bytes ->
                                ensureRunning()
                                val targetPath = targetByRelative[relativePath] ?: destFileSimpleInfo.path
                                val total = batch.requestEntries
                                    .firstOrNull { request -> request.relativePath == relativePath }
                                    ?.size
                                    ?: 0L
                                val done = (completedBytes.getOrElse(relativePath) { 0L } + bytes)
                                    .coerceAtMost(total)
                                completedBytes[relativePath] = done
                                taskState.startRuntimeByteItem(task, targetPath, total)
                                taskState.putRuntimeByteProgress(task, targetPath, done)
                            },
                        )
                        val completed = result.getOrNull()?.completedRelativePaths.orEmpty() +
                            result.exceptionOrNull()?.completedArchiveTransferResult()?.completedRelativePaths.orEmpty()
                        batch.items.forEachIndexed { index, entry ->
                            val relativePath = batch.requestEntries[index].relativePath
                            val targetPath = targetByRelative[relativePath] ?: resolveCopyTarget(entry.path)
                            if (relativePath in completed) {
                                taskState.finishRuntimeByteItem(task, targetPath, entry.size)
                                recordEntryResult(true, targetPath)
                            } else {
                                taskState.clearRuntimeByteItem(task, targetPath)
                                fallbackFiles += entry
                            }
                        }
                    }
                    archiveSelection.remainingItems.forEach { entry ->
                        resolveCopyTarget(entry.path)
                        fallbackFiles += entry
                    }
                } else {
                    fallbackFiles += files
                }
                processItemsAdaptive(
                    items = fallbackFiles,
                    config = resolveOperationParallelism(
                        endpointKind = TraversalEndpointKind.Device,
                        remoteRecommendedParallelism = currentOperationRemoteRecommended(),
                        remoteBusy = currentOperationRemoteBusy(),
                    ),
                    ensureRunning = { ensureRunning() },
                    dynamicMaxParallelismProvider = {
                        resolveOperationRuntimeMaxParallelism(
                            endpointKind = TraversalEndpointKind.Device,
                            remoteRecommendedParallelism = currentOperationRemoteRecommended(),
                            remoteBusy = currentOperationRemoteBusy(),
                        )
                    },
                ) { entry ->
                    copyFileEntry(entry)
                }
            }

            if (plannedEntries == 0) {
                taskState.removeResult(task, "")
                taskState.putValue(task, "progressCur", resolvedProgressMax.toString())
                return Result.success(true)
            }

            enqueueTraversalBatch(traversalEntries)
            taskState.removeResult(task, "")

            if (failureCount > 0) {
                val summary = buildBatchTaskFailureMessage(
                    operation = AppStrings.ui_copy,
                    failureCount = failureCount,
                    firstFailedPath = firstFailedPath,
                    firstError = firstError,
                )
                taskState.putResult(task, destFileSimpleInfo.path, summary)
                return Result.failure(Exception(summary))
            }

            return Result.success(successCount == plannedEntries)
        }

        suspend fun rename(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>> {
            return try {
                getFileClient().renames(renameInfos)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_renaming_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>> {
            return try {
                getFileClient().createFolders(paths)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_creation_folder_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> {
            return try {
                getFileClient().createFiles(paths)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_creation_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun delete(paths: List<String>): Result<List<Result<Boolean>>> {
            return try {
                getFileClient().deletes(paths)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_delete_file_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun copyTo(
            task: Task,
            srcFileSimpleInfo: FileSimpleInfo,
            destFileSimpleInfo: FileSimpleInfo
        ): Result<Boolean> {
            return try {
                val sourceDevice = when (srcFileSimpleInfo.protocol) {
                    FileProtocol.Device -> resolveTransferDevice(srcFileSimpleInfo.protocolId)
                    else -> null
                }
                val targetDevice = when (destFileSimpleInfo.protocol) {
                    FileProtocol.Device -> resolveTransferDevice(destFileSimpleInfo.protocolId)
                    else -> null
                }
                if (
                    srcFileSimpleInfo.protocol == FileProtocol.Device &&
                    destFileSimpleInfo.protocol == FileProtocol.Device &&
                    srcFileSimpleInfo.protocolId.isNotBlank() &&
                    srcFileSimpleInfo.protocolId == destFileSimpleInfo.protocolId
                ) {
                    val copyRequestId = "device-copy:${task.key}"
                    val activeFileClient = getFileClient()
                    val cancelMonitor = launchDeviceControlMonitor {
                        var lastPaused: Boolean? = null
                        while (isActive) {
                            if (taskState.isTaskCancelled(task.key)) {
                                runCatching {
                                    activeFileClient.controlCopy(copyRequestId, CopyPathControlAction.Cancel)
                                }
                                break
                            }
                            val paused = taskState.isTaskPaused(task.key)
                            if (lastPaused != paused) {
                                if (paused) {
                                    taskState.putResult(task, destFileSimpleInfo.path, TASK_REMOTE_PAUSE_REQUEST_MESSAGE)
                                    val result = runCatching {
                                        activeFileClient.controlCopy(copyRequestId, CopyPathControlAction.Pause)
                                    }.getOrNull()
                                    if (result?.isSuccess == true && result.getOrDefault(false)) {
                                        taskState.putResult(task, destFileSimpleInfo.path, TASK_REMOTE_PAUSE_WAITING_MESSAGE)
                                    } else {
                                        taskState.putResult(
                                            task,
                                            destFileSimpleInfo.path,
                                            result?.exceptionOrNull()?.message
                                                ?: TASK_REMOTE_PAUSE_FAILURE_MESSAGE
                                        )
                                    }
                                } else if (lastPaused != null) {
                                    taskState.putResult(task, destFileSimpleInfo.path, TASK_REMOTE_RESUME_REQUEST_MESSAGE)
                                    val result = runCatching {
                                        activeFileClient.controlCopy(copyRequestId, CopyPathControlAction.Resume)
                                    }.getOrNull()
                                    if (result?.isSuccess == true && result.getOrDefault(false)) {
                                        taskState.putResult(task, destFileSimpleInfo.path, TASK_REMOTE_RESUME_WAITING_MESSAGE)
                                    } else {
                                        taskState.putResult(
                                            task,
                                            destFileSimpleInfo.path,
                                            result?.exceptionOrNull()?.message
                                                ?: TASK_REMOTE_RESUME_FAILURE_MESSAGE
                                        )
                                    }
                                }
                                lastPaused = paused
                            }
                            delay(100.milliseconds)
                        }
                    }
                    taskState.putValue(task, "progressCur", "0")
                    taskState.putValue(task, "progressMax", "0")
                    taskState.putResult(task, destFileSimpleInfo.path, AppStrings.ui_copying_within_device)
                    val copyResult = try {
                        activeFileClient.copyPath(
                            srcPath = srcFileSimpleInfo.path,
                            destPath = destFileSimpleInfo.path,
                            requestId = copyRequestId,
                            onProgress = { progress: CopyPathProgress ->
                                taskState.putValue(task, "path", progress.path.ifBlank { destFileSimpleInfo.path })
                                taskState.putValue(task, "progressCur", progress.progressCur.toString())
                                taskState.putValue(task, "progressMax", progress.progressMax.toString())
                                val message = progress.message.takeIf { it.isNotBlank() }
                                    ?: AppStrings.message_task_progress_units.format(
                                        progress = AppStrings.ui_copy_progress,
                                        current = progress.progressCur.toString(),
                                        total = progress.progressMax.toString(),
                                    )
                                taskState.putResult(task, destFileSimpleInfo.path, message)
                            }
                        )
                    } finally {
                        cancelMonitor.cancel()
                    }
                    if (copyResult.isSuccess && copyResult.getOrDefault(false)) {
                        taskState.removeResult(task, destFileSimpleInfo.path)
                    } else {
                        taskState.putResult(
                            task,
                            destFileSimpleInfo.path,
                            taskState.resolveTaskFailureMessage(
                                task = task,
                                preferredPath = destFileSimpleInfo.path,
                                error = copyResult.exceptionOrNull(),
                                fallback = AppStrings.ui_copy_failed,
                            )
                        )
                    }
                    return copyResult
                }
                if (sourceDevice != null || targetDevice != null) {
                    return copyViaTransportClients(
                        task = task,
                        srcFileSimpleInfo = srcFileSimpleInfo,
                        destFileSimpleInfo = destFileSimpleInfo,
                        sourceDevice = sourceDevice,
                        targetDevice = targetDevice,
                    )
                }
                getConnect().pathRouteClient.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_copy_file_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun get(path: String): Result<FileSimpleInfo> {
            return try {
                getFileClient().getFileByPath(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_query_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun getInfo(path: String): Result<FileInfo> {
            return try {
                getFileClient().getFileInfoByPath(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_query_file_information_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun readLines(path: String): Result<List<String>> {
            return try {
                getFileClient().readFileLines(path)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_reading_file_line_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun readBytes(
            path: String,
            startOffset: Long,
            endOffset: Long,
        ): Result<ByteArray> {
            return try {
                getFileClient().readBytes(path, startOffset, endOffset)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_file_range_reading_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

        suspend fun writeBytes(
            path: String,
            fileSize: Long,
            data: ByteArray,
            offset: Long,
            blockIndex: Long = 0L,
        ): Result<Boolean> {
            return try {
                getFileClient().writeBytes(
                    fileSize = fileSize,
                    blockIndex = blockIndex,
                    blockLength = data.size.toLong(),
                    path = path,
                    byteArray = data,
                    startOffset = offset,
                )
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_write_file_range_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }

    }
}
