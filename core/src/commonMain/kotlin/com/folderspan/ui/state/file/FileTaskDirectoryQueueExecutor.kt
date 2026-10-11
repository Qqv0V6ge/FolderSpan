package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.ui.state.main.TaskRuntimeQueueEntry
import com.folderspan.ui.state.main.TaskRuntimeQueuedEntry
import com.folderspan.ui.state.main.isTaskLevelTransferFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DIRECTORY_CREATE_REQUEST_BATCH_SIZE = 16
private const val DIRECTORY_CREATE_BATCH_REQUEST_PARALLELISM = 8

internal suspend fun executeDirectoryCreateQueueAdaptiveBatch(
    queuedEntries: List<TaskRuntimeQueuedEntry>,
    operationConfig: OperationParallelismConfig,
    ensureRunning: suspend () -> Unit = {},
    dynamicMaxParallelismProvider: () -> Int,
    createDirectory: suspend (TaskRuntimeQueueEntry) -> Result<Boolean>,
    createDirectoryBatch: (suspend (List<TaskRuntimeQueueEntry>) -> Result<List<Result<Boolean>>>)? = null,
    retryableFailureFallback: String = AppStrings.ui_folder_creation_failed,
    onStarted: suspend (TaskRuntimeQueuedEntry, String) -> Unit = { _, _ -> },
    onActiveParallelismChanged: suspend (Int) -> Unit = {},
    onBatchCompleted: suspend () -> Unit = {},
    onSuccess: suspend (TaskRuntimeQueuedEntry, String) -> Unit,
    onRetryableFailure: suspend (TaskRuntimeQueuedEntry, String, String) -> Unit,
    shouldContinueAfterFailure: (Throwable?) -> Boolean = { failure ->
        !failure.isTaskLevelTransferFailure()
    },
): Result<Boolean> {
    if (queuedEntries.isEmpty()) return Result.success(true)
    val activeMutex = Mutex()
    var activeCreates = 0
    suspend fun updateActiveCreates(delta: Int) {
        val activeCount = activeMutex.withLock {
            activeCreates = (activeCreates + delta).coerceAtLeast(0)
            activeCreates
        }
        onActiveParallelismChanged(activeCount)
    }
    suspend fun handleCreateResult(
        queuedEntry: TaskRuntimeQueuedEntry,
        entryPath: String,
        result: Result<Boolean>,
    ) {
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) {
            throw failure
        }
        if (result.getOrNull() == true) {
            onSuccess(queuedEntry, entryPath)
            return
        }
        val message = failure?.message ?: retryableFailureFallback
        if (shouldContinueAfterFailure(failure)) {
            onRetryableFailure(queuedEntry, entryPath, message)
            return
        }
        throw failure ?: Exception(message)
    }
    return try {
        val layeredEntries = queuedEntries
            .sortedWith(
                compareBy<TaskRuntimeQueuedEntry> { item -> item.entry.directoryCreateDepth() }
                    .thenBy { item -> item.entry.order }
            )
            .groupBy { item -> item.entry.directoryCreateDepth() }
            .values
        layeredEntries.forEach { layerEntries ->
            if (createDirectoryBatch != null) {
                val requestParallelism = minOf(
                    DIRECTORY_CREATE_BATCH_REQUEST_PARALLELISM,
                    operationConfig.maxParallelism,
                    operationConfig.hardMaxParallelism,
                ).coerceAtLeast(1)
                val requestConfig = OperationParallelismConfig(
                    initialParallelism = minOf(operationConfig.initialParallelism, requestParallelism),
                    maxParallelism = requestParallelism,
                    queueCapacity = maxOf(4, requestParallelism * 2),
                    hardMaxParallelism = requestParallelism,
                )
                processItemsAdaptive(
                    items = layerEntries.chunked(DIRECTORY_CREATE_REQUEST_BATCH_SIZE),
                    config = requestConfig,
                    ensureRunning = ensureRunning,
                    dynamicMaxParallelismProvider = {
                        minOf(requestParallelism, dynamicMaxParallelismProvider()).coerceAtLeast(1)
                    },
                ) { requestEntries ->
                    val entryPaths = requestEntries.map { item -> item.entry.directoryCreateDisplayPath() }
                    requestEntries.zip(entryPaths).forEach { (queuedEntry, entryPath) ->
                        onStarted(queuedEntry, entryPath)
                    }
                    updateActiveCreates(1)
                    try {
                        val batchResult = createDirectoryBatch(requestEntries.map { item -> item.entry })
                        val batchFailure = batchResult.exceptionOrNull()
                        if (batchFailure is CancellationException) {
                            throw batchFailure
                        }
                        val itemResults = batchResult.getOrElse { failure ->
                            List(requestEntries.size) { Result.failure(failure) }
                        }
                        requestEntries.forEachIndexed { index, queuedEntry ->
                            val itemResult = itemResults.getOrNull(index)
                                ?: Result.failure(IllegalStateException(AppStrings.ui_batch_create_folder_response_quantity_insufficient))
                            handleCreateResult(queuedEntry, entryPaths[index], itemResult)
                        }
                        onBatchCompleted()
                    } finally {
                        updateActiveCreates(-1)
                    }
                }
                return@forEach
            }
            processItemsAdaptive(
                items = layerEntries,
                config = operationConfig,
                ensureRunning = ensureRunning,
                dynamicMaxParallelismProvider = dynamicMaxParallelismProvider,
            ) { queuedEntry ->
                val entryPath = queuedEntry.entry.directoryCreateDisplayPath()
                onStarted(queuedEntry, entryPath)
                updateActiveCreates(1)
                try {
                    val result = createDirectory(queuedEntry.entry)
                    handleCreateResult(queuedEntry, entryPath, result)
                } finally {
                    updateActiveCreates(-1)
                }
            }
        }
        onActiveParallelismChanged(0)
        Result.success(true)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        onActiveParallelismChanged(0)
        Result.failure(error as? Exception ?: Exception(error))
    }
}

private fun TaskRuntimeQueueEntry.directoryCreateDisplayPath(): String {
    return dest.path.ifBlank { src.path }
}

private fun TaskRuntimeQueueEntry.directoryCreateDepth(): Int {
    val path = directoryCreateDisplayPath().trim().trimEnd('/', '\\')
    if (path.isBlank()) return 0
    return path.split('/', '\\').count { segment -> segment.isNotBlank() }
}
