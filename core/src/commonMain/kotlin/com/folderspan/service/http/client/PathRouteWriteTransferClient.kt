package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.*
import com.folderspan.service.data.*
import com.folderspan.service.file.DeviceTransportPipelineConfig
import com.folderspan.service.file.runDeviceTransportPipeline
import com.folderspan.service.file.sameTargetFileWriteParallelism
import com.folderspan.service.operation.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import io.ktor.util.date.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import strings.AppStrings
import kotlin.time.Duration.Companion.milliseconds

internal class PathRouteWriteTransferClient(
    private val manager: HttpRouteClientManager,
    private val deviceState: DeviceState,
    private val taskState: TaskState,
    private val ensureCopyActiveBlock: suspend (Task, FileSimpleInfo?, FileSimpleInfo?) -> Unit,
) {
    private suspend fun ensureCopyActive(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo? = null,
        destFileSimpleInfo: FileSimpleInfo? = null,
    ) {
        ensureCopyActiveBlock(task, srcFileSimpleInfo, destFileSimpleInfo)
    }

    /**
     * 并发处理分块传输的通用逻辑
     */
    private suspend fun processConcurrentChunks(
        chunkCount: Int,
        parallelism: Int,
        ensureActive: suspend () -> Unit,
        operationConfig: OperationParallelismConfig? = null,
        dynamicMaxParallelismProvider: (() -> Int)? = null,
        block: suspend (index: Int) -> Boolean
    ): Boolean = coroutineScope {
        if (chunkCount <= 0) return@coroutineScope true
        val resultMutex = Mutex()
        var allOk = true
        val hardWorkerCount = minOf(
            operationConfig?.hardMaxParallelism ?: parallelism.coerceAtLeast(1),
            chunkCount,
        ).coerceAtLeast(1)
        val workerConfig = (operationConfig ?: OperationParallelismConfig(
                initialParallelism = parallelism.coerceAtLeast(1),
                maxParallelism = hardWorkerCount,
                queueCapacity = hardWorkerCount,
                hardMaxParallelism = hardWorkerCount,
            )).copy(
            initialParallelism = minOf(operationConfig?.initialParallelism ?: parallelism, parallelism, hardWorkerCount),
            maxParallelism = minOf(operationConfig?.maxParallelism ?: hardWorkerCount, hardWorkerCount),
            queueCapacity = maxOf(operationConfig?.queueCapacity ?: hardWorkerCount, hardWorkerCount),
            hardMaxParallelism = hardWorkerCount,
        ).normalized()
        class StopChunkProcessing : Exception()

        try {
            processItemsAdaptive(
                items = (0 until chunkCount).toList(),
                config = workerConfig,
                ensureRunning = ensureActive,
                dynamicMaxParallelismProvider = dynamicMaxParallelismProvider?.let { provider ->
                    { provider().coerceIn(1, hardWorkerCount) }
                },
            ) { currentIndex ->
                ensureActive()
                val ok = block(currentIndex)
                if (!ok) {
                    resultMutex.withLock {
                        allOk = false
                    }
                    throw StopChunkProcessing()
                }
            }
        } catch (_: StopChunkProcessing) {
            resultMutex.withLock {
                allOk = false
            }
        }

        ensureActive()
        resultMutex.withLock { allOk }
    }

    private fun transferChunkBytes(vararg clients: FileRouteClient): Int {
        val recommendedChunkBytes = clients
            .mapNotNull { client ->
                client.transferStatus()
                    .takeIf { status -> status.sampledAtMillis > 0L }
                    ?.recommendedChunkBytes
            }
            .minOrNull()
            ?.coerceIn(DEVICE_COPY_MIN_CHUNK_BYTES, DEVICE_COPY_MAX_CHUNK_BYTES)
        return deviceDirectPathCopyChunkBytes(recommendedChunkBytes)
    }

    private fun transferWorkerCount(
        chunkCount: Int,
        chunkBytes: Int,
        vararg clients: FileRouteClient,
    ): Int {
        val statusBound = clients
            .mapNotNull { client ->
                client.transferStatus()
                    .takeIf { status -> status.sampledAtMillis > 0L }
                    ?.recommendedParallelRequests
            }
            .minOrNull()
            ?.coerceIn(1, DEVICE_COPY_MAX_CONCURRENT_REQUESTS)
            ?: DEVICE_COPY_MAX_CONCURRENT_REQUESTS
        val memoryBound = (DEVICE_COPY_MAX_IN_FLIGHT_BYTES / chunkBytes.coerceAtLeast(1).toLong())
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val runtimeBound = HttpTransferRuntimeTuning.plan(
            maxChunkBytes = chunkBytes,
            maxParallelRequests = DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
        ).recommendedParallelRequests.coerceIn(1, DEVICE_COPY_MAX_CONCURRENT_REQUESTS)
        return minOf(statusBound, runtimeBound, memoryBound, chunkCount).coerceAtLeast(1)
    }

    private fun transferHardMax(
        chunkCount: Int,
        chunkBytes: Int,
        maxParallelRequests: Int = DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
    ): Int {
        val memoryBound = (DEVICE_COPY_MAX_IN_FLIGHT_BYTES / chunkBytes.coerceAtLeast(1).toLong())
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return minOf(maxParallelRequests.coerceAtLeast(1), memoryBound, chunkCount).coerceAtLeast(1)
    }

    private fun deviceTransferStatusSamples(vararg clients: FileRouteClient) = clients
        .mapNotNull { client ->
            client.transferStatus()
                .clamped()
                .takeIf { status -> status.sampledAtMillis > 0L }
        }

    private fun deviceTransferRemoteRecommended(vararg clients: FileRouteClient) =
        deviceTransferStatusSamples(*clients).minOfOrNull { status -> status.recommendedParallelRequests }

    private fun deviceTransferRemoteBusy(vararg clients: FileRouteClient): Boolean =
        deviceTransferStatusSamples(*clients).any { status ->
            status.busy ||
                (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
        }

    private fun adaptiveDeviceTransferConfig(
        chunkCount: Int,
        chunkBytes: Int,
        maxParallelRequests: Int = DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
        vararg clients: FileRouteClient,
    ): OperationParallelismConfig {
        val hardMax = transferHardMax(chunkCount, chunkBytes, maxParallelRequests)
        val currentMax = adaptiveDeviceTransferRuntimeMax(chunkCount, chunkBytes, maxParallelRequests, *clients)
        val baseConfig = resolveOperationParallelism(
            endpointKind = TraversalEndpointKind.Device,
            remoteRecommendedParallelism = deviceTransferRemoteRecommended(*clients),
            remoteBusy = deviceTransferRemoteBusy(*clients),
        )
        return baseConfig.copy(
            initialParallelism = minOf(baseConfig.initialParallelism, currentMax, hardMax),
            maxParallelism = hardMax,
            queueCapacity = maxOf(4, hardMax * 4),
            hardMaxParallelism = hardMax,
        ).normalized()
    }

    private fun adaptiveDeviceTransferRuntimeMax(
        chunkCount: Int,
        chunkBytes: Int,
        maxParallelRequests: Int = DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
        vararg clients: FileRouteClient,
    ): Int {
        val hardMax = transferHardMax(chunkCount, chunkBytes, maxParallelRequests)
        return minOf(
            hardMax,
            resolveOperationRuntimeMaxParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = deviceTransferRemoteRecommended(*clients),
                remoteBusy = deviceTransferRemoteBusy(*clients),
            ),
        ).coerceAtLeast(1)
    }

    private fun transferStreamWorkerCount(
        chunkCount: Int,
        vararg clients: FileRouteClient,
    ): Int {
        val recommendedParallelRequests = clients
            .mapNotNull { client ->
                client.transferStatus()
                    .takeIf { status -> status.sampledAtMillis > 0L }
                    ?.recommendedParallelRequests
            }
            .minOrNull()
        return httpStreamWorkerCount(
            chunkCount = chunkCount,
            recommendedParallelRequests = recommendedParallelRequests,
            maxParallelRequests = DEVICE_COPY_STREAM_MAX_CONCURRENT_REQUESTS,
        )
    }

    /**
     * 传输配置数据类
     */
    private data class TransferConfig(
        val totalBytes: Long,
        val totalBlocks: Int,
        val parallelism: Int
    )

    /**
     * 进度追踪辅助类
     */
    private class ProgressTracker(
        private val task: Task,
        private val destPath: String,
        private val totalBytes: Long,
        private val totalBlocks: Long,
        private val taskState: TaskState,
        private val onProgress: (suspend (current: Int, total: Int) -> Unit)?,
        private val infoLogEnabled: Boolean = true,
    ) {
        private val startMs = getTimeMillis()
        private val rateSampler = TaskProgressRateSampler(initialAt = startMs)
        private val progressMutex = Mutex()
        private var lastLogMs = startMs
        private var doneBytes = 0L

        suspend fun updateProgress(bytesProcessed: Long, progressMessage: String) {
            progressMutex.withLock {
                doneBytes = (doneBytes + bytesProcessed).coerceAtMost(totalBytes)
                val doneUnits = if (doneBytes <= 0L) {
                    0L
                } else {
                    ((doneBytes + DEVICE_COPY_PROGRESS_BYTES - 1L) / DEVICE_COPY_PROGRESS_BYTES)
                        .coerceAtMost(totalBlocks)
                }
                val now = getTimeMillis()
                if (now - lastLogMs >= 1000 || doneBytes >= totalBytes) {
                    val rateSample = rateSampler.update(
                        completed = doneBytes,
                        total = totalBytes,
                        now = now,
                        force = totalBytes in 1..doneBytes,
                    )
                    val resultText = AppStrings.message_task_progress_with_metrics.format(
                        progress = progressMessage,
                        percent = doneBytes.formatPercent(totalBytes),
                        current = doneUnits.toString(),
                        total = totalBlocks.toString(),
                        speed = rateSample.speedPerSecond.formatSpeed(),
                        remaining = rateSample.etaMs.formatDuration(),
                    )
                    if (infoLogEnabled) {
                        LogKit.i(resultText)
                    }
                    taskState.putResult(task, destPath, resultText)
                    onProgress?.invoke(doneUnits.toInt(), totalBlocks.toInt())
                    lastLogMs = now
                }
            }
        }
    }

    suspend fun writeBytes(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
        fileSimpleInfo: FileSimpleInfo,
        sharedTransferSemaphore: Semaphore = Semaphore(DEVICE_COPY_MAX_CONCURRENT_REQUESTS),
        allowDuplexDeviceTransfer: Boolean = false,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null
    ): Result<Boolean> {
        try {
        val srcFileSimpleInfoPath = srcFileSimpleInfo.path + fileSimpleInfo.path
        val destFileSimpleInfoPath = destFileSimpleInfo.path + fileSimpleInfo.path
        LogKit.d(AppStrings.ui_task_path_src_arg0_dest_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (destFileSimpleInfoPath)))

        // 当前处理路径用于 UI 展示
        taskState.putValue(task, "path", destFileSimpleInfoPath)

        suspend fun ensureActive() {
            ensureCopyActive(task, srcFileSimpleInfo, destFileSimpleInfo)
        }

        if (
            srcFileSimpleInfo.protocol == FileProtocol.Device &&
            destFileSimpleInfo.protocol == FileProtocol.Device &&
            srcFileSimpleInfo.protocolId.isNotBlank() &&
            srcFileSimpleInfo.protocolId == destFileSimpleInfo.protocolId
        ) {
            ensureActive()
            val copyRequestId = "device-copy:${task.key}"
            val cancelMonitor = launchPathCopyControlMonitor {
                var lastPaused: Boolean? = null
                while (isActive) {
                    if (taskState.isTaskCancelled(task.key)) {
                        runCatching {
                            manager.fileRouteClient.controlCopy(copyRequestId, CopyPathControlAction.Cancel)
                        }
                        manager.cancelRequest(copyRequestId, AppStrings.message_task_cancelled)
                        break
                    }

                    val paused = taskState.isTaskPaused(task.key)
                    if (lastPaused != paused) {
                        if (paused) {
                            taskState.putResult(task, destFileSimpleInfo.path, TASK_REMOTE_PAUSE_REQUEST_MESSAGE)
                            val result = runCatching {
                                manager.fileRouteClient.controlCopy(copyRequestId, CopyPathControlAction.Pause)
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
                                manager.fileRouteClient.controlCopy(copyRequestId, CopyPathControlAction.Resume)
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
                manager.fileRouteClient.copyPath(
                    srcPath = srcFileSimpleInfo.path,
                    destPath = destFileSimpleInfo.path,
                    onProgress = { progress ->
                        ensureActive()
                        val targetPath = progress.path.ifBlank { destFileSimpleInfo.path }
                        taskState.putValue(task, "path", targetPath)
                        taskState.putValue(task, "progressCur", progress.progressCur.toString())
                        taskState.putValue(task, "progressMax", progress.progressMax.toString())
                        if (progress.message.isNotBlank()) {
                            taskState.putResult(task, destFileSimpleInfo.path, progress.message)
                        }
                    },
                    requestId = copyRequestId,
                )
            } finally {
                cancelMonitor.cancel()
                manager.cancelRequest(copyRequestId, AppStrings.task_copy_request_ended)
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

        // 本地 to 远程
        if (srcFileSimpleInfo.protocol == FileProtocol.Local && destFileSimpleInfo.protocol == FileProtocol.Device) {
            val currentSource = FileUtils.getFile(FileAccessPermission.Allowed, srcFileSimpleInfoPath)
            if (currentSource.isFailure) {
                val error = currentSource.exceptionOrNull()
                if (error.isMissingSourceFileFailure()) {
                    LogKit.w(AppStrings.ui_source_file_does_not_exist_skip_upload_src_arg0_dest_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (destFileSimpleInfoPath)))
                    taskState.removeResult(task, destFileSimpleInfoPath)
                    return Result.success(true)
                }
                val msg = error.toTaskFailureMessage(AppStrings.file_read_failed)
                taskState.putResult(task, destFileSimpleInfoPath, msg)
                return Result.failure(error ?: Exception(AppStrings.file_read_failed))
            }
            val currentSourceSize = currentSource.getOrNull()?.size ?: fileSimpleInfo.size
            if (currentSourceSize != fileSimpleInfo.size) {
                LogKit.w(
                    AppStrings.ui_file_size_has_changed_skip_upload_src_arg0.format(arg0 = (srcFileSimpleInfoPath)) +
                        "expected=${fileSimpleInfo.size}, actual=$currentSourceSize"
                )
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            if (fileSimpleInfo.size == 0L) {
                ensureActive()
                return manager.fileRouteClient
                    .createFiles(listOf(destFileSimpleInfoPath))
                    .fold(
                        onSuccess = { item ->
                            if (item.isNotEmpty()) {
                                val first = item.first()
                                if (first.isFailure) {
                                    val msg = first.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_creation_failed)
                                    taskState.putResult(task, destFileSimpleInfoPath, msg)
                                }
                                first
                            } else {
                                Result.success(false)
                            }
                        },
                        onFailure = { error ->
                            taskState.putResult(
                                task,
                                destFileSimpleInfoPath,
                                error.toTaskFailureMessage(AppStrings.ui_creation_failed)
                            )
                            Result.failure(error)
                        }
                    )
            }

            val chunkSize = transferChunkBytes(manager.fileRouteClient)
            if (fileSimpleInfo.size <= chunkSize.toLong()) {
                val totalBytes = fileSimpleInfo.size
                LogKit.i(AppStrings.ui_start_upload_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (totalBytes.formatBytes())))
                taskState.putResult(
                    task,
                    destFileSimpleInfoPath,
                    AppStrings.task_start_operation.format(operation = AppStrings.ui_upload),
                )
                val progressTracker = ProgressTracker(
                    task,
                    destFileSimpleInfoPath,
                    totalBytes,
                    httpSegmentCount(totalBytes, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1).toLong(),
                    taskState,
                    onProgress,
                )
                val readResult = FileUtils.readFileRange(FileAccessPermission.Allowed, srcFileSimpleInfoPath, 0L, totalBytes)
                if (readResult.isFailure) {
                    val error = readResult.exceptionOrNull()
                    if (error.isMissingSourceFileFailure()) {
                        LogKit.w(AppStrings.ui_source_file_disappeared_skip_upload_arg0_destination_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (destFileSimpleInfoPath)))
                        taskState.removeResult(task, destFileSimpleInfoPath)
                        return Result.success(true)
                    }
                    val msg = error.toTaskFailureMessage(AppStrings.file_read_failed)
                    taskState.putResult(task, destFileSimpleInfoPath, msg)
                    return Result.failure(error ?: Exception(AppStrings.file_read_failed))
                }
                val blockData = readResult.getOrNull() ?: byteArrayOf()
                ensureActive()
                delayForHttpTransferBackoff(manager.fileRouteClient.transferStatus())
                val writeResult = manager.fileRouteClient.writeBytes(
                    fileSize = totalBytes,
                    blockIndex = 0L,
                    blockLength = blockData.size.toLong(),
                    path = destFileSimpleInfoPath,
                    byteArray = blockData,
                    startOffset = 0L,
                )
                val writeOk = writeResult.isSuccess && writeResult.getOrDefault(false)
                if (!writeOk) {
                    val msg = writeResult.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                    taskState.putResult(task, destFileSimpleInfoPath, msg)
                    return Result.failure(
                        writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed)
                    )
                }
                ensureActive()
                progressTracker.updateProgress(blockData.size.toLong(), AppStrings.ui_upload_progress)
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            val uploadPlan = manager.fileRouteClient.adaptiveTransferPlan()
            val streamChunkSize = minOf(
                DEVICE_COPY_STREAM_CHUNK_BYTES,
                uploadPlan.streamRangeBytes,
            ).coerceAtLeast(chunkSize)
            val streamChunkCount = httpSegmentCount(fileSimpleInfo.size, streamChunkSize)
            val streamWrittenBytes = LongArray(streamChunkCount)
            LogKit.i(AppStrings.ui_start_long_stream_upload_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (fileSimpleInfo.size.formatBytes())))
            taskState.putResult(
                task,
                destFileSimpleInfoPath,
                AppStrings.task_start_operation.format(operation = AppStrings.ui_upload),
            )
            val streamProgressTracker = ProgressTracker(
                task,
                destFileSimpleInfoPath,
                fileSimpleInfo.size,
                httpSegmentCount(fileSimpleInfo.size, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1).toLong(),
                taskState,
                onProgress,
            )
            val streamOk = try {
                processHttpChunksUntilFailure(
                    chunkCount = streamChunkCount,
                    parallelism = 1,
                    ensureActive = { ensureActive() },
                    operationConfig = adaptiveDeviceTransferConfig(
                        streamChunkCount,
                        streamChunkSize,
                        1,
                        manager.fileRouteClient,
                    ),
                    dynamicMaxParallelismProvider = { 1 },
                ) { segmentIndex ->
                    ensureActive()
                    val startOffset = segmentIndex * streamChunkSize.toLong()
                    val endOffset = minOf(startOffset + streamChunkSize.toLong(), fileSimpleInfo.size)
                    val expectedBytes = endOffset - startOffset
                    val upload = sharedTransferSemaphore.withPermit {
                        ensureActive()
                        delayForHttpTransferBackoff(manager.fileRouteClient.transferStatus())
                        manager.fileRouteClient.streamUploadRangeFromLocal(
                            sourcePath = srcFileSimpleInfoPath,
                            destinationPath = destFileSimpleInfoPath,
                            fileSize = fileSimpleInfo.size,
                            sourceOffset = startOffset,
                            destinationOffset = startOffset,
                            expectedBytes = expectedBytes,
                        ) { offset, bytesWritten ->
                            ensureActive()
                            val segmentProgress = ((offset - startOffset) + bytesWritten)
                                .coerceAtLeast(0L)
                                .coerceAtMost(expectedBytes)
                            val delta = segmentProgress - streamWrittenBytes[segmentIndex]
                            if (delta > 0L) {
                                streamWrittenBytes[segmentIndex] = segmentProgress
                                streamProgressTracker.updateProgress(delta, AppStrings.ui_upload_progress)
                            }
                        }
                    }
                    if (upload.isFailure || !upload.getOrDefault(false)) {
                        val error = upload.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed)
                        if (!shouldFallbackDeviceDirectStreamFailure(error)) {
                            val message = error.toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                            taskState.putResult(task, destFileSimpleInfoPath, message)
                            throw error
                        }
                        LogKit.w(AppStrings.ui_long_flow_upload_failed_preparing_to_rollback_slices_to_complete_arg0.format(arg0 = (error.message).toString()))
                        return@processHttpChunksUntilFailure false
                    }
                    val missingProgress = expectedBytes - streamWrittenBytes[segmentIndex]
                    if (missingProgress > 0L) {
                        streamWrittenBytes[segmentIndex] = expectedBytes
                        streamProgressTracker.updateProgress(missingProgress, AppStrings.ui_upload_progress)
                    }
                    true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                taskState.putResult(task, destFileSimpleInfoPath, error.toTaskFailureMessage(AppStrings.ui_writing_file_failed))
                return Result.failure(error)
            }
            if (streamOk) {
                LogKit.i(AppStrings.ui_long_flow_upload_completed_arg0_result_true.format(arg0 = (fileSimpleInfo.name)))
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }
            LogKit.w(AppStrings.ui_long_flow_upload_not_completed_revert_to_chunk_upload_arg0.format(arg0 = (fileSimpleInfo.name)))

            val chunkCount = httpSegmentCount(fileSimpleInfo.size, chunkSize)
            val workerCount = sameTargetFileWriteParallelism(
                transferWorkerCount(chunkCount, chunkSize, manager.fileRouteClient)
            )
            val progressUnits = httpSegmentCount(fileSimpleInfo.size, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1)
            val config = TransferConfig(fileSimpleInfo.size, chunkCount, workerCount)
            LogKit.i(AppStrings.ui_start_upload_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (config.totalBytes.formatBytes())))
            taskState.putResult(
                task,
                destFileSimpleInfoPath,
                AppStrings.task_start_operation.format(operation = AppStrings.ui_upload),
            )

            val progressTracker = ProgressTracker(
                task, destFileSimpleInfoPath, config.totalBytes, progressUnits.toLong(), taskState, onProgress
            )
            val uploadStateMutex = Mutex()
            var sourceMissingDuringUpload = false
            var nonSkipFailureDuringUpload = false
            var writtenChunkCount = 0

            val allOk = processConcurrentChunks(
                chunkCount = config.totalBlocks,
                parallelism = config.parallelism,
                ensureActive = { ensureActive() },
                operationConfig = adaptiveDeviceTransferConfig(
                    config.totalBlocks,
                    chunkSize,
                    workerCount,
                    manager.fileRouteClient,
                ),
                dynamicMaxParallelismProvider = {
                    adaptiveDeviceTransferRuntimeMax(
                        config.totalBlocks,
                        chunkSize,
                        workerCount,
                        manager.fileRouteClient,
                    )
                },
            ) { blockIndex ->
                ensureActive()
                val start = blockIndex * chunkSize.toLong()
                val end = minOf(start + chunkSize.toLong(), config.totalBytes)
                ensureActive()
                val readResult = FileUtils.readFileRange(FileAccessPermission.Allowed, srcFileSimpleInfoPath, start, end)

                if (readResult.isFailure) {
                    val error = readResult.exceptionOrNull()
                    val msg = error.toTaskFailureMessage(AppStrings.file_read_failed)
                    if (error.isMissingSourceFileFailure()) {
                        uploadStateMutex.withLock {
                            sourceMissingDuringUpload = true
                        }
                    } else {
                        uploadStateMutex.withLock {
                            nonSkipFailureDuringUpload = true
                        }
                        taskState.putResult(task, destFileSimpleInfoPath, msg)
                    }
                    LogKit.w(
                        AppStrings.ui_upload_chunk_reading_failed_path_arg0_idx_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (blockIndex).toString()) +
                            "range=$start..$end, error=$msg"
                    )
                    return@processConcurrentChunks false
                }

                val blockData = readResult.getOrNull() ?: byteArrayOf()
                val blockBytes = blockData.size.toLong()
                ensureActive()
                val shouldSkipWrite = uploadStateMutex.withLock { sourceMissingDuringUpload }
                if (shouldSkipWrite) {
                    LogKit.w(
                        AppStrings.ui_source_file_disappeared_skip_remaining_chunks_path_arg0_index_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (blockIndex).toString()) +
                            "range=$start..$end"
                    )
                    return@processConcurrentChunks false
                }
                val writeResult = sharedTransferSemaphore.withPermit {
                    ensureActive()
                    delayForHttpTransferBackoff(manager.fileRouteClient.transferStatus())
                    manager.fileRouteClient.writeBytes(
                        fileSize = fileSimpleInfo.size,
                        blockIndex = blockIndex.toLong(),
                        blockLength = blockBytes,
                        path = destFileSimpleInfoPath,
                        byteArray = blockData,
                        startOffset = start,
                    )
                }

                val writeOk = writeResult.isSuccess && writeResult.getOrDefault(false)
                if (!writeOk) {
                    val msg = writeResult.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                    uploadStateMutex.withLock {
                        nonSkipFailureDuringUpload = true
                    }
                    LogKit.w(
                        AppStrings.ui_upload_chunk_write_failed_path_arg0_idx_arg1.format(arg0 = (destFileSimpleInfoPath), arg1 = (blockIndex).toString()) +
                            "range=$start..$end, error=$msg"
                    )
                    taskState.putResult(task, destFileSimpleInfoPath, msg)
                    return@processConcurrentChunks false
                }
                uploadStateMutex.withLock {
                    writtenChunkCount++
                }

                ensureActive()
                progressTracker.updateProgress(blockBytes, AppStrings.ui_upload_progress)
                true
            }

            LogKit.i(AppStrings.ui_upload_completed_arg0_result_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (allOk).toString()))
            val skippedMissingSource = uploadStateMutex.withLock {
                sourceMissingDuringUpload && writtenChunkCount == 0 && !nonSkipFailureDuringUpload
            }
            if (skippedMissingSource) {
                LogKit.w(AppStrings.ui_source_file_does_not_exist_skip_upload_src_arg0_dest_arg1.format(arg0 = (srcFileSimpleInfoPath), arg1 = (destFileSimpleInfoPath)))
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }
            val missingAfterPartialWrite = uploadStateMutex.withLock {
                sourceMissingDuringUpload && writtenChunkCount > 0
            }
            if (missingAfterPartialWrite) {
                val msg = AppStrings.ui_the_file_is_deleted_during_upload
                taskState.putResult(task, destFileSimpleInfoPath, msg)
                return Result.failure(Exception(msg))
            }
            if (allOk) {
                taskState.removeResult(task, destFileSimpleInfoPath)
            }
            return Result.success(allOk)
        }

        // 远程 to 本地
        if (srcFileSimpleInfo.protocol == FileProtocol.Device && destFileSimpleInfo.protocol == FileProtocol.Local) {
            if (fileSimpleInfo.size == 0L) {
                ensureActive()
                return FileUtils.createFile(FileAccessPermission.Allowed, destFileSimpleInfoPath)
                    .fold(
                        onSuccess = { item ->  Result.success(item) },
                        onFailure = { failure ->
                            taskState.putResult(
                                task,
                                destFileSimpleInfoPath,
                                failure.toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                            )
                            Result.failure(failure)
                        }
                    )
            }

            val sourceFileService = manager.fileRouteClient
            val downloadPlan = sourceFileService.adaptiveTransferPlan()
            val fallbackChunkSize = minOf(
                transferChunkBytes(sourceFileService),
                downloadPlan.byteChunkBytes,
            ).coerceAtLeast(DEVICE_COPY_MIN_CHUNK_BYTES)
            val writeDownloadInfoLog = shouldWritePathTransferInfoLog(PathTransferLogKind.Download)
            if (fileSimpleInfo.size <= fallbackChunkSize.toLong()) {
                val totalBytes = fileSimpleInfo.size
                if (writeDownloadInfoLog) {
                    LogKit.i(AppStrings.ui_start_download_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (totalBytes.formatBytes())))
                }
                taskState.putResult(
                    task,
                    destFileSimpleInfoPath,
                    AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
                )
                val progressTracker = ProgressTracker(
                    task,
                    destFileSimpleInfoPath,
                    totalBytes,
                    httpSegmentCount(totalBytes, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1).toLong(),
                    taskState,
                    onProgress,
                    infoLogEnabled = writeDownloadInfoLog,
                )
                delayForHttpTransferBackoff(sourceFileService.transferStatus())
                val download = sourceFileService.downloadRangeToFile(
                    remotePath = srcFileSimpleInfoPath,
                    startOffset = 0L,
                    endOffset = totalBytes,
                    localPath = destFileSimpleInfoPath,
                    fileSize = totalBytes,
                    localOffset = 0L,
                ) { _, bytesWritten ->
                    ensureActive()
                    progressTracker.updateProgress(bytesWritten.toLong(), AppStrings.ui_download_progress)
                }
                if (download.isFailure || !download.getOrDefault(false)) {
                    taskState.putResult(
                        task,
                        destFileSimpleInfoPath,
                        download.exceptionOrNull().toTaskFailureMessage(AppStrings.file_read_failed)
                    )
                    return Result.failure(download.exceptionOrNull() ?: Exception(AppStrings.file_read_failed))
                }

                ensureActive()
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            val streamChunkSize = minOf(
                DEVICE_COPY_STREAM_CHUNK_BYTES,
                downloadPlan.streamRangeBytes,
            ).coerceAtLeast(fallbackChunkSize)
            val streamChunkCount = httpSegmentCount(fileSimpleInfo.size, streamChunkSize)
            val streamWorkerCount = minOf(
                transferStreamWorkerCount(streamChunkCount, sourceFileService),
                downloadPlan.readParallelism,
            ).coerceAtLeast(1)
            val progressUnits = httpSegmentCount(fileSimpleInfo.size, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1)
            val streamConfig = TransferConfig(fileSimpleInfo.size, streamChunkCount, streamWorkerCount)
            if (writeDownloadInfoLog) {
                LogKit.i(AppStrings.ui_start_long_stream_download_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (streamConfig.totalBytes.formatBytes())))
            }
            taskState.putResult(
                task,
                destFileSimpleInfoPath,
                AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
            )

            val progressTracker = ProgressTracker(
                task,
                destFileSimpleInfoPath,
                streamConfig.totalBytes,
                progressUnits.toLong(),
                taskState,
                onProgress,
                infoLogEnabled = writeDownloadInfoLog,
            )
            val streamWrittenBytes = LongArray(streamConfig.totalBlocks)

            val streamOk = try {
                processHttpChunksUntilFailure(
                    chunkCount = streamConfig.totalBlocks,
                    parallelism = streamConfig.parallelism,
                    ensureActive = { ensureActive() },
                    operationConfig = adaptiveDeviceTransferConfig(
                        streamConfig.totalBlocks,
                        streamChunkSize,
                        DEVICE_COPY_STREAM_MAX_CONCURRENT_REQUESTS,
                        sourceFileService,
                    ),
                    dynamicMaxParallelismProvider = {
                        adaptiveDeviceTransferRuntimeMax(
                            streamConfig.totalBlocks,
                            streamChunkSize,
                            DEVICE_COPY_STREAM_MAX_CONCURRENT_REQUESTS,
                            sourceFileService,
                        )
                    },
                ) { segmentIndex ->
                    ensureActive()
                    val startOffset = segmentIndex * streamChunkSize.toLong()
                    val endOffset = minOf(startOffset + streamChunkSize.toLong(), streamConfig.totalBytes)
                    val expectedBytes = endOffset - startOffset
                    val download = sharedTransferSemaphore.withPermit {
                        ensureActive()
                        delayForHttpTransferBackoff(sourceFileService.transferStatus())
                        sourceFileService.streamFileRangeToFile(
                            remotePath = srcFileSimpleInfoPath,
                            startOffset = startOffset,
                            endOffset = endOffset,
                            localPath = destFileSimpleInfoPath,
                            fileSize = fileSimpleInfo.size,
                            localOffset = startOffset,
                        ) { offset, bytesWritten ->
                            ensureActive()
                            val segmentProgress = ((offset - startOffset) + bytesWritten)
                                .coerceAtLeast(0L)
                                .coerceAtMost(expectedBytes)
                            val delta = segmentProgress - streamWrittenBytes[segmentIndex]
                            if (delta > 0L) {
                                streamWrittenBytes[segmentIndex] = segmentProgress
                                progressTracker.updateProgress(delta, AppStrings.ui_download_progress)
                            }
                        }
                    }
                    if (download.isFailure || !download.getOrDefault(false)) {
                        val error = download.exceptionOrNull() ?: Exception(AppStrings.file_read_failed)
                        if (!shouldFallbackDeviceDirectStreamFailure(error)) {
                            val message = error.toTaskFailureMessage(AppStrings.file_read_failed)
                            LogKit.w(AppStrings.ui_long_flow_download_segment_stop_arg0.format(arg0 = (message)))
                            taskState.putResult(task, destFileSimpleInfoPath, message)
                            throw error
                        }
                        LogKit.w(AppStrings.ui_long_flow_download_failed_preparing_to_rollback_and_complete_arg0.format(arg0 = (error.message).toString()))
                        return@processHttpChunksUntilFailure false
                    }
                    val missingProgress = expectedBytes - streamWrittenBytes[segmentIndex]
                    if (missingProgress > 0L) {
                        streamWrittenBytes[segmentIndex] = expectedBytes
                        progressTracker.updateProgress(missingProgress, AppStrings.ui_download_progress)
                    }
                    true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                taskState.putResult(task, destFileSimpleInfoPath, error.toTaskFailureMessage(AppStrings.file_read_failed))
                return Result.failure(error)
            }

            if (streamOk) {
                if (writeDownloadInfoLog) {
                    LogKit.i(AppStrings.ui_long_flow_download_completed_arg0_results_true.format(arg0 = (fileSimpleInfo.name)))
                }
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            val fallbackRanges = remainingDeviceDirectFallbackRanges(
                totalBytes = streamConfig.totalBytes,
                streamChunkBytes = streamChunkSize,
                fallbackChunkBytes = fallbackChunkSize,
                streamWrittenBytes = streamWrittenBytes,
            )

            if (fallbackRanges.isEmpty()) {
                if (writeDownloadInfoLog) {
                    LogKit.i(AppStrings.ui_long_flow_download_completed_arg0_results_true.format(arg0 = (fileSimpleInfo.name)))
                }
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            LogKit.w(AppStrings.ui_long_stream_download_incomplete_falling_back_to_range_recovery_arg0.format(arg0 = (fileSimpleInfo.name), arg1 = (fallbackRanges.size).toString()))
            taskState.putResult(task, destFileSimpleInfoPath, AppStrings.ui_downgrading_long_term_downloads)
            val fallbackWorkerCount = transferWorkerCount(fallbackRanges.size, fallbackChunkSize, sourceFileService)
            val allOk = processConcurrentChunks(
                chunkCount = fallbackRanges.size,
                parallelism = fallbackWorkerCount,
                ensureActive = { ensureActive() },
                operationConfig = adaptiveDeviceTransferConfig(
                    fallbackRanges.size,
                    fallbackChunkSize,
                    DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
                    sourceFileService,
                ),
                dynamicMaxParallelismProvider = {
                    adaptiveDeviceTransferRuntimeMax(
                        fallbackRanges.size,
                        fallbackChunkSize,
                        DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
                        sourceFileService,
                    )
                },
            ) { rangeIndex ->
                ensureActive()
                val (startOffset, endOffset) = fallbackRanges[rangeIndex]
                val download = sharedTransferSemaphore.withPermit {
                    ensureActive()
                    delayForHttpTransferBackoff(sourceFileService.transferStatus())
                    sourceFileService.downloadBytesToFile(
                        remotePath = srcFileSimpleInfoPath,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        localPath = destFileSimpleInfoPath,
                        fileSize = fileSimpleInfo.size,
                        localOffset = startOffset,
                    ) { _, bytesWritten ->
                        ensureActive()
                        progressTracker.updateProgress(bytesWritten.toLong(), AppStrings.ui_download_progress)
                    }
                }
                if (download.isFailure || !download.getOrDefault(false)) {
                    val error = download.exceptionOrNull() ?: Exception(AppStrings.file_read_failed)
                    taskState.putResult(task, destFileSimpleInfoPath, error.toTaskFailureMessage(AppStrings.file_read_failed))
                    return@processConcurrentChunks false
                }
                true
            }
            if (!allOk) {
                taskState.putResult(
                    task,
                    destFileSimpleInfoPath,
                    taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = destFileSimpleInfoPath,
                        error = null,
                        fallback = AppStrings.ui_file_download_failed,
                    )
                )
            }

            if (writeDownloadInfoLog) {
                LogKit.i(AppStrings.ui_download_completed_arg0_result_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (allOk).toString()))
            }
            if (allOk) {
                taskState.removeResult(task, destFileSimpleInfoPath)
            }
            return Result.success(allOk)
        }


        // 远程 to 远程
        if (srcFileSimpleInfo.protocol == FileProtocol.Device && destFileSimpleInfo.protocol == FileProtocol.Device) {
            var destFileService: FileRouteClient = manager.fileRouteClient
            if (srcFileSimpleInfo.protocol == FileProtocol.Device && destFileSimpleInfo.protocol == FileProtocol.Device) {
                ensureActive()
                val socketDevice =
                    deviceState.socketDevices.firstOrNull { device -> device.id == destFileSimpleInfo.protocolId && device.httpClient != null }
                if (socketDevice == null) {
                    val error = DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    taskState.putResult(task, destFileSimpleInfoPath, error.toTaskFailureMessage(AppStrings.ui_copy_failed))
                    return Result.failure(error)
                }
                destFileService = socketDevice.httpClient!!.fileRouteClient
            }
            if (fileSimpleInfo.size == 0L) {
                ensureActive()
                return destFileService.createFiles(listOf(destFileSimpleInfoPath))
                    .fold(
                        onSuccess = { item ->
                            if (item.isNotEmpty()) {
                                val first = item.first()
                                if (first.isFailure) {
                                    val msg = first.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_creation_failed)
                                    taskState.putResult(task, destFileSimpleInfoPath, msg)
                                }
                                first
                            } else {
                                Result.success(false)
                            }
                        },
                        onFailure = { error ->
                            taskState.putResult(
                                task,
                                destFileSimpleInfoPath,
                                error.toTaskFailureMessage(AppStrings.ui_creation_failed)
                            )
                            Result.failure(error)
                        }
                    )
            }

            val sourceFileService = manager.fileRouteClient
            val sourcePlan = sourceFileService.adaptiveTransferPlan()
            val destPlan = destFileService.adaptiveTransferPlan()
            val chunkSize = minOf(
                transferChunkBytes(sourceFileService, destFileService),
                sourcePlan.byteChunkBytes,
                destPlan.byteChunkBytes,
            ).coerceAtLeast(DEVICE_COPY_MIN_CHUNK_BYTES)
            if (fileSimpleInfo.size <= chunkSize.toLong()) {
                val totalBytes = fileSimpleInfo.size
                LogKit.i(AppStrings.ui_start_transmission_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (totalBytes.formatBytes())))
                taskState.putResult(task, destFileSimpleInfoPath, AppStrings.ui_start_transfer)
                val progressTracker = ProgressTracker(
                    task,
                    destFileSimpleInfoPath,
                    totalBytes,
                    httpSegmentCount(totalBytes, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1).toLong(),
                    taskState,
                    onProgress,
                )
                delayForHttpTransferBackoff(sourceFileService.transferStatus())
                val read = sourceFileService.readBytes(
                    srcFileSimpleInfoPath,
                    0L,
                    totalBytes,
                )
                if (read.isFailure) {
                    taskState.putResult(
                        task,
                        destFileSimpleInfoPath,
                        read.exceptionOrNull().toTaskFailureMessage(AppStrings.file_read_failed)
                    )
                    return Result.failure(read.exceptionOrNull() ?: Exception(AppStrings.file_read_failed))
                }

                val bytes = read.getOrNull() ?: byteArrayOf()
                ensureActive()
                delayForHttpTransferBackoff(destFileService.transferStatus())
                val write = destFileService.writeBytes(
                    fileSize = totalBytes,
                    blockIndex = 0L,
                    blockLength = bytes.size.toLong(),
                    path = destFileSimpleInfoPath,
                    byteArray = bytes,
                    startOffset = 0L,
                )
                val ok = write.isSuccess && write.getOrDefault(false)
                if (!ok) {
                    taskState.putResult(
                        task,
                        destFileSimpleInfoPath,
                        write.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                    )
                    return Result.failure(
                        write.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed)
                    )
                }

                ensureActive()
                progressTracker.updateProgress(bytes.size.toLong(), AppStrings.ui_transfer_progress)
                taskState.removeResult(task, destFileSimpleInfoPath)
                return Result.success(true)
            }

            val chunkCount = httpSegmentCount(fileSimpleInfo.size, chunkSize)
            val workerCount = minOf(
                transferWorkerCount(chunkCount, chunkSize, sourceFileService, destFileService),
                sourcePlan.readParallelism,
                destPlan.writeParallelism,
            ).coerceAtLeast(1)
            val progressUnits = httpSegmentCount(fileSimpleInfo.size, DEVICE_COPY_PROGRESS_BYTES).coerceAtLeast(1)
            val config = TransferConfig(fileSimpleInfo.size, chunkCount, workerCount)
            LogKit.i(AppStrings.ui_start_transmission_arg0_size_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (config.totalBytes.formatBytes())))
            taskState.putResult(task, destFileSimpleInfoPath, AppStrings.ui_start_transfer)

            val progressTracker = ProgressTracker(
                task, destFileSimpleInfoPath, config.totalBytes, progressUnits.toLong(), taskState, onProgress
            )

            val readTransferSemaphore =
                if (allowDuplexDeviceTransfer) Semaphore(config.parallelism) else sharedTransferSemaphore
            val writeParallelism = sameTargetFileWriteParallelism(
                minOf(config.parallelism, destPlan.writeParallelism).coerceAtLeast(1)
            )
            val writeTransferSemaphore =
                if (allowDuplexDeviceTransfer) Semaphore(writeParallelism) else sharedTransferSemaphore
            var committedBytes = 0L
            val pipelineResult = runDeviceTransportPipeline(
                totalBytes = config.totalBytes,
                config = DeviceTransportPipelineConfig(
                    chunkSize = chunkSize,
                    readParallelism = config.parallelism,
                    writeParallelism = writeParallelism,
                    queueDepth = minOf(
                        HttpRouteClientManager.DEVICE_DIRECT_PREFETCH_QUEUE_DEPTH,
                        config.parallelism,
                        sourcePlan.queueDepth,
                        destPlan.queueDepth,
                    ).coerceAtLeast(1),
                ),
                readChunk = { _, startOffset, endOffset ->
                    readTransferSemaphore.withPermit {
                        ensureActive()
                        delayForHttpTransferBackoff(sourceFileService.transferStatus())
                        val read = sourceFileService.readBytes(
                            srcFileSimpleInfoPath,
                            startOffset,
                            endOffset,
                        )
                        if (read.isFailure) {
                            taskState.putResult(
                                task,
                                destFileSimpleInfoPath,
                                read.exceptionOrNull().toTaskFailureMessage(AppStrings.file_read_failed)
                            )
                        }
                        read
                    }
                },
                writeChunk = { chunk ->
                    writeTransferSemaphore.withPermit {
                        ensureActive()
                        delayForHttpTransferBackoff(destFileService.transferStatus())
                        val write = destFileService.writeBytes(
                            fileSize = fileSimpleInfo.size,
                            blockIndex = chunk.index.toLong(),
                            blockLength = chunk.size.toLong(),
                            path = destFileSimpleInfoPath,
                            byteArray = chunk.bytes,
                            startOffset = chunk.startOffset,
                        )
                        if (write.isFailure || !write.getOrDefault(false)) {
                            taskState.putResult(
                                task,
                                destFileSimpleInfoPath,
                                write.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_writing_file_failed)
                            )
                        }
                        write
                    }
                },
                onChunkCommitted = { progress ->
                    ensureActive()
                    val deltaBytes = progress.transferredBytes - committedBytes
                    committedBytes = progress.transferredBytes
                    progressTracker.updateProgress(deltaBytes, AppStrings.ui_transfer_progress)
                },
            )
            pipelineResult.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            val allOk = pipelineResult.isSuccess && pipelineResult.getOrDefault(0L) == config.totalBytes

            LogKit.i(AppStrings.ui_transmission_completed_arg0_result_arg1.format(arg0 = (fileSimpleInfo.name), arg1 = (allOk).toString()))
            if (allOk) {
                taskState.removeResult(task, destFileSimpleInfoPath)
            } else {
                taskState.putResult(
                    task,
                    destFileSimpleInfoPath,
                    pipelineResult.exceptionOrNull().toTaskFailureMessage(AppStrings.task_transfer_failed)
                )
            }
            return Result.success(allOk)
        }

        return Result.success(false)
        } catch (e: CancellationException) {
            return Result.failure(e)
        }
    }

}
