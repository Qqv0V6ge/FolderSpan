package com.folderspan.ui.state.main

import com.folderspan.service.file.calculateDeviceTransportChunkCount
import com.folderspan.service.file.calculateDeviceTransportRange
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_LENGTH
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.TimeSource
import strings.AppStrings

private const val TASK_FILE_RECOVERY_MIN_CHUNKS = 30

internal fun taskFileRecoveryMinBytes(): Long = MAX_LENGTH.toLong() * TASK_FILE_RECOVERY_MIN_CHUNKS

internal fun shouldUseTaskFileRecovery(fileSize: Long): Boolean {
    return fileSize >= taskFileRecoveryMinBytes()
}

internal fun shouldUseChunkOverallProgressForSingleFileTask(
    taskType: TaskType,
    totalEntries: Int,
    stage: TaskRuntimeStage,
): Boolean {
    return when (taskType) {
        TaskType.Copy -> totalEntries == 1
        TaskType.Move -> totalEntries == 2 && stage == TaskRuntimeStage.COPY
        TaskType.Delete -> false
        TaskType.Download -> false
    }
}

internal data class TaskFileTransferRecoveryProgress(
    val transferredBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
    val resumed: Boolean,
)

internal typealias RemainingFileDirectCopy = suspend (
    startOffset: Long,
    endOffset: Long,
    onBytesCommitted: suspend (offset: Long, bytesWritten: Int) -> Unit,
) -> Result<Boolean>

private const val CONTINUOUS_CHECKPOINT_MAX_UNPERSISTED_BYTES = 64L * 1024L * 1024L
private const val CONTINUOUS_CHECKPOINT_MAX_INTERVAL_MS = 2_000L

/**
 * 带 checkpoint 的文件分片复制。
 *
 * 默认保持串行恢复语义；传入 operationConfig 后使用自适应并发复制未完成分片，
 * checkpoint 仍只提交连续完成的 chunk，保证中断后可以从稳定边界恢复。
 */
