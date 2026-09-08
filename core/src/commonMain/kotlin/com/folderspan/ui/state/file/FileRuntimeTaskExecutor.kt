package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.DeviceBackedShareSession
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.Share
import com.folderspan.extensions.*
import com.folderspan.service.file.*
import com.folderspan.service.http.client.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.http.archive.SmallFileArchiveTransferCoordinator
import com.folderspan.service.http.archive.SmallFileArchiveTransportPreference
import com.folderspan.service.http.archive.completedArchiveTransferResult
import com.folderspan.service.operation.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal fun TaskRuntimeQueueEntry.shouldUseTransferRecoveryForCopyQueue(): Boolean {
    if (kind != TaskRuntimeEntryKind.FILE_COPY || isDirectory) return false
    if (!shouldUseTaskFileRecovery(size)) return false
    if (destPath.isBlank() || srcPath.isBlank()) return false
    if (srcProtocol == FileProtocol.Share && (destProtocol != FileProtocol.Local || srcProtocolId == SYSTEM_SHARE_DESK_ID)) {
        return false
    }
    if (
        srcProtocol == FileProtocol.Device &&
        destProtocol == FileProtocol.Device &&
        srcProtocolId.isNotBlank() &&
        srcProtocolId == destProtocolId
    ) {
        return false
    }
    return srcProtocol in recoverableTransferSourceProtocols &&
        destProtocol in recoverableTransferDestinationProtocols
}

internal fun shouldPlanRuntimeCopyArchive(
    sourceProtocol: FileProtocol,
    destinationProtocol: FileProtocol,
    sourceProtocolId: String = "",
    destinationProtocolId: String = "",
): Boolean {
    val archiveSource = sourceProtocol == FileProtocol.Local ||
        sourceProtocol == FileProtocol.Device ||
        sourceProtocol == FileProtocol.Share
    val archiveDestination = destinationProtocol == FileProtocol.Local ||
        destinationProtocol == FileProtocol.Device
    if (!archiveSource || !archiveDestination) return false
    if (sourceProtocol == FileProtocol.Local && destinationProtocol == FileProtocol.Local) return false
    if (sourceProtocol == FileProtocol.Share) {
        if (destinationProtocol != FileProtocol.Local) return false
        if (sourceProtocolId == SYSTEM_SHARE_DESK_ID) return false
    }
    if (sourceProtocol == FileProtocol.Device && destinationProtocol == FileProtocol.Device) {
        if (sourceProtocolId.isBlank() || destinationProtocolId.isBlank() || sourceProtocolId == destinationProtocolId) {
            return false
        }
    }
    return true
}

internal fun shouldContinueRuntimeQueueAfterFailure(
    taskType: TaskType,
    stage: TaskRuntimeStage,
    failure: Throwable?,
): Boolean = !failure.isTaskLevelTransferFailure() &&
    !(taskType == TaskType.Move && stage == TaskRuntimeStage.COPY)