internal suspend fun copyFileWithTransferCheckpoint(
    taskKey: Long,
    entryId: String,
    destPath: String,
    fileSize: Long,
    chunkSize: Int,
    loadCheckpoint: () -> TaskRuntimeTransferCheckpoint?,
    saveCheckpoint: (TaskRuntimeTransferCheckpoint) -> Unit,
    deleteCheckpoint: () -> Unit,
    targetSize: suspend () -> Long?,
    readChunk: suspend (startOffset: Long, endOffset: Long) -> Result<ByteArray>,
    writeChunk: suspend (chunkIndex: Int, startOffset: Long, bytes: ByteArray) -> Result<Boolean>,
    ensureRunning: suspend () -> Unit,
    onProgress: suspend (TaskFileTransferRecoveryProgress) -> Unit = {},
    operationConfig: OperationParallelismConfig? = null,
    dynamicMaxParallelismProvider: (() -> Int)? = null,
    copyChunkDirectly: (suspend (chunkIndex: Int, startOffset: Long, endOffset: Long) -> Result<Boolean>)? = null,
    copyRemainingDirectly: RemainingFileDirectCopy? = null,
    allowParallelChunks: Boolean = true,
    checkpointNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): Result<Boolean> {
    if (fileSize < 0L) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
    }
    if (chunkSize <= 0) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_split_size_invalid))
    }
    if (fileSize == 0L) {
        deleteCheckpoint()
        return Result.success(true)
    }

    val totalChunks = calculateDeviceTransportChunkCount(fileSize, chunkSize)
    val loadedCheckpoint = loadCheckpoint()
    val normalizedCheckpoint = normalizeTransferCheckpoint(
        checkpoint = loadedCheckpoint,
        taskKey = taskKey,
        entryId = entryId,
        destPath = destPath,
        fileSize = fileSize,
        chunkSize = chunkSize,
        totalChunks = totalChunks,
        targetSize = targetSize,
    )
    if (loadedCheckpoint != null && normalizedCheckpoint == null) {
        deleteCheckpoint()
    }

    var completedChunks = normalizedCheckpoint?.completedChunks ?: 0
    val now = Clock.System.now().toEpochMilliseconds()
    val initialCheckpoint = normalizedCheckpoint?.copy(updatedAt = now)
        ?: TaskRuntimeTransferCheckpoint(
            taskKey = taskKey,
            entryId = entryId,
            destPath = destPath,
            fileSize = fileSize,
            chunkSize = chunkSize,
            completedChunks = completedChunks,
            createdAt = now,
            updatedAt = now,
        )
    if (copyRemainingDirectly == null) {
        saveCheckpoint(initialCheckpoint)
    }

    val resumed = completedChunks > 0
    onProgress(
        TaskFileTransferRecoveryProgress(
            transferredBytes = completedChunksToBytes(completedChunks, fileSize, chunkSize),
            completedChunks = completedChunks,
            totalChunks = totalChunks,
            resumed = resumed,
        )
    )

    return try {
        if (copyRemainingDirectly != null) {
            copyRemainingWithTransferCheckpoint(
                fileSize = fileSize,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                completedChunks = completedChunks,
                initialCheckpoint = initialCheckpoint,
                resumed = resumed,
                saveCheckpoint = saveCheckpoint,
                ensureRunning = ensureRunning,
                onProgress = onProgress,
                copyRemainingDirectly = copyRemainingDirectly,
                checkpointNowMs = checkpointNowMs,
            )
        } else if (operationConfig == null || !allowParallelChunks) {
            while (completedChunks < totalChunks) {
                copyTransferChunkDirectly(
                    chunkIndex = completedChunks,
                    fileSize = fileSize,
                    chunkSize = chunkSize,
                    readChunk = readChunk,
                    writeChunk = writeChunk,
                    ensureRunning = ensureRunning,
                    copyChunkDirectly = copyChunkDirectly,
                )
                completedChunks++
                val committedAt = Clock.System.now().toEpochMilliseconds()
                saveCheckpoint(
                    initialCheckpoint.copy(
                        completedChunks = completedChunks,
                        updatedAt = committedAt,
                    )
                )
                onProgress(
                    TaskFileTransferRecoveryProgress(
                        transferredBytes = completedChunksToBytes(completedChunks, fileSize, chunkSize),
                        completedChunks = completedChunks,
                        totalChunks = totalChunks,
                        resumed = resumed,
                    )
                )
            }
        } else {
            val completedByIndex = BooleanArray(totalChunks)
            for (index in 0 until completedChunks) {
                completedByIndex[index] = true
            }
            var contiguousCompletedChunks = completedChunks
            val checkpointMutex = Mutex()
            val remainingChunkIndexes = (completedChunks until totalChunks).toList()
            processItemsAdaptive(
                items = remainingChunkIndexes,
                config = operationConfig,
                ensureRunning = ensureRunning,
                dynamicMaxParallelismProvider = dynamicMaxParallelismProvider,
            ) { chunkIndex ->
                copyTransferChunkDirectly(
                    chunkIndex = chunkIndex,
                    fileSize = fileSize,
                    chunkSize = chunkSize,
                    readChunk = readChunk,
                    writeChunk = writeChunk,
                    ensureRunning = ensureRunning,
                    copyChunkDirectly = copyChunkDirectly,
                )
                checkpointMutex.withLock {
                    completedByIndex[chunkIndex] = true
                    val previousCompletedChunks = contiguousCompletedChunks
                    while (contiguousCompletedChunks < totalChunks &&
                        completedByIndex[contiguousCompletedChunks]
                    ) {
                        contiguousCompletedChunks++
                    }
                    if (contiguousCompletedChunks != previousCompletedChunks) {
                        val committedAt = Clock.System.now().toEpochMilliseconds()
                        saveCheckpoint(
                            initialCheckpoint.copy(
                                completedChunks = contiguousCompletedChunks,
                                updatedAt = committedAt,
                            )
                        )
                        onProgress(
                            TaskFileTransferRecoveryProgress(
                                transferredBytes = completedChunksToBytes(
                                    contiguousCompletedChunks,
                                    fileSize,
                                    chunkSize,
                                ),
                                completedChunks = contiguousCompletedChunks,
                                totalChunks = totalChunks,
                                resumed = resumed,
                            )
                        )
                    }
                }
            }
        }
        Result.success(true)
    } catch (cancel: CancellationException) {
        Result.failure(cancel)
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

private suspend fun copyRemainingWithTransferCheckpoint(
    fileSize: Long,
    chunkSize: Int,
    totalChunks: Int,
    completedChunks: Int,
    initialCheckpoint: TaskRuntimeTransferCheckpoint,
    resumed: Boolean,
    saveCheckpoint: (TaskRuntimeTransferCheckpoint) -> Unit,
    ensureRunning: suspend () -> Unit,
    onProgress: suspend (TaskFileTransferRecoveryProgress) -> Unit,
    copyRemainingDirectly: RemainingFileDirectCopy,
    checkpointNowMs: () -> Long,
) {
    // 独立 scope 是有意的：取消传输时不能等待正在阻塞的同步 checkpoint I/O。
    val checkpointScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pendingCheckpoint = Channel<TaskRuntimeTransferCheckpoint>(Channel.CONFLATED)
    val checkpointStats = ContinuousCheckpointWriteStats()
    val checkpointWriter = checkpointScope.launch {
        for (checkpoint in pendingCheckpoint) {
            val persistStarted = TimeSource.Monotonic.markNow()
            checkpointStats.writeAttempts++
            try {
                saveCheckpoint(checkpoint)
                checkpointStats.lastPersistedChunks = checkpoint.completedChunks
                checkpointStats.lastFailure = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                checkpointStats.lastFailure = error
            } finally {
                val persistMs = persistStarted.elapsedNow().inWholeMilliseconds
                checkpointStats.totalPersistMs += persistMs
                checkpointStats.maxPersistMs = maxOf(checkpointStats.maxPersistMs, persistMs)
            }
        }
    }

    var committedBytes = completedChunksToBytes(completedChunks, fileSize, chunkSize)
    val initialCommittedBytes = committedBytes
    var committedChunks = completedChunks
    var scheduledChunks = completedChunks
    var scheduledBytes = committedBytes
    var scheduledAt = checkpointNowMs()
    val transferStarted = TimeSource.Monotonic.markNow()
    var previousCommitAt = transferStarted
    var maxCommitGapMs = 0L

    suspend fun scheduleCheckpoint(checkpoint: TaskRuntimeTransferCheckpoint) {
        pendingCheckpoint.send(checkpoint)
        yield()
    }

    suspend fun finishCheckpointWriter() {
        pendingCheckpoint.close()
        checkpointWriter.join()
        checkpointScope.cancel()
        if (checkpointStats.lastPersistedChunks != totalChunks) {
            throw checkpointStats.lastFailure
                ?: IllegalStateException(AppStrings.ui_final_checkpoint_not_persisted)
        }
    }

    try {
        scheduleCheckpoint(initialCheckpoint)
        if (committedBytes >= fileSize) {
            finishCheckpointWriter()
            return
        }

        ensureRunning()
        val copied = copyRemainingDirectly(committedBytes, fileSize) { offset, bytesWritten ->
            ensureRunning()
            require(bytesWritten > 0) { AppStrings.ui_insert_bytes_must_be_a_positive_number }
            require(offset == committedBytes) { AppStrings.ui_input_data_range_is_not_continuous }
            val nextCommittedBytes = offset + bytesWritten.toLong()
            require(nextCommittedBytes <= fileSize) { AppStrings.ui_the_data_entered_exceeds_the_file_range }
            committedBytes = nextCommittedBytes

            val commitAt = TimeSource.Monotonic.markNow()
            maxCommitGapMs = maxOf(maxCommitGapMs, previousCommitAt.elapsedNow().inWholeMilliseconds)
            previousCommitAt = commitAt

            val nextCompletedChunks = completedChunksForCommittedBytes(
                committedBytes = committedBytes,
                fileSize = fileSize,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
            )
            if (nextCompletedChunks > committedChunks) {
                committedChunks = nextCompletedChunks
            }
            val checkpointBytes = completedChunksToBytes(committedChunks, fileSize, chunkSize)
            val checkpointAt = checkpointNowMs().coerceAtLeast(scheduledAt)
            val shouldScheduleCheckpoint = committedChunks > scheduledChunks && (
                checkpointBytes - scheduledBytes >= CONTINUOUS_CHECKPOINT_MAX_UNPERSISTED_BYTES ||
                    checkpointAt - scheduledAt >= CONTINUOUS_CHECKPOINT_MAX_INTERVAL_MS ||
                    committedChunks == totalChunks
                )
            if (shouldScheduleCheckpoint) {
                scheduleCheckpoint(
                    initialCheckpoint.copy(
                        completedChunks = committedChunks,
                        updatedAt = checkpointAt,
                    )
                )
                scheduledChunks = committedChunks
                scheduledBytes = checkpointBytes
                scheduledAt = checkpointAt
            }
            onProgress(
                TaskFileTransferRecoveryProgress(
                    transferredBytes = committedBytes,
                    completedChunks = committedChunks,
                    totalChunks = totalChunks,
                    resumed = resumed,
                )
            )
        }
            .getOrElse { error -> throw error }

        if (!copied) throw IllegalStateException(AppStrings.ui_write_failed)
        if (committedBytes != fileSize || committedChunks != totalChunks) {
            throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
        }

        val transferDurationMs = transferStarted.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
        finishCheckpointWriter()
        val transferredBytes = committedBytes - initialCommittedBytes
        LogKit.i(
            "event=device_session_receive_completed taskKey=${initialCheckpoint.taskKey} " +
                "bytes=$transferredBytes durationMs=$transferDurationMs " +
                "bytesPerSecond=${transferredBytes * 1000L / transferDurationMs} " +
                "checkpointWrites=${checkpointStats.writeAttempts} " +
                "checkpointPersistMs=${checkpointStats.totalPersistMs} " +
                "checkpointMaxPersistMs=${checkpointStats.maxPersistMs} " +
                "maxCommitGapMs=$maxCommitGapMs"
        )
    } catch (cancel: CancellationException) {
        pendingCheckpoint.cancel()
        checkpointScope.cancel()
        throw cancel
    } catch (error: Throwable) {
        pendingCheckpoint.cancel()
        checkpointScope.cancel()
        throw error
    }
}

private class ContinuousCheckpointWriteStats(
    var writeAttempts: Int = 0,
    var lastPersistedChunks: Int = -1,
    var totalPersistMs: Long = 0L,
    var maxPersistMs: Long = 0L,
    var lastFailure: Throwable? = null,
)

private fun completedChunksForCommittedBytes(
    committedBytes: Long,
    fileSize: Long,
    chunkSize: Int,
    totalChunks: Int,
): Int {
    if (committedBytes >= fileSize) return totalChunks
    return (committedBytes / chunkSize.toLong()).toInt().coerceIn(0, totalChunks)
}

private suspend fun copyTransferChunkDirectly(
    chunkIndex: Int,
    fileSize: Long,
    chunkSize: Int,
    readChunk: suspend (startOffset: Long, endOffset: Long) -> Result<ByteArray>,
    writeChunk: suspend (chunkIndex: Int, startOffset: Long, bytes: ByteArray) -> Result<Boolean>,
    ensureRunning: suspend () -> Unit,
    copyChunkDirectly: (suspend (chunkIndex: Int, startOffset: Long, endOffset: Long) -> Result<Boolean>)?,
) {
    ensureRunning()
    val (startOffset, endOffset) = calculateDeviceTransportRange(
        chunkIndex = chunkIndex,
        totalBytes = fileSize,
        chunkSize = chunkSize,
    )
    if (copyChunkDirectly != null) {
        val directSuccess = copyChunkDirectly(chunkIndex, startOffset, endOffset).getOrElse { error -> throw error }
        if (!directSuccess) {
            throw IllegalStateException(AppStrings.ui_write_failed)
        }
        return
    }
    val bytes = readChunk(startOffset, endOffset).getOrElse { error -> throw error }
    val expectedBytes = (endOffset - startOffset).coerceAtLeast(0L)
    if (bytes.size.toLong() != expectedBytes) {
        throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
    }
    val writeSuccess = writeChunk(chunkIndex, startOffset, bytes).getOrElse { error -> throw error }
    if (!writeSuccess) {
        throw IllegalStateException(AppStrings.ui_write_failed)
    }
}

private suspend fun normalizeTransferCheckpoint(
    checkpoint: TaskRuntimeTransferCheckpoint?,
    taskKey: Long,
    entryId: String,
    destPath: String,
    fileSize: Long,
    chunkSize: Int,
    totalChunks: Int,
    targetSize: suspend () -> Long?,
): TaskRuntimeTransferCheckpoint? {
    if (checkpoint == null) return null
    if (checkpoint.taskKey != taskKey ||
        checkpoint.entryId != entryId ||
        checkpoint.destPath != destPath ||
        checkpoint.fileSize != fileSize ||
        checkpoint.chunkSize != chunkSize
    ) {
        return null
    }
    val completedChunks = checkpoint.completedChunks.coerceIn(0, totalChunks)
    if (completedChunks <= 0) {
        return checkpoint.copy(completedChunks = 0)
    }
    val currentTargetSize = targetSize() ?: return null
    val completedBytes = completedChunksToBytes(completedChunks, fileSize, chunkSize)
    if (currentTargetSize !in completedBytes..fileSize) {
        return null
    }
    return checkpoint.copy(completedChunks = completedChunks)
}

private fun completedChunksToBytes(
    completedChunks: Int,
    fileSize: Long,
    chunkSize: Int,
): Long {
    if (completedChunks <= 0) return 0L
    return minOf(fileSize, completedChunks.toLong() * chunkSize.toLong())
}