internal suspend fun copyLocalRangeToContinuousDevice(
    targetClient: ContinuousRangeDeviceFileClient,
    localPath: String,
    remotePath: String,
    fileSize: Long,
    startOffset: Long,
    endOffset: Long,
    segmentSize: Int = DEVICE_DIRECT_MAX_LENGTH,
    ensureRunning: suspend () -> Unit = {},
    onBytesCommitted: suspend (offset: Long, bytesWritten: Int) -> Unit,
): Result<Boolean> {
    if (fileSize < 0L || startOffset < 0L || endOffset < startOffset || endOffset > fileSize) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_invalid_range_for_local_upload))
    }
    if (segmentSize <= 0) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_invalid_segment_size_for_local_upload))
    }
    return try {
        var segmentStart = startOffset
        while (segmentStart < endOffset) {
            ensureRunning()
            val segmentEnd = minOf(segmentStart + segmentSize.toLong(), endOffset)
            val uploaded = targetClient.uploadRangeFromFile(
                localPath = localPath,
                localStartOffset = segmentStart,
                localEndOffset = segmentEnd,
                remotePath = remotePath,
                remoteFileSize = fileSize,
                remoteStartOffset = segmentStart,
            ).getOrElse { error -> throw error }
            if (!uploaded) throw IllegalStateException(AppStrings.ui_write_failed)
            onBytesCommitted(segmentStart, (segmentEnd - segmentStart).toInt())
            segmentStart = segmentEnd
        }
        Result.success(true)
    } catch (cancel: CancellationException) {
        Result.failure(cancel)
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

internal class FileRuntimeTaskExecutor(
    private val taskState: TaskState,
    private val deviceState: DeviceState,
    private val directoryCollector: FileStateDirectoryCollector,
    private val operationIgnoreResolver: FileOperationIgnoreResolver,
    private val currentDesk: () -> DiskBase,
    private val runCopyAcrossEndpoints: suspend (
        Task,
        FileSimpleInfo,
        FileSimpleInfo,
        DiskBase,
        DiskBase,
    ) -> Result<Boolean>,
    private val updateFileAndFolder: suspend () -> Unit,
    private val resolveDevice: (DiskBase?, String) -> Device?,
    private val resolveNetworkAccess: (DiskBase?, String) -> NetworkAccess?,
) {
    fun continueTask(task: Task) {
        val latestTask = taskState.getTask(task.key) ?: task
        if (!taskState.hasPendingRuntimeEntries(latestTask.key)) return
        val meta = taskState.loadRuntimeMeta(latestTask.key)
            ?: buildFallbackRuntimeMeta(latestTask, taskState.countPendingRuntimeEntries(latestTask.key))
        val resumedTask = latestTask.withStatus(StatusEnum.LOADING)
        taskState.addOrUpdate(resumedTask)
        val currentTask = taskState.getTask(latestTask.key) ?: resumedTask
        taskState.registerTaskHandler(currentTask) {
            try {
                val result = executePendingTask(currentTask, meta, resumedFromRecovery = true)
                if (result.isSuccess && result.getOrDefault(false)) {
                    taskState.delete(currentTask)
                    updateFileAndFolder()
                } else if (result.isFailure && result.exceptionOrNull() is CancellationException) {
                    taskState.updateStatus(currentTask, StatusEnum.FAILURE)
                } else {
                    val runState = taskState.loadRuntimeRunState(currentTask.key)
                    val latestMeta = taskState.loadRuntimeMeta(currentTask.key) ?: meta
                    val preferredPath = runState?.currentEntryPath
                        ?.takeIf { item -> item.isNotBlank() }
                        ?: latestMeta.target?.path?.takeIf { item -> item.isNotBlank() }
                        ?: latestMeta.source.path
                    val fallback = when (latestMeta.taskType) {
                        TaskType.Copy -> AppStrings.ui_copy_failed
                        TaskType.Move -> AppStrings.ui_move_failed
                        TaskType.Delete -> AppStrings.ui_delete_failed
                        TaskType.Download -> AppStrings.ui_download_failed
                    }
                    val message = taskState.resolveTaskFailureMessage(
                        task = currentTask,
                        preferredPath = preferredPath,
                        error = result.exceptionOrNull(),
                        fallback = fallback,
                    )
                    taskState.putResult(currentTask, preferredPath, message)
                    taskState.updateStatus(currentTask, StatusEnum.FAILURE)
                }
            } finally {
                taskState.clearSignals(currentTask.key)
            }
        }
    }

    internal suspend fun executeCopyTask(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): Result<Boolean> {
        val meta = prepareCopyRuntime(task, src, dest)
        return executePendingTask(task, meta)
    }

    internal suspend fun executeMoveTask(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): Result<Boolean> {
        val meta = prepareMoveRuntime(task, src, dest)
        return executePendingTask(task, meta)
    }

    internal suspend fun executeDeleteTask(
        task: Task,
        target: FileSimpleInfo,
    ): Result<Boolean> {
        val meta = prepareDeleteRuntime(task, target)
        return executePendingTask(task, meta)
    }

    fun finishSuccessfulTask(task: Task) {
        if (taskState.hasFailedRetryEntries(task)) {
            taskState.updateStatus(task, StatusEnum.SUCCESS)
        } else {
            taskState.delete(task)
        }
    }

    private suspend fun executePendingTask(
        task: Task,
        meta: TaskRuntimeMeta,
        resumedFromRecovery: Boolean = false,
    ): Result<Boolean> {
        var runtimeMeta = taskState.loadRuntimeMeta(task.key) ?: meta
        var runState = taskState.loadRuntimeRunState(task.key) ?: buildInitialRunState(task, runtimeMeta)
        if (resumedFromRecovery || runtimeMeta.resumedFromRecovery) {
            val pendingEntries = taskState.countPendingRuntimeEntries(task.key)
            if (pendingEntries != runtimeMeta.remainingEntries) {
                runtimeMeta = runtimeMeta.copy(
                    resumedFromRecovery = true,
                    totalEntries = runtimeMeta.totalEntries.coerceAtLeast(pendingEntries),
                    remainingEntries = pendingEntries,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
        val totalEntries = runtimeMeta.totalEntries.coerceAtLeast(1)
        var processedEntries = (runtimeMeta.totalEntries - runtimeMeta.remainingEntries)
            .coerceAtLeast(0)
            .coerceAtMost(totalEntries)
        var activeQueue: PendingRuntimeQueue? = null
        var ackedSinceLastStateFlush = TASK_RUNTIME_STATE_FLUSH_INTERVAL
        var lastFlushedStage: TaskRuntimeStage? = null
        var lastFlushedCategory: TaskRuntimeQueueCategory? = null
        var lastFlushedQueueFile = ""
        var lastRuntimeStateFlushedAt = 0L
        var copyFileByteMetricsRestored = false
        val copyFileEntries = taskState.loadPendingRuntimeQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
        ).map { queuedEntry -> queuedEntry.entry }
            .let { fileEntries ->
                TaskRuntimeQueueEntriesByCategory(files = fileEntries)
            }
        val copyByteTotal = copyFileEntries.totalFileBytes
        val shouldRestoreCopyByteMetrics = shouldUseCopyByteRuntimeMetrics(copyFileEntries)
        var lastReportedParallelCount = -1
        var lastParallelCountUpdatedAt = 0L

        fun reportRuntimeParallelCount(activeCount: Int, force: Boolean = false) {
            val normalizedCount = activeCount.coerceAtLeast(0)
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && normalizedCount == lastReportedParallelCount) return
            val shouldReport = force ||
                normalizedCount == 0 ||
                normalizedCount > lastReportedParallelCount ||
                now - lastParallelCountUpdatedAt >= 250L
            if (!shouldReport) return
            lastReportedParallelCount = normalizedCount
            lastParallelCountUpdatedAt = now
            taskState.putRuntimeParallelCount(task, normalizedCount)
        }

        fun flushRuntimeState(force: Boolean = false) {
            val now = Clock.System.now().toEpochMilliseconds()
            val queueChanged = runState.currentStage != lastFlushedStage ||
                runState.currentQueueCategory != lastFlushedCategory ||
                runState.currentQueueFile != lastFlushedQueueFile
            val countThresholdReached = ackedSinceLastStateFlush >= TASK_RUNTIME_STATE_FLUSH_INTERVAL
            val delayThresholdReached = ackedSinceLastStateFlush > 0 &&
                now - lastRuntimeStateFlushedAt >= TASK_RUNTIME_STATE_FLUSH_MAX_DELAY_MILLIS
            if (!force && !queueChanged && !countThresholdReached && !delayThresholdReached) {
                return
            }
            taskState.saveRuntimeMeta(runtimeMeta)
            taskState.saveRuntimeRunState(runState)
            taskState.putOverallProgress(task, processedEntries.coerceAtMost(totalEntries), totalEntries)
            taskState.putRuntimeStatus(
                task = task,
                phase = TaskRuntimePhase.EXECUTING,
                stage = runState.currentStage,
                category = runState.currentQueueCategory,
                queueFile = runState.currentQueueFile.takeIf { item -> item.isNotBlank() },
                remainingEntries = runtimeMeta.remainingEntries,
                resumedFromRecovery = runtimeMeta.resumedFromRecovery,
            )
            runState.currentEntryPath
                .takeIf { item -> item.isNotBlank() }
                ?.let { path -> taskState.putValue(task, "path", path) }
            if (taskState.usesRuntimeItemMetrics(task) ||
                runState.currentStage == TaskRuntimeStage.DELETE_SOURCE ||
                runState.currentStage == TaskRuntimeStage.DELETE
            ) {
                taskState.putRuntimeItemProgress(task, processedEntries.coerceAtMost(totalEntries), totalEntries)
            }
            lastFlushedStage = runState.currentStage
            lastFlushedCategory = runState.currentQueueCategory
            lastFlushedQueueFile = runState.currentQueueFile
            lastRuntimeStateFlushedAt = now
            ackedSinceLastStateFlush = 0
        }

        fun ackQueuedEntry(
            currentStage: TaskRuntimeStage,
            currentCategory: TaskRuntimeQueueCategory,
            queuedEntry: TaskRuntimeQueuedEntry,
            entryPath: String,
            lastError: String = "",
            entryId: String? = null,
        ) {
            taskState.ackRuntimeQueueEntry(
                taskKey = task.key,
                stage = currentStage,
                category = currentCategory,
                fileName = queuedEntry.fileName,
                entryId = entryId,
            )
            taskState.deleteRuntimeTransferCheckpoint(task.key, queuedEntry.entry.entryId)
            val remainingEntries = (runtimeMeta.remainingEntries - 1).coerceAtLeast(0)
            val ackAt = Clock.System.now().toEpochMilliseconds()
            processedEntries++
            runtimeMeta = runtimeMeta.copy(
                currentPhase = TaskRuntimePhase.EXECUTING,
                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                remainingEntries = remainingEntries,
                lastError = lastError,
                updatedAt = ackAt,
            )
            runState = runState.copy(
                currentPhase = TaskRuntimePhase.EXECUTING,
                currentStage = currentStage,
                currentQueueCategory = currentCategory,
                currentQueueFile = queuedEntry.fileName,
                currentEntryPath = entryPath,
                lastAckedAt = ackAt,
            )
            ackedSinceLastStateFlush++
            flushRuntimeState()
        }

        suspend fun executeCopyDirectoryQueueAdaptive(currentQueue: PendingRuntimeQueue): Result<Boolean> {
            val stateMutex = Mutex()
            stateMutex.withLock {
                taskState.beginRuntimeDirectoryItemMetrics(
                    task,
                    completed = processedEntries.coerceAtMost(totalEntries),
                    total = totalEntries,
                    startedAt = runtimeMeta.createdAt,
                )
            }
            while (true) {
                ensureQueuedTaskRunning(task)
                val queuedEntries = taskState.loadPendingRuntimeQueueEntries(
                    taskKey = task.key,
                    stage = currentQueue.stage,
                    category = currentQueue.category,
                    limit = TASK_RUNTIME_ADAPTIVE_COPY_QUEUE_BATCH_SIZE,
                )
                if (queuedEntries.isEmpty()) {
                    return Result.success(true)
                }
                val queueEntries = queuedEntries.map { item -> item.entry }
                val endpointKind = resolveCopyQueueOperationEndpointKind(queueEntries)
                val operationConfig = resolveOperationParallelism(
                    endpointKind = endpointKind,
                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                    remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                )
                val canBatchDeviceDirectoryCreates = queueEntries.isNotEmpty() &&
                    queueEntries.all { entry ->
                        entry.kind == TaskRuntimeEntryKind.DIRECTORY_CREATE &&
                            entry.destProtocol == FileProtocol.Device
                    } &&
                    queueEntries.map { entry -> entry.destProtocolId }.distinct().size == 1
                val result = executeDirectoryCreateQueueAdaptiveBatch(
                    queuedEntries = queuedEntries,
                    operationConfig = operationConfig,
                    ensureRunning = { ensureQueuedTaskRunning(task) },
                    dynamicMaxParallelismProvider = {
                        resolveOperationRuntimeMaxParallelism(
                            endpointKind = endpointKind,
                            remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                            remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                        )
                    },
                    createDirectory = { entry ->
                        val result = executeCopyQueueEntry(task, entry)
                        if (taskState.isTaskCancelled(task.key)) {
                            throw CancellationException(AppStrings.message_task_cancelled)
                        }
                        result
                    },
                    createDirectoryBatch = if (canBatchDeviceDirectoryCreates) {
                        { entries -> executeDeviceDirectoryCreateBatch(task, entries) }
                    } else {
                        null
                    },
                    onStarted = { queuedEntry, entryPath ->
                        stateMutex.withLock {
                            val startAt = Clock.System.now().toEpochMilliseconds()
                            runState = runState.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                currentStage = currentQueue.stage,
                                currentQueueCategory = currentQueue.category,
                                currentQueueFile = queuedEntry.fileName,
                                currentEntryPath = entryPath,
                                lastStartedAt = startAt,
                            )
                            runtimeMeta = runtimeMeta.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                lastError = "",
                                updatedAt = startAt,
                            )
                            flushRuntimeState()
                        }
                    },
                    onActiveParallelismChanged = { activeCount ->
                        stateMutex.withLock {
                            reportRuntimeParallelCount(activeCount)
                        }
                    },
                    onBatchCompleted = {
                        stateMutex.withLock {
                            flushRuntimeState(force = true)
                        }
                    },
                    onSuccess = { queuedEntry, entryPath ->
                        stateMutex.withLock {
                            ackQueuedEntry(
                                currentStage = currentQueue.stage,
                                currentCategory = currentQueue.category,
                                queuedEntry = queuedEntry,
                                entryPath = entryPath,
                                entryId = queuedEntry.entry.entryId,
                            )
                        }
                    },
                    onRetryableFailure = { queuedEntry, entryPath, message ->
                        LogKit.w(AppStrings.ui_queue_entry_failed_logged_continued_path_arg0_error_arg1.format(arg0 = entryPath, arg1 = message))
                        stateMutex.withLock {
                            ackQueuedEntry(
                                currentStage = currentQueue.stage,
                                currentCategory = currentQueue.category,
                                queuedEntry = queuedEntry,
                                entryPath = entryPath,
                                lastError = message,
                                entryId = queuedEntry.entry.entryId,
                            )
                        }
                    },
                    shouldContinueAfterFailure = { failure ->
                        shouldContinueRuntimeQueueAfterFailure(
                            taskType = task.taskType,
                            stage = currentQueue.stage,
                            failure = failure,
                        )
                    },
                )
                if (result.isFailure) {
                    stateMutex.withLock {
                        runtimeMeta = runtimeMeta.copy(
                            currentPhase = TaskRuntimePhase.EXECUTING,
                            resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                            lastError = result.exceptionOrNull()?.message.orEmpty(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                        flushRuntimeState(force = true)
                    }
                    return result
                }
            }
        }

        suspend fun executeCopyEmptyFileQueueAdaptive(currentQueue: PendingRuntimeQueue): Result<Boolean> {
            val stateMutex = Mutex()
            stateMutex.withLock {
                taskState.beginRuntimeItemMetrics(
                    task,
                    completed = processedEntries.coerceAtMost(totalEntries),
                    total = totalEntries,
                    startedAt = runtimeMeta.createdAt,
                )
            }
            while (true) {
                ensureQueuedTaskRunning(task)
                val queuedEntries = taskState.loadPendingRuntimeQueueEntries(
                    taskKey = task.key,
                    stage = currentQueue.stage,
                    category = currentQueue.category,
                    limit = TASK_RUNTIME_ADAPTIVE_COPY_QUEUE_BATCH_SIZE,
                )
                if (queuedEntries.isEmpty()) {
                    return Result.success(true)
                }
                val queueEntries = queuedEntries.map { item -> item.entry }
                val endpointKind = resolveCopyQueueOperationEndpointKind(queueEntries)
                val operationConfig = resolveOperationParallelism(
                    endpointKind = endpointKind,
                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                    remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                )
                val result = executeDirectoryCreateQueueAdaptiveBatch(
                    queuedEntries = queuedEntries,
                    operationConfig = operationConfig,
                    ensureRunning = { ensureQueuedTaskRunning(task) },
                    dynamicMaxParallelismProvider = {
                        resolveOperationRuntimeMaxParallelism(
                            endpointKind = endpointKind,
                            remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                            remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                        )
                    },
                    createDirectory = { entry ->
                        val result = executeCopyQueueEntry(task, entry)
                        if (taskState.isTaskCancelled(task.key)) {
                            throw CancellationException(AppStrings.message_task_cancelled)
                        }
                        result
                    },
                    retryableFailureFallback = AppStrings.ui_failed_create_empty_file,
                    onStarted = { queuedEntry, entryPath ->
                        stateMutex.withLock {
                            val startAt = Clock.System.now().toEpochMilliseconds()
                            runState = runState.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                currentStage = currentQueue.stage,
                                currentQueueCategory = currentQueue.category,
                                currentQueueFile = queuedEntry.fileName,
                                currentEntryPath = entryPath,
                                lastStartedAt = startAt,
                            )
                            runtimeMeta = runtimeMeta.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                lastError = "",
                                updatedAt = startAt,
                            )
                            flushRuntimeState()
                        }
                    },
                    onActiveParallelismChanged = { activeCount ->
                        stateMutex.withLock {
                            reportRuntimeParallelCount(activeCount)
                        }
                    },
                    onSuccess = { queuedEntry, entryPath ->
                        stateMutex.withLock {
                            ackQueuedEntry(
                                currentStage = currentQueue.stage,
                                currentCategory = currentQueue.category,
                                queuedEntry = queuedEntry,
                                entryPath = entryPath,
                                entryId = queuedEntry.entry.entryId,
                            )
                        }
                    },
                    onRetryableFailure = { queuedEntry, entryPath, message ->
                        LogKit.w(AppStrings.ui_queue_entry_failed_logged_continued_path_arg0_error_arg1.format(arg0 = entryPath, arg1 = message))
                        stateMutex.withLock {
                            ackQueuedEntry(
                                currentStage = currentQueue.stage,
                                currentCategory = currentQueue.category,
                                queuedEntry = queuedEntry,
                                entryPath = entryPath,
                                lastError = message,
                                entryId = queuedEntry.entry.entryId,
                            )
                        }
                    },
                    shouldContinueAfterFailure = { failure ->
                        shouldContinueRuntimeQueueAfterFailure(
                            taskType = task.taskType,
                            stage = currentQueue.stage,
                            failure = failure,
                        )
                    },
                )
                if (result.isFailure) {
                    stateMutex.withLock {
                        runtimeMeta = runtimeMeta.copy(
                            currentPhase = TaskRuntimePhase.EXECUTING,
                            resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                            lastError = result.exceptionOrNull()?.message.orEmpty(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                        flushRuntimeState(force = true)
                    }
                    return result
                }
            }
        }

        suspend fun executeCopyFileQueueAdaptive(currentQueue: PendingRuntimeQueue): Result<Boolean> {
            val stateMutex = Mutex()
            stateMutex.withLock {
                if (shouldRestoreCopyByteMetrics && !copyFileByteMetricsRestored) {
                    taskState.restoreRuntimeByteMetrics(task, copyByteTotal, startedAt = runtimeMeta.createdAt)
                    copyFileByteMetricsRestored = true
                }
            }
            while (true) {
                ensureQueuedTaskRunning(task)
                val queuedEntries = taskState.loadPendingRuntimeQueueEntries(
                    taskKey = task.key,
                    stage = currentQueue.stage,
                    category = currentQueue.category,
                    limit = TASK_RUNTIME_ADAPTIVE_COPY_QUEUE_BATCH_SIZE,
                )
                if (queuedEntries.isEmpty()) {
                    return Result.success(true)
                }
                val queueEntries = queuedEntries.map { item -> item.entry }
                val endpointKind = resolveCopyQueueOperationEndpointKind(queueEntries)
                val operationConfig = resolveOperationParallelism(
                    endpointKind = endpointKind,
                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                    remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                )
                try {
                    suspend fun processCopyFileQueueEntries(entriesToProcess: List<TaskRuntimeQueuedEntry>) {
                        processItemsAdaptive(
                            items = entriesToProcess,
                            config = operationConfig,
                            ensureRunning = { ensureQueuedTaskRunning(task) },
                            dynamicMaxParallelismProvider = {
                                resolveOperationRuntimeMaxParallelism(
                                    endpointKind = endpointKind,
                                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                                    remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                                )
                            },
                            onActiveParallelismChanged = { activeCount ->
                                stateMutex.withLock {
                                    reportRuntimeParallelCount(activeCount)
                                }
                            },
                        ) { queuedEntry ->
                            val entry = queuedEntry.entry
                            val entryPath = entry.displayPath()
                            stateMutex.withLock {
                                val startAt = Clock.System.now().toEpochMilliseconds()
                                runState = runState.copy(
                                    currentPhase = TaskRuntimePhase.EXECUTING,
                                    currentStage = currentQueue.stage,
                                    currentQueueCategory = currentQueue.category,
                                    currentQueueFile = queuedEntry.fileName,
                                    currentEntryPath = entryPath,
                                    lastStartedAt = startAt,
                                )
                                runtimeMeta = runtimeMeta.copy(
                                    currentPhase = TaskRuntimePhase.EXECUTING,
                                    resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                    lastError = "",
                                    updatedAt = startAt,
                                )
                                flushRuntimeState()
                            }

                            val result = executeCopyQueueEntry(task, entry)
                            val failure = result.exceptionOrNull()
                            if (failure is CancellationException) {
                                throw failure
                            }
                            if (taskState.isTaskCancelled(task.key)) {
                                throw CancellationException(AppStrings.message_task_cancelled)
                            }
                            if (result.getOrNull() != true) {
                                val message = failure?.message ?: AppStrings.ui_task_execution_failed
                                if (
                                    shouldContinueRuntimeQueueAfterFailure(
                                        taskType = task.taskType,
                                        stage = currentQueue.stage,
                                        failure = failure,
                                    )
                                ) {
                                    LogKit.w(AppStrings.ui_queue_entry_failed_logged_continued_path_arg0_error_arg1.format(arg0 = entryPath, arg1 = message))
                                    stateMutex.withLock {
                                        ackQueuedEntry(
                                            currentStage = currentQueue.stage,
                                            currentCategory = currentQueue.category,
                                            queuedEntry = queuedEntry,
                                            entryPath = entryPath,
                                            lastError = message,
                                            entryId = entry.entryId,
                                        )
                                    }
                                    return@processItemsAdaptive
                                }
                                stateMutex.withLock {
                                    runtimeMeta = runtimeMeta.copy(
                                        currentPhase = TaskRuntimePhase.EXECUTING,
                                        resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                        lastError = message,
                                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                                    )
                                    flushRuntimeState(force = true)
                                }
                                throw failure ?: Exception(message)
                            }

                            stateMutex.withLock {
                                ackQueuedEntry(
                                    currentStage = currentQueue.stage,
                                    currentCategory = currentQueue.category,
                                    queuedEntry = queuedEntry,
                                    entryPath = entryPath,
                                    entryId = entry.entryId,
                                )
                            }
                        }
                    }

                    val archiveSelection = planRuntimeCopyArchiveSelection(
                        task = task,
                        queuedEntries = queuedEntries,
                        queueEntries = queueEntries,
                    )
                    val archiveCompletedEntryIds = if (archiveSelection != null && archiveSelection.batches.isNotEmpty()) {
                        processRuntimeCopyArchiveBatches(
                            task = task,
                            currentQueue = currentQueue,
                            selection = archiveSelection,
                            operationConfig = operationConfig,
                            stateMutex = stateMutex,
                            updateActiveParallelism = { activeCount -> reportRuntimeParallelCount(activeCount) },
                            ackQueuedEntry = ::ackQueuedEntry,
                            ensureRunning = { ensureQueuedTaskRunning(task) },
                            currentRuntimeMaxParallelism = {
                                resolveOperationRuntimeMaxParallelism(
                                    endpointKind = endpointKind,
                                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(queueEntries),
                                    remoteBusy = resolveCopyQueueRemoteBusy(queueEntries),
                                )
                            },
                        )
                    } else {
                        emptySet()
                    }
                    val fallbackEntries = queuedEntries.filterNot { queuedEntry ->
                        queuedEntry.entry.entryId in archiveCompletedEntryIds
                    }
                    if (fallbackEntries.isNotEmpty()) {
                        processCopyFileQueueEntries(fallbackEntries)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    val exception = error as? Exception ?: Exception(error)
                    LogKit.e(AppStrings.ui_file_task_queue_failed_task_arg0.format(arg0 = (task.key).toString()), exception)
                    return Result.failure(exception)
                }
            }
        }

        suspend fun executeDeleteFileQueueAdaptive(currentQueue: PendingRuntimeQueue): Result<Boolean> {
            val stateMutex = Mutex()
            stateMutex.withLock {
                taskState.beginRuntimeItemMetrics(
                    task,
                    completed = processedEntries.coerceAtMost(totalEntries),
                    total = totalEntries,
                    startedAt = runtimeMeta.createdAt,
                )
            }
            while (true) {
                ensureQueuedTaskRunning(task)
                val queuedEntries = taskState.loadPendingRuntimeQueueEntries(
                    taskKey = task.key,
                    stage = currentQueue.stage,
                    category = currentQueue.category,
                    limit = TASK_RUNTIME_ADAPTIVE_DELETE_QUEUE_BATCH_SIZE,
                )
                if (queuedEntries.isEmpty()) {
                    return Result.success(true)
                }
                val queueEntries = queuedEntries.map { item -> item.entry }
                val endpointKind = resolveDeleteQueueOperationEndpointKind(queueEntries)
                val operationConfig = resolveOperationParallelism(
                    endpointKind = endpointKind,
                    remoteRecommendedParallelism = resolveDeleteQueueRemoteRecommendedParallelism(queueEntries),
                    remoteBusy = resolveDeleteQueueRemoteBusy(queueEntries),
                )
                try {
                    processItemsAdaptive(
                        items = queuedEntries,
                        config = operationConfig,
                        ensureRunning = { ensureQueuedTaskRunning(task) },
                        dynamicMaxParallelismProvider = {
                            resolveOperationRuntimeMaxParallelism(
                                endpointKind = endpointKind,
                                remoteRecommendedParallelism = resolveDeleteQueueRemoteRecommendedParallelism(queueEntries),
                                remoteBusy = resolveDeleteQueueRemoteBusy(queueEntries),
                            )
                        },
                        onActiveParallelismChanged = { activeCount ->
                            stateMutex.withLock {
                                reportRuntimeParallelCount(activeCount)
                            }
                        },
                    ) { queuedEntry ->
                        val entry = queuedEntry.entry
                        val entryPath = entry.displayPath()
                        stateMutex.withLock {
                            val startAt = Clock.System.now().toEpochMilliseconds()
                            runState = runState.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                currentStage = currentQueue.stage,
                                currentQueueCategory = currentQueue.category,
                                currentQueueFile = queuedEntry.fileName,
                                currentEntryPath = entryPath,
                                lastStartedAt = startAt,
                            )
                            runtimeMeta = runtimeMeta.copy(
                                currentPhase = TaskRuntimePhase.EXECUTING,
                                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                lastError = "",
                                updatedAt = startAt,
                            )
                            flushRuntimeState()
                        }

                        val result = executeDeleteQueueEntry(task, entry)
                        val failure = result.exceptionOrNull()
                        if (failure is CancellationException) {
                            throw failure
                        }
                        if (taskState.isTaskCancelled(task.key)) {
                            throw CancellationException(AppStrings.message_task_cancelled)
                        }
                        if (result.getOrNull() != true) {
                            val message = failure?.message ?: AppStrings.ui_task_execution_failed
                            if (
                                shouldContinueRuntimeQueueAfterFailure(
                                    taskType = task.taskType,
                                    stage = currentQueue.stage,
                                    failure = failure,
                                )
                            ) {
                                LogKit.w(AppStrings.ui_queue_entry_failed_logged_continued_path_arg0_error_arg1.format(arg0 = entryPath, arg1 = message))
                                stateMutex.withLock {
                                    ackQueuedEntry(
                                        currentStage = currentQueue.stage,
                                        currentCategory = currentQueue.category,
                                        queuedEntry = queuedEntry,
                                        entryPath = entryPath,
                                        lastError = message,
                                        entryId = entry.entryId,
                                    )
                                }
                                return@processItemsAdaptive
                            }
                            stateMutex.withLock {
                                runtimeMeta = runtimeMeta.copy(
                                    currentPhase = TaskRuntimePhase.EXECUTING,
                                    resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                                    lastError = message,
                                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                                )
                                flushRuntimeState(force = true)
                            }
                            throw failure ?: Exception(message)
                        }

                        stateMutex.withLock {
                            ackQueuedEntry(
                                currentStage = currentQueue.stage,
                                currentCategory = currentQueue.category,
                                queuedEntry = queuedEntry,
                                entryPath = entryPath,
                                entryId = entry.entryId,
                            )
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    val exception = error as? Exception ?: Exception(error)
                    LogKit.e(AppStrings.ui_file_task_queue_failed_task_arg0.format(arg0 = (task.key).toString()), exception)
                    return Result.failure(exception)
                }
            }
        }

        try {
            taskState.putOverallProgress(task, processedEntries, totalEntries)
            val currentQueue = resolveNextPendingRuntimeQueue(
                taskKey = task.key,
                preferredStage = runState.currentStage,
                preferredCategory = runState.currentQueueCategory,
            )
            taskState.putRuntimeStatus(
                task = task,
                phase = TaskRuntimePhase.EXECUTING,
                stage = currentQueue?.stage,
                category = currentQueue?.category,
                queueFile = runState.currentQueueFile
                    .takeIf { item ->
                        item.isNotBlank() &&
                            runState.currentStage == currentQueue?.stage &&
                            runState.currentQueueCategory == currentQueue?.category
                    },
                remainingEntries = runtimeMeta.remainingEntries,
                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
            )
            runState.currentEntryPath
                .takeIf { item -> item.isNotBlank() }
                ?.let { path -> taskState.putValue(task, "path", path) }
            while (true) {
                ensureQueuedTaskRunning(task)
                val currentQueue = activeQueue ?: resolveNextPendingRuntimeQueue(
                    taskKey = task.key,
                    preferredStage = runState.currentStage,
                    preferredCategory = runState.currentQueueCategory,
                )?.also { queue -> activeQueue = queue } ?: break
                val currentStage = currentQueue.stage
                val currentCategory = currentQueue.category
                if (currentStage == TaskRuntimeStage.COPY &&
                    currentCategory == TaskRuntimeQueueCategory.DIRECTORIES
                ) {
                    val parallelResult = executeCopyDirectoryQueueAdaptive(currentQueue)
                    if (parallelResult.isFailure) {
                        return parallelResult
                    }
                    activeQueue = null
                    continue
                }
                if (currentStage == TaskRuntimeStage.COPY &&
                    currentCategory == TaskRuntimeQueueCategory.EMPTY_FILES
                ) {
                    val parallelResult = executeCopyEmptyFileQueueAdaptive(currentQueue)
                    if (parallelResult.isFailure) {
                        return parallelResult
                    }
                    activeQueue = null
                    continue
                }
                if (currentStage == TaskRuntimeStage.COPY &&
                    currentCategory == TaskRuntimeQueueCategory.FILES
                ) {
                    val parallelResult = executeCopyFileQueueAdaptive(currentQueue)
                    if (parallelResult.isFailure) {
                        return parallelResult
                    }
                    activeQueue = null
                    continue
                }
                if ((currentStage == TaskRuntimeStage.DELETE_SOURCE || currentStage == TaskRuntimeStage.DELETE) &&
                    currentCategory == TaskRuntimeQueueCategory.FILES
                ) {
                    val parallelResult = executeDeleteFileQueueAdaptive(currentQueue)
                    if (parallelResult.isFailure) {
                        return parallelResult
                    }
                    activeQueue = null
                    continue
                }
                val queuedEntry = taskState.peekNextRuntimeQueueEntry(
                    taskKey = task.key,
                    stage = currentStage,
                    category = currentCategory,
                    preferredFile = runState.currentQueueFile
                        .takeIf { item ->
                            item.isNotBlank() &&
                                runState.currentStage == currentStage &&
                                runState.currentQueueCategory == currentCategory
                        },
                )
                if (queuedEntry == null) {
                    runState = runState.copy(
                        currentPhase = TaskRuntimePhase.EXECUTING,
                        currentStage = currentStage,
                        currentQueueCategory = currentCategory,
                        currentQueueFile = "",
                        currentEntryPath = "",
                    )
                    runtimeMeta = runtimeMeta.copy(
                        currentPhase = TaskRuntimePhase.EXECUTING,
                        resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                    activeQueue = null
                    flushRuntimeState(force = true)
                    continue
                }
                val entry = queuedEntry.entry
                val entryPath = entry.displayPath()
                val startAt = Clock.System.now().toEpochMilliseconds()
                runState = runState.copy(
                    currentPhase = TaskRuntimePhase.EXECUTING,
                    currentStage = currentStage,
                    currentQueueCategory = currentCategory,
                    currentQueueFile = queuedEntry.fileName,
                    currentEntryPath = entryPath,
                    lastStartedAt = startAt,
                )
                runtimeMeta = runtimeMeta.copy(
                    currentPhase = TaskRuntimePhase.EXECUTING,
                    resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                    lastError = "",
                    updatedAt = startAt,
                )
                flushRuntimeState()

                val result = when (currentStage) {
                    TaskRuntimeStage.COPY -> executeCopyQueueEntry(task, entry)
                    TaskRuntimeStage.DELETE_SOURCE,
                    TaskRuntimeStage.DELETE -> executeDeleteQueueEntry(task, entry)
                }
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) {
                    throw failure
                }
                if (taskState.isTaskCancelled(task.key)) {
                    throw CancellationException(AppStrings.message_task_cancelled)
                }
                if (result.getOrNull() != true) {
                    val message = failure?.message ?: AppStrings.ui_task_execution_failed
                    if (
                        shouldContinueRuntimeQueueAfterFailure(
                            taskType = task.taskType,
                            stage = currentStage,
                            failure = failure,
                        )
                    ) {
                        LogKit.w(AppStrings.ui_queue_entry_failed_logged_continued_path_arg0_error_arg1.format(arg0 = entryPath, arg1 = message))
                        ackQueuedEntry(
                            currentStage = currentStage,
                            currentCategory = currentCategory,
                            queuedEntry = queuedEntry,
                            entryPath = entryPath,
                            lastError = message,
                        )
                        continue
                    }
                    runtimeMeta = runtimeMeta.copy(
                        currentPhase = TaskRuntimePhase.EXECUTING,
                        resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                        lastError = message,
                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                    flushRuntimeState(force = true)
                    return Result.failure(failure ?: Exception(message))
                }

                ackQueuedEntry(
                    currentStage = currentStage,
                    currentCategory = currentCategory,
                    queuedEntry = queuedEntry,
                    entryPath = entryPath,
                )
            }
            taskState.putOverallProgress(task, totalEntries, totalEntries)
            return Result.success(true)
        } catch (cancel: CancellationException) {
            return Result.failure(cancel)
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_file_task_execution_failed_task_arg0_type_arg1.format(arg0 = (task.key).toString(), arg1 = (runtimeMeta.taskType).toString()), error)
            runtimeMeta = runtimeMeta.copy(
                currentPhase = TaskRuntimePhase.EXECUTING,
                resumedFromRecovery = resumedFromRecovery || runtimeMeta.resumedFromRecovery,
                lastError = error.message.orEmpty(),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )
            flushRuntimeState(force = true)
            return Result.failure(error)
        }
    }

    private suspend fun prepareCopyRuntime(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): TaskRuntimeMeta {
        taskState.loadRuntimeMeta(task.key)
            ?.takeIf { taskState.hasPendingRuntimeEntries(task.key) }
            ?.let { meta -> return meta }
        taskState.putCopyScanProgress(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
            remainingEntries = 0,
            resumedFromRecovery = false,
        )
        val copyPlan = buildCopyQueueEntries(
            src = src,
            dest = dest,
            ensureRunning = { ensureQueuedTaskRunning(task) },
            requestBatchId = buildTaskScanRequestBatchId(task),
            onScanProgress = taskState.buildCopyScanProgressPublisher(task),
            onRejectedEntry = { entry, error ->
                recordRejectedCopyEntry(task, src, dest, entry, error)
            },
        )
        publishCompletedCopyScanProgress(task, copyPlan)
        recordIgnoredSkipCount(task, copyPlan)
        return persistQueuedRuntime(
            task = task,
            source = src.toEndpointRef(),
            target = dest.toEndpointRef(),
            entriesByStage = mapOf(TaskRuntimeStage.COPY to copyPlan.entries),
        )
    }


    private fun currentDeviceTransferStatus(protocolId: String) = resolveDevice(currentDesk(), protocolId)
        ?.paths
        ?.transferStatus()
        ?.clamped()
        ?.takeIf { status -> status.sampledAtMillis > 0L }

    private fun currentDeviceFileTransferStatus(protocolId: String) = resolveDevice(currentDesk(), protocolId)
        ?.let { device -> device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient }
        ?.transferStatus()
        ?.clamped()
        ?.takeIf { status -> status.sampledAtMillis > 0L }

    private fun currentDeviceTransferStatusBusy(protocolId: String): Boolean {
        val status = currentDeviceTransferStatus(protocolId) ?: return false
        return status.busy ||
            (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
    }

    private fun currentDeviceFileTransferStatusBusy(protocolId: String): Boolean {
        val status = currentDeviceFileTransferStatus(protocolId) ?: return false
        return status.busy ||
            (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
    }

    private fun currentCopyQueueFileTransferStatus(ref: TaskRuntimeEndpointRef) = when (ref.protocol) {
        FileProtocol.Device -> currentDeviceFileTransferStatus(ref.protocolId)
        FileProtocol.Share -> resolveShareFileClient(ref.protocolId)
            ?.transferStatus()
            ?.clamped()
            ?.takeIf { status -> status.sampledAtMillis > 0L }
        else -> null
    }

    fun resolveOperationParallelismForEndpoint(
        endpointKind: TraversalEndpointKind,
        protocolId: String,
    ): OperationParallelismConfig {
        val transferStatus = if (endpointKind == TraversalEndpointKind.Device) {
            currentDeviceTransferStatus(protocolId)
        } else {
            null
        }
        return resolveOperationParallelism(
            endpointKind = endpointKind,
            remoteRecommendedParallelism = transferStatus?.recommendedParallelRequests,
            remoteBusy = endpointKind == TraversalEndpointKind.Device && currentDeviceTransferStatusBusy(protocolId),
        )
    }

    fun resolveOperationRuntimeMaxForEndpoint(
        endpointKind: TraversalEndpointKind,
        protocolId: String,
    ): Int {
        val transferStatus = if (endpointKind == TraversalEndpointKind.Device) {
            currentDeviceTransferStatus(protocolId)
        } else {
            null
        }
        return resolveOperationRuntimeMaxParallelism(
            endpointKind = endpointKind,
            remoteRecommendedParallelism = transferStatus?.recommendedParallelRequests,
            remoteBusy = endpointKind == TraversalEndpointKind.Device && currentDeviceTransferStatusBusy(protocolId),
        )
    }

    private fun resolveCopyQueueOperationEndpointKind(entries: List<TaskRuntimeQueueEntry>): TraversalEndpointKind {
        val protocols = entries.flatMap { entry -> entry.copyEndpointRefs() }.map { ref -> ref.protocol }.toSet()
        return when {
            FileProtocol.Device in protocols || FileProtocol.Share in protocols -> TraversalEndpointKind.Device
            FileProtocol.Network in protocols -> TraversalEndpointKind.Network
            else -> TraversalEndpointKind.Local
        }
    }

    private fun resolveCopyQueueRemoteRecommendedParallelism(entries: List<TaskRuntimeQueueEntry>): Int? {
        return entries
            .asSequence()
            .flatMap { entry -> entry.copyEndpointRefs().asSequence() }
            .mapNotNull { ref -> currentCopyQueueFileTransferStatus(ref) }
            .minOfOrNull { status -> status.recommendedParallelRequests }
    }

    private fun resolveCopyQueueRemoteBusy(entries: List<TaskRuntimeQueueEntry>): Boolean {
        return entries
            .asSequence()
            .flatMap { entry -> entry.copyEndpointRefs().asSequence() }
            .mapNotNull { ref -> currentCopyQueueFileTransferStatus(ref) }
            .any { status ->
                status.busy ||
                    (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
            }
    }


    private fun resolveDeleteQueueOperationEndpointKind(entries: List<TaskRuntimeQueueEntry>): TraversalEndpointKind {
        val protocols = entries.flatMap { entry -> entry.deleteEndpointRefs() }.map { ref -> ref.protocol }.toSet()
        return when {
            FileProtocol.Device in protocols -> TraversalEndpointKind.Device
            FileProtocol.Network in protocols -> TraversalEndpointKind.Network
            FileProtocol.Share in protocols -> TraversalEndpointKind.Share
            else -> TraversalEndpointKind.Local
        }
    }

    private fun planRuntimeCopyArchiveSelection(
        task: Task,
        queuedEntries: List<TaskRuntimeQueuedEntry>,
        queueEntries: List<TaskRuntimeQueueEntry>,
    ): RuntimeSmallFileArchiveSelection? {
        if (queuedEntries.size < 2) return null
        val sourceProtocol = queueEntries.map { entry -> entry.srcProtocol }.distinct().singleOrNull() ?: return null
        val destinationProtocol = queueEntries.map { entry -> entry.destProtocol }.distinct().singleOrNull() ?: return null
        val sourceProtocolId = queueEntries.map { entry -> entry.srcProtocolId }.distinct().singleOrNull().orEmpty()
        val destinationProtocolId = queueEntries.map { entry -> entry.destProtocolId }.distinct().singleOrNull().orEmpty()
        if (
            !shouldPlanRuntimeCopyArchive(
                sourceProtocol = sourceProtocol,
                destinationProtocol = destinationProtocol,
                sourceProtocolId = sourceProtocolId,
                destinationProtocolId = destinationProtocolId,
            )
        ) {
            return null
        }
        val destinationRootPath = taskState.loadRuntimeMeta(task.key)
            ?.target
            ?.path
            ?.takeIf { path -> path.isNotBlank() }
            ?: return null
        return selectRuntimeSmallFileArchiveBatches(
            queuedEntries = queuedEntries,
            destinationRootPath = destinationRootPath,
            destinationSeparator = resolveProtocolPathSeparator(
                protocol = destinationProtocol,
                protocolId = destinationProtocolId.takeIf { destinationProtocol == FileProtocol.Device }.orEmpty(),
            ),
        )
    }

    private suspend fun processRuntimeCopyArchiveBatches(
        task: Task,
        currentQueue: PendingRuntimeQueue,
        selection: RuntimeSmallFileArchiveSelection,
        operationConfig: OperationParallelismConfig,
        stateMutex: Mutex,
        updateActiveParallelism: suspend (Int) -> Unit,
        ackQueuedEntry: (
            currentStage: TaskRuntimeStage,
            currentCategory: TaskRuntimeQueueCategory,
            queuedEntry: TaskRuntimeQueuedEntry,
            entryPath: String,
            lastError: String,
            entryId: String?,
        ) -> Unit,
        ensureRunning: suspend () -> Unit,
        currentRuntimeMaxParallelism: () -> Int,
    ): Set<String> {
        val queueEntries = selection.batches.flatMap { batch -> batch.queuedEntries.map { item -> item.entry } }
        val sourceProtocol = queueEntries.map { entry -> entry.srcProtocol }.distinct().singleOrNull() ?: return emptySet()
        val destinationProtocol = queueEntries.map { entry -> entry.destProtocol }.distinct().singleOrNull() ?: return emptySet()
        val sourceClient = when (sourceProtocol) {
            FileProtocol.Device -> {
                val sourceId = queueEntries.map { entry -> entry.srcProtocolId }.distinct().singleOrNull() ?: return emptySet()
                resolveDeviceFileClient(currentDesk(), sourceId) ?: return emptySet()
            }
            FileProtocol.Share -> {
                val sourceId = queueEntries.map { entry -> entry.srcProtocolId }.distinct().singleOrNull() ?: return emptySet()
                resolveShareFileClient(sourceId) ?: return emptySet()
            }
            else -> null
        }
        val targetClient = if (destinationProtocol == FileProtocol.Device) {
            val targetId = queueEntries.map { entry -> entry.destProtocolId }.distinct().singleOrNull() ?: return emptySet()
            resolveDeviceFileClient(currentDesk(), targetId) ?: return emptySet()
        } else {
            null
        }
        val coordinator = SmallFileArchiveTransferCoordinator(
            sourceClient = sourceClient,
            targetClient = targetClient,
            transportPreference = SmallFileArchiveTransportPreference.LAN,
        )
        coordinator.negotiate()
        val destinationRootPath = taskState.loadRuntimeMeta(task.key)
            ?.target
            ?.path
            ?.takeIf { path -> path.isNotBlank() }
            ?: return emptySet()
        val batchCount = selection.batches.size
        val batchConfig = archiveTransferBatchOperationConfig(operationConfig, batchCount)
        val completedEntryIds = mutableSetOf<String>()
        val completedMutex = Mutex()
        processItemsAdaptive(
            items = selection.batches,
            config = batchConfig,
            ensureRunning = ensureRunning,
            dynamicMaxParallelismProvider = {
                archiveTransferBatchRuntimeMax(
                    runtimeMaxParallelism = currentRuntimeMaxParallelism(),
                    batchCount = batchCount,
                )
            },
            onActiveParallelismChanged = { activeCount ->
                stateMutex.withLock {
                    updateActiveParallelism(activeCount)
                }
            },
        ) { batch ->
            ensureRunning()
            val batchProgress = mutableMapOf<String, Long>()
            val result = coordinator.transferBatch(
                entries = batch.requestEntries,
                destinationRootPath = destinationRootPath,
                onFileBytes = { relativePath, bytes ->
                    ensureRunning()
                    val requestEntry = batch.requestEntries.firstOrNull { item -> item.relativePath == relativePath }
                        ?: return@transferBatch
                    val queuedEntry = batch.queuedEntries.firstOrNull { item -> item.entry.srcPath == requestEntry.sourcePath }
                        ?: return@transferBatch
                    val entry = queuedEntry.entry
                    taskState.startRuntimeByteItem(task, entry.destPath, entry.size)
                    val current = batchProgress.getOrElse(entry.entryId) { 0L }
                    val updated = (current + bytes).coerceAtMost(entry.size)
                    batchProgress[entry.entryId] = updated
                    taskState.putRuntimeByteProgress(task, entry.destPath, updated)
                },
            )
            if (result.isFailure) {
                LogKit.w(AppStrings.ui_archiving_small_files_runtime_queue_failed_unfinished_entries_were.format(arg0 = result.exceptionOrNull()?.message.orEmpty()))
            }
            val completed = result.getOrNull()?.completedRelativePaths.orEmpty() +
                result.exceptionOrNull()?.completedArchiveTransferResult()?.completedRelativePaths.orEmpty()
            batch.queuedEntries.forEachIndexed { index, queuedEntry ->
                ensureRunning()
                val requestEntry = batch.requestEntries.getOrNull(index) ?: return@forEachIndexed
                if (requestEntry.relativePath !in completed) return@forEachIndexed
                val entry = queuedEntry.entry
                val entryPath = entry.displayPath()
                taskState.startRuntimeByteItem(task, entry.destPath, entry.size)
                taskState.finishRuntimeByteItem(task, entry.destPath, entry.size)
                resolveSuccessfulMainQueueEntry(task, entry.toCopyRetryEntry(task.taskType))
                stateMutex.withLock {
                    ackQueuedEntry(
                        currentQueue.stage,
                        currentQueue.category,
                        queuedEntry,
                        entryPath,
                        "",
                        entry.entryId,
                    )
                }
                completedMutex.withLock {
                    completedEntryIds += entry.entryId
                }
            }
        }
        return completedMutex.withLock { completedEntryIds.toSet() }
    }

    private fun resolveDeleteQueueRemoteRecommendedParallelism(entries: List<TaskRuntimeQueueEntry>): Int? {
        return entries
            .asSequence()
            .flatMap { entry -> entry.deleteEndpointRefs().asSequence() }
            .filter { ref -> ref.protocol == FileProtocol.Device }
            .map { ref -> ref.protocolId }
            .filter { protocolId -> protocolId.isNotBlank() }
            .distinct()
            .mapNotNull { protocolId -> currentDeviceFileTransferStatus(protocolId) }
            .minOfOrNull { status -> status.recommendedParallelRequests }
    }

    private fun resolveDeleteQueueRemoteBusy(entries: List<TaskRuntimeQueueEntry>): Boolean {
        return entries
            .asSequence()
            .flatMap { entry -> entry.deleteEndpointRefs().asSequence() }
            .filter { ref -> ref.protocol == FileProtocol.Device }
            .map { ref -> ref.protocolId }
            .filter { protocolId -> protocolId.isNotBlank() }
            .distinct()
            .any { protocolId -> currentDeviceFileTransferStatusBusy(protocolId) }
    }




    private suspend fun prepareMoveRuntime(
        task: Task,
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
    ): TaskRuntimeMeta {
        taskState.loadRuntimeMeta(task.key)
            ?.takeIf { taskState.hasPendingRuntimeEntries(task.key) }
            ?.let { meta -> return meta }
        taskState.putCopyScanProgress(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
            remainingEntries = 0,
            resumedFromRecovery = false,
        )
        val copyPlan = buildCopyQueueEntries(
            src = src,
            dest = dest,
            ensureRunning = { ensureQueuedTaskRunning(task) },
            requestBatchId = buildTaskScanRequestBatchId(task),
            onScanProgress = taskState.buildCopyScanProgressPublisher(task),
            onRejectedEntry = { entry, error ->
                recordRejectedCopyEntry(task, src, dest, entry, error)
            },
        )
        publishCompletedCopyScanProgress(task, copyPlan)
        recordIgnoredSkipCount(task, copyPlan)
        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(copyPlan, TaskRuntimeStage.DELETE_SOURCE)
        return persistQueuedRuntime(
            task = task,
            source = src.toEndpointRef(),
            target = dest.toEndpointRef(),
            entriesByStage = linkedMapOf(
                TaskRuntimeStage.COPY to copyPlan.entries,
                TaskRuntimeStage.DELETE_SOURCE to deleteEntries,
            ),
        )
    }

    private fun recordIgnoredSkipCount(
        task: Task,
        copyPlan: CopyQueueBuildResult,
    ) {
        if (copyPlan.skippedCount > 0) {
            taskState.putValue(task, TASK_IGNORED_SKIP_COUNT_VALUE_KEY, copyPlan.skippedCount.toString())
        } else {
            taskState.removeValue(task, TASK_IGNORED_SKIP_COUNT_VALUE_KEY)
        }
    }

    private fun publishCompletedCopyScanProgress(
        task: Task,
        copyPlan: CopyQueueBuildResult,
    ) {
        val discoveredEntries = copyPlan.entries.totalSize + copyPlan.skippedCount + copyPlan.rejectedCount
        if (discoveredEntries > 0) {
            taskState.putCopyScanProgress(task, discoveredEntries)
        }
    }

    private fun recordRejectedCopyEntry(
        task: Task,
        sourceRoot: FileSimpleInfo,
        targetRoot: FileSimpleInfo,
        entry: FileSimpleInfo,
        error: Throwable,
    ) {
        taskState.recordRetryFailure(
            task = task,
            entry = buildCopyRetryEntry(
                taskType = task.taskType,
                src = entry,
                dest = entry.toCopyDestination(sourceRoot, targetRoot),
            ),
            message = error.message.orEmpty(),
            fallback = AppStrings.file_symbolic_link_copy_not_supported,
        )
    }

    private suspend fun prepareDeleteRuntime(
        task: Task,
        target: FileSimpleInfo,
    ): TaskRuntimeMeta {
        taskState.loadRuntimeMeta(task.key)
            ?.takeIf { taskState.hasPendingRuntimeEntries(task.key) }
            ?.let { meta -> return meta }
        taskState.putDeleteScanProgress(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
            remainingEntries = 0,
            resumedFromRecovery = false,
        )
        val entries = buildDeleteQueueEntries(
            target = target,
            stage = TaskRuntimeStage.DELETE,
            ensureRunning = { ensureQueuedTaskRunning(task) },
            requestBatchId = buildTaskScanRequestBatchId(task),
            onScanProgress = taskState.buildDeleteScanProgressPublisher(task),
        )
        publishCompletedDeleteScanProgress(task, entries)
        return persistQueuedRuntime(
            task = task,
            source = target.toEndpointRef(),
            target = null,
            entriesByStage = mapOf(TaskRuntimeStage.DELETE to entries),
        )
    }

    private fun persistQueuedRuntime(
        task: Task,
        source: TaskRuntimeEndpointRef,
        target: TaskRuntimeEndpointRef?,
        entriesByStage: Map<TaskRuntimeStage, TaskRuntimeQueueEntriesByCategory>,
    ): TaskRuntimeMeta {
        taskState.clearRuntime(task.key)
        val now = Clock.System.now().toEpochMilliseconds()
        val totalEntries = entriesByStage.values.sumOf { entries -> entries.totalSize }
        val copyEntries = entriesByStage[TaskRuntimeStage.COPY]
        val copyByteTotal = copyEntries?.totalFileBytes ?: 0L
        entriesByStage.forEach { (stage, entries) ->
            entries.byCategory.forEach { (category, categoryEntries) ->
                taskState.appendRuntimeQueueEntries(
                    taskKey = task.key,
                    stage = stage,
                    category = category,
                    entries = categoryEntries.sortedBy { entry -> entry.order },
                )
            }
        }
        val meta = TaskRuntimeMeta(
            taskKey = task.key,
            taskType = task.taskType,
            source = source,
            target = target,
            currentPhase = TaskRuntimePhase.EXECUTING,
            scanCompleted = true,
            resumedFromRecovery = false,
            totalEntries = totalEntries,
            remainingEntries = totalEntries,
            createdAt = now,
            updatedAt = now,
        )
        taskState.saveRuntimeMeta(meta)
        val runState = buildInitialRunState(task, meta)
        taskState.saveRuntimeRunState(runState)
        taskState.putOverallProgress(task, 0, totalEntries.coerceAtLeast(1))
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = runState.currentStage,
            category = runState.currentQueueCategory,
            queueFile = runState.currentQueueFile.takeIf { item -> item.isNotBlank() },
            remainingEntries = totalEntries,
            resumedFromRecovery = false,
        )
        val initialPath = runState.currentEntryPath
            .takeIf { item -> item.isNotBlank() }
            ?: target?.path?.takeIf { item -> item.isNotBlank() }
            ?: source.path
        if (initialPath.isNotBlank()) {
            taskState.putValue(task, "path", initialPath)
        }
        if (shouldUseCopyByteRuntimeMetrics(copyEntries)) {
            taskState.beginRuntimeByteMetrics(task, copyByteTotal, startedAt = meta.createdAt)
        } else {
            taskState.beginRuntimeItemMetrics(
                task,
                completed = 0,
                total = totalEntries.coerceAtLeast(1),
                startedAt = meta.createdAt,
            )
        }
        return meta
    }

    private fun buildInitialRunState(
        task: Task,
        meta: TaskRuntimeMeta,
    ): TaskRuntimeRunState {
        val queue = resolveNextPendingRuntimeQueue(task.key)
        val queueFile = queue?.let { currentQueue ->
            taskState.listRuntimeQueueFiles(task.key, currentQueue.stage, currentQueue.category).firstOrNull()
        }
        return TaskRuntimeRunState(
            taskKey = task.key,
            runId = buildTaskRuntimeRunId(task.key),
            currentPhase = TaskRuntimePhase.EXECUTING,
            currentStage = queue?.stage,
            currentQueueCategory = queue?.category,
            currentQueueFile = queueFile.orEmpty(),
            currentEntryPath = meta.target?.path?.takeIf { item -> item.isNotBlank() }
                ?: meta.source.path,
        )
    }

    private fun buildFallbackRuntimeMeta(
        task: Task,
        remainingEntries: Int,
    ): TaskRuntimeMeta {
        val now = Clock.System.now().toEpochMilliseconds()
        return TaskRuntimeMeta(
            taskKey = task.key,
            taskType = task.taskType,
            source = TaskRuntimeEndpointRef(
                protocol = task.protocol,
                protocolId = task.protocolId,
                path = task.values["path"].orEmpty(),
            ),
            target = null,
            currentPhase = TaskRuntimePhase.EXECUTING,
            scanCompleted = true,
            resumedFromRecovery = true,
            totalEntries = remainingEntries,
            remainingEntries = remainingEntries,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun resolveNextPendingRuntimeQueue(
        taskKey: Long,
        preferredStage: TaskRuntimeStage? = null,
        preferredCategory: TaskRuntimeQueueCategory? = null,
    ): PendingRuntimeQueue? {
        val orderedStages = listOf(
            TaskRuntimeStage.COPY,
            TaskRuntimeStage.DELETE_SOURCE,
            TaskRuntimeStage.DELETE,
        )
        preferredStage?.let { stage ->
            resolveNextPendingRuntimeCategory(taskKey, stage, preferredCategory)
                ?.let { category -> return PendingRuntimeQueue(stage, category) }
        }
        return orderedStages.firstNotNullOfOrNull { stage ->
            resolveNextPendingRuntimeCategory(taskKey, stage)
                ?.let { category -> PendingRuntimeQueue(stage, category) }
        }
    }

    private fun publishCompletedDeleteScanProgress(
        task: Task,
        entries: TaskRuntimeQueueEntriesByCategory,
    ) {
        val discoveredEntries = entries.totalSize
        if (discoveredEntries > 0) {
            taskState.putDeleteScanProgress(task, discoveredEntries)
        }
    }

    fun buildTaskScanRequestBatchId(task: Task): String {
        return "task-scan:${task.key}"
    }

    private fun resolveNextPendingRuntimeCategory(
        taskKey: Long,
        stage: TaskRuntimeStage,
        preferredCategory: TaskRuntimeQueueCategory? = null,
    ): TaskRuntimeQueueCategory? {
        val categories = stage.orderedQueueCategories()
        preferredCategory
            ?.takeIf { category -> category in categories }
            ?.takeIf { category -> taskState.listRuntimeQueueFiles(taskKey, stage, category).isNotEmpty() }
            ?.let { category -> return category }
        return categories.firstOrNull { category ->
            taskState.listRuntimeQueueFiles(taskKey, stage, category).isNotEmpty()
        }
    }

    private suspend fun buildCopyQueueEntries(
        src: FileSimpleInfo,
        dest: FileSimpleInfo,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
        onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    ): CopyQueueBuildResult {
        ensureRunning()
        val sourceSeparator = resolveProtocolPathSeparator(src.protocol, src.protocolId)
        val ignoreMatcher = operationIgnoreResolver.resolveForSource(src, sourceSeparator)
        ensureRunning()
        var rejectedCount = 0
        val children = if (src.isDirectory) {
            collectDirectoryEntries(
                root = src,
                ensureRunning = ensureRunning,
                requestBatchId = requestBatchId,
                onScanProgress = onScanProgress,
                onEntriesDiscovered = onEntriesDiscovered,
                rejectSymbolicLinkEntries = true,
                onRejectedEntry = { entry, error ->
                    rejectedCount++
                    onRejectedEntry(entry, error)
                },
            )
        } else {
            emptyList()
        }
        ensureRunning()
        return buildCopyQueueEntriesForOperation(
            src = src,
            dest = dest,
            sourceSeparator = sourceSeparator,
            ignoreMatcher = ignoreMatcher,
            children = children,
        ).copy(rejectedCount = rejectedCount)
    }

    private suspend fun buildDeleteQueueEntries(
        target: FileSimpleInfo,
        stage: TaskRuntimeStage,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
    ): TaskRuntimeQueueEntriesByCategory {
        ensureRunning()
        val entries = if (target.isDirectory) {
            collectDirectoryEntries(
                root = target,
                ensureRunning = ensureRunning,
                requestBatchId = requestBatchId,
                onScanProgress = onScanProgress,
                onEntriesDiscovered = onEntriesDiscovered,
            ) + target
        } else {
            listOf(target)
        }
        ensureRunning()
        val files = entries
            .filterNot { entry -> entry.isDirectory }
            .sortedWith(
                compareByDescending<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                    .thenBy { entry -> entry.path }
            )
            .mapIndexed { index, entry ->
                buildDeleteQueueEntry(
                    order = index,
                    stage = stage,
                    target = entry,
                )
            }
        val directories = entries
            .filter { entry -> entry.isDirectory }
            .sortedWith(
                compareByDescending<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                    .thenBy { entry -> entry.path }
            )
            .mapIndexed { index, entry ->
                buildDeleteQueueEntry(
                    order = index,
                    stage = stage,
                    target = entry,
                )
            }
        return TaskRuntimeQueueEntriesByCategory(directories = directories, files = files)
    }

    private suspend fun collectDirectoryEntries(
        root: FileSimpleInfo,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
        rejectSymbolicLinkEntries: Boolean = false,
        onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    ): List<FileSimpleInfo> = directoryCollector.collectDirectoryEntries(
        root = root,
        ensureRunning = ensureRunning,
        requestBatchId = requestBatchId,
        onScanProgress = onScanProgress,
        onEntriesDiscovered = onEntriesDiscovered,
        rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
        onRejectedEntry = onRejectedEntry,
    )

    suspend fun ensureQueuedTaskRunning(task: Task) {
        if (!taskState.awaitIfPaused(task.key)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
        if (taskState.isTaskCancelled(task.key)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
    }

    private suspend fun executeCopyQueueEntry(
        task: Task,
        entry: TaskRuntimeQueueEntry,
    ): Result<Boolean> {
        val trackedEntry = entry.toCopyRetryEntry(task.taskType)
        if (entry.kind == TaskRuntimeEntryKind.DIRECTORY_CREATE) {
            taskState.putCreatingFolderProgress(task, entry.destPath)
            val result = createDirectoryForQueueEntry(entry)
            return recordDirectoryCreateResult(task, trackedEntry, result)
        }
        if (entry.kind == TaskRuntimeEntryKind.EMPTY_FILE_CREATE) {
            taskState.putCreatingEmptyFileProgress(task, entry.destPath)
            val result = createEmptyFileForQueueEntry(entry)
            if (result.isSuccess && result.getOrDefault(false)) {
                resolveSuccessfulMainQueueEntry(task, trackedEntry)
            }
            val failure = result.exceptionOrNull()
            if ((result.isFailure || !result.getOrDefault(false)) && !failure.isTaskLevelTransferFailure()) {
                if (taskState.getFailedRetryEntries(task).none { item -> item.entryKey == trackedEntry.entryKey }) {
                    taskState.recordRetryFailure(
                        task = task,
                        entry = trackedEntry,
                        message = failure?.message.orEmpty(),
                        fallback = AppStrings.ui_failed_create_empty_file,
                    )
                }
            }
            return result
        }
        val src = entry.toSourceFileSimpleInfo()
        val dest = entry.toTargetFileSimpleInfo()
        val sourceDesk = resolveDeskForProtocol(entry.srcProtocol, entry.srcProtocolId)
        if (sourceDesk == null) {
            val failure = resolveMissingEndpointError(entry.srcProtocol, true)
            if (!failure.isTaskLevelTransferFailure() &&
                taskState.getFailedRetryEntries(task).none { item -> item.entryKey == trackedEntry.entryKey }
            ) {
                taskState.recordRetryFailure(
                    task = task,
                    entry = trackedEntry,
                    message = failure.message.orEmpty(),
                    fallback = AppStrings.ui_copy_failed,
                )
            }
            return Result.failure(failure)
        }
        val targetDesk = resolveDeskForProtocol(entry.destProtocol, entry.destProtocolId)
        if (targetDesk == null) {
            val failure = resolveMissingEndpointError(entry.destProtocol, false)
            if (!failure.isTaskLevelTransferFailure() &&
                taskState.getFailedRetryEntries(task).none { item -> item.entryKey == trackedEntry.entryKey }
            ) {
                taskState.recordRetryFailure(
                    task = task,
                    entry = trackedEntry,
                    message = failure.message.orEmpty(),
                    fallback = AppStrings.ui_copy_failed,
                )
            }
            return Result.failure(failure)
        }
        taskState.clearRetryFailureForRetry(task, trackedEntry)
        taskState.startRuntimeByteItem(task, entry.destPath, entry.size)
        val recoverableResult = copyRecoverableLargeFileQueueEntry(
            task = task,
            entry = entry,
            sourceDesk = sourceDesk,
            targetDesk = targetDesk,
        )
        val result = recoverableResult ?: runCopyAcrossEndpoints(task, src, dest, sourceDesk, targetDesk)
        if (result.isSuccess && result.getOrDefault(false)) {
            taskState.finishRuntimeByteItem(task, entry.destPath, entry.size)
            resolveSuccessfulMainQueueEntry(task, trackedEntry)
        } else if (!result.exceptionOrNull().isTaskLevelTransferFailure() &&
            taskState.getFailedRetryEntries(task).none { item -> item.entryKey == trackedEntry.entryKey }
        ) {
            taskState.clearRuntimeByteItem(task, entry.destPath)
            taskState.recordRetryFailure(
                task = task,
                entry = trackedEntry,
                message = result.exceptionOrNull()?.message.orEmpty(),
                fallback = AppStrings.ui_copy_failed,
            )
        } else {
            taskState.clearRuntimeByteItem(task, entry.destPath)
        }
        return result
    }

    private suspend fun copyRecoverableLargeFileQueueEntry(
        task: Task,
        entry: TaskRuntimeQueueEntry,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
    ): Result<Boolean>? {
        if (!entry.shouldUseTransferRecoveryForCopyQueue()) {
            taskState.deleteRuntimeTransferCheckpoint(task.key, entry.entryId)
            return null
        }

        val sourceClient = when (entry.srcProtocol) {
            FileProtocol.Local -> null
            FileProtocol.Device -> resolveDeviceFileClient(sourceDesk, entry.srcProtocolId)
                ?: return Result.failure(
                    DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
                )

            FileProtocol.Share -> resolveShareFileClient(entry.srcProtocolId) ?: return null

            FileProtocol.Network -> return null
        }
        val targetClient = when (entry.destProtocol) {
            FileProtocol.Local -> null
            FileProtocol.Device -> resolveDeviceFileClient(targetDesk, entry.destProtocolId)
                ?: return Result.failure(
                    DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                )

            FileProtocol.Share,
            FileProtocol.Network -> return null
        }

        val chunkSize = resolveTransferRecoveryChunkSize(sourceDesk, targetDesk)
        val totalChunks = calculateDeviceTransportChunkCount(entry.size, chunkSize)
        val endpointKind = resolveCopyQueueOperationEndpointKind(listOf(entry))
        val operationConfig = resolveOperationParallelism(
            endpointKind = endpointKind,
            remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(listOf(entry)),
            remoteBusy = resolveCopyQueueRemoteBusy(listOf(entry)),
        ).let { config ->
            config.copy(
                initialParallelism = minOf(config.initialParallelism, totalChunks).coerceAtLeast(1),
                maxParallelism = minOf(config.maxParallelism, totalChunks).coerceAtLeast(1),
                queueCapacity = minOf(config.queueCapacity, maxOf(4, totalChunks * 4)),
                hardMaxParallelism = minOf(config.hardMaxParallelism, totalChunks).coerceAtLeast(1),
            )
        }
        val useChunkOverallProgress = shouldUseChunkOverallProgress(task, entry)
        var lastProgressAt = 0L
        val progressGate = TaskProgressUpdateGate()
        val progressRateSampler = TaskProgressRateSampler(
            initialCompleted = 0L,
            initialAt = Clock.System.now().toEpochMilliseconds(),
        )
        taskState.putValue(task, "path", entry.destPath)
        if (useChunkOverallProgress) {
            taskState.putOverallProgress(task, 0, totalChunks)
        }

        suspend fun readChunk(startOffset: Long, endOffset: Long): Result<ByteArray> {
            return when (entry.srcProtocol) {
                FileProtocol.Local -> FileUtils.readFileRange(
                    FileAccessPermission.Allowed,
                    entry.srcPath,
                    startOffset,
                    endOffset,
                )
                FileProtocol.Device -> sourceClient?.readBytes(entry.srcPath, startOffset, endOffset)
                    ?: Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
                    )

                FileProtocol.Share -> sourceClient?.readBytes(entry.srcPath, startOffset, endOffset)
                    ?: Result.failure(
                        ShareSessionUnavailableException(AppStrings.message_task_source_share_session_expired)
                    )

                FileProtocol.Network -> Result.failure(Exception(AppStrings.ui_not_supporting_file_recovery))
            }
        }

        suspend fun writeChunk(
            chunkIndex: Int,
            startOffset: Long,
            bytes: ByteArray,
        ): Result<Boolean> {
            return when (entry.destProtocol) {
                FileProtocol.Local -> FileUtils.writeBytes(
                    permission = FileAccessPermission.Allowed,
                    path = entry.destPath,
                    fileSize = entry.size,
                    data = bytes,
                    offset = startOffset,
                )

                FileProtocol.Device -> targetClient?.writeBytes(
                    fileSize = entry.size,
                    blockIndex = chunkIndex.toLong(),
                    blockLength = bytes.size.toLong(),
                    path = entry.destPath,
                    byteArray = bytes,
                    startOffset = startOffset,
                ) ?: Result.failure(
                    DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                )

                FileProtocol.Share,
                FileProtocol.Network -> Result.failure(Exception(AppStrings.ui_not_supporting_file_recovery))
            }
        }

        val copyChunkDirectly: (suspend (chunkIndex: Int, startOffset: Long, endOffset: Long) -> Result<Boolean>)? =
            if (
                entry.srcProtocol in setOf(FileProtocol.Device, FileProtocol.Share) &&
                entry.destProtocol == FileProtocol.Local &&
                sourceClient != null
            ) {
                { _, startOffset, endOffset ->
                    sourceClient.downloadRangeToFile(
                        remotePath = entry.srcPath,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        localPath = entry.destPath,
                        fileSize = entry.size,
                        localOffset = startOffset,
                    ) { _, _ -> }
                }
            } else {
                null
            }
        val continuousSourceClient = sourceClient as? ContinuousRangeDeviceFileClient
        val continuousTargetClient = targetClient as? ContinuousRangeDeviceFileClient
        val copyRemainingDirectly: RemainingFileDirectCopy? = when {
            entry.srcProtocol in setOf(FileProtocol.Device, FileProtocol.Share) &&
                entry.destProtocol == FileProtocol.Local &&
                continuousSourceClient != null -> {
                { startOffset, endOffset, onBytesCommitted ->
                    continuousSourceClient.downloadRangeToFile(
                        remotePath = entry.srcPath,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        localPath = entry.destPath,
                        fileSize = entry.size,
                        localOffset = startOffset,
                        onBytesWritten = onBytesCommitted,
                    )
                }
            }
            entry.srcProtocol == FileProtocol.Local &&
                entry.destProtocol == FileProtocol.Device &&
                continuousTargetClient != null -> {
                { startOffset, endOffset, onBytesCommitted ->
                    copyLocalRangeToContinuousDevice(
                        targetClient = continuousTargetClient,
                        localPath = entry.srcPath,
                        remotePath = entry.destPath,
                        fileSize = entry.size,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        ensureRunning = { ensureQueuedTaskRunning(task) },
                        onBytesCommitted = onBytesCommitted,
                    )
                }
            }
            else -> null
        }
        val allowParallelChunks = sourceClient !is SequentialRangeDeviceFileClient &&
            targetClient !is SequentialRangeDeviceFileClient

        return copyFileWithTransferCheckpoint(
            taskKey = task.key,
            entryId = entry.entryId,
            destPath = entry.destPath,
            fileSize = entry.size,
            chunkSize = chunkSize,
            loadCheckpoint = { taskState.loadRuntimeTransferCheckpoint(task.key, entry.entryId) },
            saveCheckpoint = { checkpoint -> taskState.saveRuntimeTransferCheckpoint(checkpoint) },
            deleteCheckpoint = { taskState.deleteRuntimeTransferCheckpoint(task.key, entry.entryId) },
            targetSize = {
                loadTransferTargetSize(
                    protocol = entry.destProtocol,
                    path = entry.destPath,
                    targetClient = targetClient,
                )
            },
            readChunk = ::readChunk,
            writeChunk = ::writeChunk,
            ensureRunning = { ensureQueuedTaskRunning(task) },
            operationConfig = operationConfig,
            dynamicMaxParallelismProvider = {
                resolveOperationRuntimeMaxParallelism(
                    endpointKind = endpointKind,
                    remoteRecommendedParallelism = resolveCopyQueueRemoteRecommendedParallelism(listOf(entry)),
                    remoteBusy = resolveCopyQueueRemoteBusy(listOf(entry)),
                ).coerceAtMost(totalChunks.coerceAtLeast(1))
            },
            copyChunkDirectly = copyChunkDirectly,
            copyRemainingDirectly = copyRemainingDirectly,
            allowParallelChunks = allowParallelChunks,
            onProgress = { progress ->
                val now = Clock.System.now().toEpochMilliseconds()
                val forceProgressUpdate = progress.completedChunks == progress.totalChunks
                if (progressGate.shouldPublish(force = forceProgressUpdate)) {
                    if (useChunkOverallProgress) {
                        taskState.putOverallProgress(task, progress.completedChunks, progress.totalChunks)
                    }
                    taskState.putRuntimeByteProgress(task, entry.destPath, progress.transferredBytes)
                }
                if (now - lastProgressAt >= 1000L || progress.completedChunks == progress.totalChunks) {
                    val rateSample = progressRateSampler.update(
                        completed = progress.transferredBytes,
                        total = entry.size,
                        now = now,
                        force = progress.completedChunks == progress.totalChunks,
                    )
                    taskState.putResult(
                        task,
                        entry.destPath,
                        buildWebRtcTransferProgressText(
                            transferredBytes = progress.transferredBytes,
                            totalBytes = entry.size,
                            chunkSize = chunkSize,
                            rateSample = rateSample,
                        )
                    )
                    lastProgressAt = now
                }
            },
        )
    }

    private suspend fun executeDeleteQueueEntry(
        task: Task,
        entry: TaskRuntimeQueueEntry,
    ): Result<Boolean> {
        val retryEntry = entry.toDeleteRetryEntry(task.taskType)
        val result = retryDeleteEntry(task, retryEntry)
        if (result.isSuccess && result.getOrDefault(false)) {
            resolveSuccessfulMainQueueEntry(task, retryEntry, clearResultWhenNoFailure = false)
            return result
        }
        if (!result.exceptionOrNull().isTaskLevelTransferFailure() &&
            taskState.getFailedRetryEntries(task).none { item -> item.entryKey == retryEntry.entryKey }
        ) {
            taskState.recordRetryFailure(
                task = task,
                entry = retryEntry,
                message = result.exceptionOrNull()?.message.orEmpty(),
                fallback = AppStrings.ui_delete_failed,
            )
        }
        return result
    }

    private fun resolveSuccessfulMainQueueEntry(
        task: Task,
        entry: TaskRetryEntry,
        clearResultWhenNoFailure: Boolean = false,
    ) {
        if (!taskState.hasInMemoryFailureMarkers(task)) {
            if (clearResultWhenNoFailure) {
                taskState.removeResult(task, entry.resultPath)
            }
            return
        }
        val latestTask = taskState.getTask(task.key) ?: task
        val failedEntries = taskState.getFailedRetryEntries(latestTask)
        val remainingFailures = failedEntries
            .filterNot { item -> item.entryKey == entry.entryKey }
        if (remainingFailures.size != failedEntries.size) {
            taskState.recordRetrySuccess(latestTask, entry, remainingFailures)
        } else {
            taskState.removeResult(latestTask, entry.resultPath)
        }
    }

    private suspend fun createDirectoryForQueueEntry(entry: TaskRuntimeQueueEntry): Result<Boolean> {
        return when (entry.destProtocol) {
            FileProtocol.Local -> FileUtils.createFolder(FileAccessPermission.Allowed, entry.destPath)

            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), entry.destProtocolId)
                    ?: return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    )
                device.files.createFolders(listOf(entry.destPath)).fold(
                    onSuccess = { results -> results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_folder_creation_failed)) },
                    onFailure = { failure -> Result.failure(failure) }
                )
            }

            FileProtocol.Network -> {
                val networkAccess = resolveNetworkAccess(currentDesk(), entry.destProtocolId)
                    ?: return Result.failure(
                        TaskEndpointUnavailableException(AppStrings.message_task_target_network_disconnected)
                    )
                val separator = resolveProtocolPathSeparator(entry.destProtocol, entry.destProtocolId)
                val (parentPath, name) = splitParentAndName(entry.destPath, separator)
                networkAccess.createFolder(parentPath, name)
            }

            FileProtocol.Share -> Result.failure(Exception(AppStrings.ui_shared_goals_do_not_support_creating_folders))
        }
    }

    private suspend fun executeDeviceDirectoryCreateBatch(
        task: Task,
        entries: List<TaskRuntimeQueueEntry>,
    ): Result<List<Result<Boolean>>> {
        if (entries.isEmpty()) return Result.success(emptyList())
        val protocolId = entries.first().destProtocolId
        if (entries.any { entry ->
                entry.kind != TaskRuntimeEntryKind.DIRECTORY_CREATE ||
                    entry.destProtocol != FileProtocol.Device ||
                    entry.destProtocolId != protocolId
            }
        ) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_target_device_for_batch_creating_folders_is_inconsistent))
        }
        val device = resolveDevice(currentDesk(), protocolId)
            ?: return Result.failure(
                DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
            )
        entries.forEach { entry ->
            taskState.putCreatingFolderProgress(task, entry.destPath)
        }
        val batchResult = device.files.createFolders(entries.map { entry -> entry.destPath })
        val batchFailure = batchResult.exceptionOrNull()
        if (batchFailure != null &&
            (batchFailure is CancellationException || batchFailure.isTaskLevelTransferFailure())
        ) {
            return Result.failure(batchFailure)
        }
        val rawResults = batchResult.getOrElse { failure ->
            List(entries.size) { Result.failure(failure) }
        }
        val normalizedResults = entries.indices.map { index ->
            rawResults.getOrNull(index)
                ?: Result.failure(IllegalStateException(AppStrings.ui_batch_create_folder_response_quantity_insufficient))
        }
        normalizedResults.forEachIndexed { index, result ->
            val entry = entries[index]
            recordDirectoryCreateResult(task, entry.toCopyRetryEntry(task.taskType), result)
        }
        return Result.success(normalizedResults)
    }

    private fun recordDirectoryCreateResult(
        task: Task,
        trackedEntry: TaskRetryEntry,
        result: Result<Boolean>,
    ): Result<Boolean> {
        if (result.isSuccess && result.getOrDefault(false)) {
            resolveSuccessfulMainQueueEntry(task, trackedEntry)
        }
        val failure = result.exceptionOrNull()
        if ((result.isFailure || !result.getOrDefault(false)) && !failure.isTaskLevelTransferFailure()) {
            if (taskState.getFailedRetryEntries(task).none { item -> item.entryKey == trackedEntry.entryKey }) {
                taskState.recordRetryFailure(
                    task = task,
                    entry = trackedEntry,
                    message = failure?.message.orEmpty(),
                    fallback = AppStrings.ui_folder_creation_failed,
                )
            }
        }
        return result
    }

    private suspend fun createEmptyFileForQueueEntry(entry: TaskRuntimeQueueEntry): Result<Boolean> {
        return when (entry.destProtocol) {
            FileProtocol.Local -> FileUtils.createFile(FileAccessPermission.Allowed, entry.destPath)

            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), entry.destProtocolId)
                    ?: return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    )
                device.files.createFiles(listOf(entry.destPath)).fold(
                    onSuccess = { results -> results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_failed_create_empty_file)) },
                    onFailure = { failure -> Result.failure(failure) }
                )
            }

            FileProtocol.Network -> {
                val networkAccess = resolveNetworkAccess(currentDesk(), entry.destProtocolId)
                    ?: return Result.failure(
                        TaskEndpointUnavailableException(AppStrings.message_task_target_network_disconnected)
                    )
                val separator = resolveProtocolPathSeparator(entry.destProtocol, entry.destProtocolId)
                val (parentPath, name) = splitParentAndName(entry.destPath, separator)
                networkAccess.createFile(parentPath, name)
            }

            FileProtocol.Share -> Result.failure(Exception(AppStrings.ui_shared_target_does_not_support_creating_empty_files))
        }
    }

    private fun buildDeleteQueueEntry(
        order: Int,
        stage: TaskRuntimeStage,
        target: FileSimpleInfo,
    ): TaskRuntimeQueueEntry {
        val retryStage = when (stage) {
            TaskRuntimeStage.COPY -> TaskRetryStage.COPY
            TaskRuntimeStage.DELETE_SOURCE -> TaskRetryStage.DELETE_SOURCE
            TaskRuntimeStage.DELETE -> TaskRetryStage.DELETE
        }
        return TaskRuntimeQueueEntry(
            entryId = buildTaskRetryEntryKey(
                stage = retryStage,
                srcPath = target.path,
                srcProtocol = target.protocol,
                srcProtocolId = target.protocolId,
                destPath = "",
                destProtocol = FileProtocol.Local,
                destProtocolId = "",
            ),
            stage = stage,
            kind = when (stage) {
                TaskRuntimeStage.DELETE_SOURCE -> TaskRuntimeEntryKind.SOURCE_DELETE
                TaskRuntimeStage.DELETE -> TaskRuntimeEntryKind.TARGET_DELETE
                TaskRuntimeStage.COPY -> TaskRuntimeEntryKind.FILE_COPY
            },
            order = order,
            src = target.toEndpointRef(),
            isDirectory = target.isDirectory,
            size = target.size,
        )
    }

    fun resolveProtocolPathSeparator(
        protocol: FileProtocol,
        protocolId: String,
    ): String {
        return when (protocol) {
            FileProtocol.Local -> PathUtils.getPathSeparator()
            FileProtocol.Device -> resolveDevice(currentDesk(), protocolId)?.pathSeparator
                ?: PathUtils.getPathSeparator()

            FileProtocol.Share -> resolveShare(protocolId)?.pathSeparator?.ifBlank { "/" } ?: "/"
            FileProtocol.Network -> (resolveNetworkAccess(currentDesk(), protocolId) as? Network)?.pathSeparator ?: "/"
        }
    }

    private fun splitParentAndName(path: String, separator: String): Pair<String, String> {
        val trimmed = path.trimEnd(separator.firstOrNull() ?: '/')
        val lastIndex = trimmed.lastIndexOf(separator)
        if (lastIndex <= 0) {
            return separator to trimmed.removePrefix(separator)
        }
        val parent = trimmed.substring(0, lastIndex).ifBlank { separator }
        val name = trimmed.substring(lastIndex + separator.length)
        return parent to name
    }



    private suspend fun retryDeleteEntry(
        task: Task,
        entry: TaskRetryEntry,
    ): Result<Boolean> {
        suspend fun ensureRunning() {
            if (!taskState.awaitIfPaused(task.key)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw CancellationException(AppStrings.message_task_cancelled)
            }
        }

        ensureRunning()
        return when (entry.srcProtocol) {
            FileProtocol.Local -> runCatching {
                if (entry.isDirectory) {
                    PathUtils.deleteDirectory(FileAccessPermission.Allowed, entry.srcPath)
                    true
                } else {
                    FileUtils.deleteFile(FileAccessPermission.Allowed, entry.srcPath)
                        .getOrElse { failure -> throw failure }
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { failure -> Result.failure(failure) }
            )

            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), entry.srcProtocolId)
                    ?: return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
                    )
                if (entry.isDirectory) {
                    device.paths.deleteDirectory(entry.srcPath)
                } else {
                    device.files.delete(listOf(entry.srcPath)).fold(
                        onSuccess = { results ->
                            results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_delete_failed))
                        },
                        onFailure = { failure -> Result.failure(failure) }
                    )
                }
            }

            FileProtocol.Network -> {
                val networkAccess = resolveNetworkAccess(currentDesk(), entry.srcProtocolId)
                    ?: return Result.failure(
                        TaskEndpointUnavailableException(AppStrings.message_task_source_network_disconnected)
                    )
                networkAccess.delete(entry.srcPath, entry.isDirectory)
            }

            else -> Result.failure(Exception(AppStrings.ui_delete_failed))
        }
    }

    private fun resolveDeskForProtocol(
        protocol: FileProtocol,
        protocolId: String,
    ): DiskBase? {
        return when (protocol) {
            FileProtocol.Local -> Local()
            FileProtocol.Device -> resolveDevice(currentDesk(), protocolId)
            FileProtocol.Share -> {
                if (protocolId == SYSTEM_SHARE_DESK_ID) Local()
                else resolveShare(protocolId)
            }

            FileProtocol.Network -> resolveNetworkAccess(currentDesk(), protocolId) as? DiskBase
        }
    }

    private fun resolveMissingEndpointError(
        protocol: FileProtocol,
        isSource: Boolean,
    ): Throwable {
        return when (protocol) {
            FileProtocol.Device -> DeviceEndpointUnavailableException(
                if (isSource) {
                    AppStrings.message_task_source_device_disconnected
                } else {
                    AppStrings.message_task_target_device_disconnected
                }
            )
            FileProtocol.Share -> ShareSessionUnavailableException(
                if (isSource) {
                    AppStrings.message_task_source_share_session_expired
                } else {
                    AppStrings.message_task_target_share_session_expired
                }
            )
            FileProtocol.Network -> TaskEndpointUnavailableException(
                if (isSource) {
                    AppStrings.message_task_source_network_disconnected
                } else {
                    AppStrings.message_task_target_network_disconnected
                }
            )
            FileProtocol.Local -> Exception(
                if (isSource) {
                    AppStrings.message_task_source_path_unavailable
                } else {
                    AppStrings.message_task_target_path_unavailable
                }
            )
        }
    }

    private fun shouldUseChunkOverallProgress(
        task: Task,
        entry: TaskRuntimeQueueEntry,
    ): Boolean {
        val meta = taskState.loadRuntimeMeta(task.key) ?: return false
        return shouldUseChunkOverallProgressForSingleFileTask(
            taskType = task.taskType,
            totalEntries = meta.totalEntries,
            stage = entry.stage,
        )
    }

    private fun resolveTransferRecoveryChunkSize(
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
    ): Int {
        val runtimePlan = HttpTransferRuntimeTuning.plan(
            maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
            maxParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
        )
        val statusChunkSize = buildList {
            addAll(
                listOf(sourceDesk, targetDesk).filterIsInstance<Device>().mapNotNull { device ->
                    device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
                },
            )
            addAll(
                listOf(sourceDesk, targetDesk).filterIsInstance<Share>().mapNotNull { share ->
                    (share.session as? DeviceBackedShareSession)?.deviceFileClient
                },
            )
        }
            .mapNotNull { client ->
                client.transferStatus()
                    .clamped()
                    .takeIf { status -> status.sampledAtMillis > 0L }
                    ?.recommendedChunkBytes
            }
            .minOrNull()
        return minOf(
            statusChunkSize ?: DEVICE_DIRECT_MAX_LENGTH,
            runtimePlan.recommendedChunkBytes,
        ).coerceIn(1, DEVICE_DIRECT_MAX_LENGTH)
    }

    private fun resolveDeviceFileClient(
        preferredDesk: DiskBase,
        deviceId: String,
    ): DeviceFileClient? {
        val device = resolveDevice(preferredDesk, deviceId) ?: return null
        return device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
    }

    private fun resolveShare(shareId: String): Share? {
        if (shareId.isBlank()) return null
        return deviceState.shares.firstOrNull { item -> item.id == shareId }
    }

    private fun resolveShareFileClient(shareId: String): DeviceFileClient? {
        val share = resolveShare(shareId) ?: return null
        return (share.session as? DeviceBackedShareSession)?.deviceFileClient
    }

    private suspend fun loadTransferTargetSize(
        protocol: FileProtocol,
        path: String,
        targetClient: DeviceFileClient?,
    ): Long? {
        if (path.isBlank()) return null
        return when (protocol) {
            FileProtocol.Local -> FileUtils.getFile(FileAccessPermission.Allowed, path)
                .getOrNull()
                ?.takeIf { file -> !file.isDirectory }
                ?.size

            FileProtocol.Device -> targetClient
                ?.getFileByPath(path)
                ?.getOrNull()
                ?.takeIf { file -> !file.isDirectory }
                ?.size

            FileProtocol.Share,
            FileProtocol.Network -> null
        }
    }

}
