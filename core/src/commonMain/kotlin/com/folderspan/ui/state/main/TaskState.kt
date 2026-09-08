package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotApplyConflictException
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

class TaskState(
    private val failureResultStore: TaskFailureResultStore = TempTaskFailureResultStore(),
    private val runtimeStore: TaskRuntimePersistenceStore = TempTaskRuntimePersistenceStore(),
) {
    val tasks = mutableStateListOf<Task>()
    var revision by mutableIntStateOf(0)
        private set
    private val failureRevisions = mutableStateMapOf<Long, Int>()

    private val byteMetricTrackers = mutableMapOf<Long, RuntimeByteMetricTracker>()
    private val rateMetricTrackers = mutableMapOf<Long, RuntimeMetricRateTracker>()
    private val snapshotLock = TaskRuntimeStoreLock()
    private val taskScheduler = TaskSerialScheduler(
        updateStatus = { task, status -> updateStatus(task, status) },
        deleteTask = { task -> delete(task) },
        getTask = { taskKey -> getTask(taskKey) },
        onCancelRequested = { task, reason ->
            runtimeStore.clearTaskRuntime(task.key)
            if (reason.isNotEmpty()) {
                putResult(task, "cancel_reason", reason)
            }
        },
        onTaskExecutionFailure = { taskKey, latestTask, error ->
            handleTaskExecutionFailure(taskKey, latestTask, error)
        },
        onTaskFinalized = { taskKey ->
            finalizeCompletedTask(taskKey)
        },
    )

    private inline fun <T> withMutableTaskSnapshot(block: () -> T): T {
        snapshotLock.lock()
        try {
            var attempt = 0
            while (true) {
                try {
                    return Snapshot.withMutableSnapshot(block)
                } catch (error: SnapshotApplyConflictException) {
                    attempt++
                    if (attempt >= 8) throw error
                }
            }
        } finally {
            snapshotLock.unlock()
        }
    }

    private data class RuntimeByteMetricTracker(
        val totalBytes: Long,
        val startedAt: Long,
        val completedBytes: Long = 0L,
        val activeBytes: Map<String, Long> = emptyMap(),
        val activeSizes: Map<String, Long> = emptyMap(),
    ) {
        fun started(path: String, totalBytes: Long): RuntimeByteMetricTracker {
            val normalizedPath = path.ifBlank { "__active" }
            val size = totalBytes.coerceAtLeast(0L)
            return copy(
                activeBytes = activeBytes + (normalizedPath to 0L),
                activeSizes = activeSizes + (normalizedPath to size),
            )
        }

        fun progress(path: String, transferredBytes: Long): RuntimeByteMetricTracker {
            val normalizedPath = resolvePath(path)
            val total = activeSizes[normalizedPath] ?: transferredBytes
            val normalizedTransferred = transferredBytes.coerceIn(0L, total.coerceAtLeast(transferredBytes))
            return copy(activeBytes = activeBytes + (normalizedPath to normalizedTransferred))
        }

        fun progressPercent(path: String, percent: Double): RuntimeByteMetricTracker {
            val normalizedPath = resolvePath(path)
            val total = activeSizes[normalizedPath] ?: return this
            val transferred = ((total * percent.coerceIn(0.0, 100.0)) / 100.0).toLong()
            return progress(normalizedPath, transferred)
        }

        fun finished(path: String, fallbackSize: Long): RuntimeByteMetricTracker {
            val normalizedPath = resolvePath(path)
            val size = (activeSizes[normalizedPath] ?: fallbackSize).coerceAtLeast(0L)
            return copy(
                completedBytes = (completedBytes + size).coerceAtMost(totalBytes.coerceAtLeast(0L)),
                activeBytes = activeBytes - normalizedPath,
                activeSizes = activeSizes - normalizedPath,
            )
        }

        fun cleared(path: String): RuntimeByteMetricTracker {
            val normalizedPath = resolvePath(path)
            return copy(
                activeBytes = activeBytes - normalizedPath,
                activeSizes = activeSizes - normalizedPath,
            )
        }

        fun displayCompleted(): Long {
            return (completedBytes + activeBytes.values.sum()).coerceIn(0L, totalBytes.coerceAtLeast(0L))
        }

        private fun resolvePath(path: String): String {
            val normalizedPath = path.ifBlank { "__active" }
            if (normalizedPath in activeSizes) return normalizedPath
            return if (activeSizes.size == 1) activeSizes.keys.first() else normalizedPath
        }
    }

    private data class RuntimeMetricRateTracker(
        val kind: String,
        val total: Long,
        val sampler: TaskProgressRateSampler,
    )

    init {
        restorePersistedTasks()
    }

    private fun notifyChanged() {
        revision++
    }

    fun getFailureRevision(taskKey: Long): Int = failureRevisions[taskKey] ?: 0

    private fun notifyFailureChanged(taskKey: Long) {
        failureRevisions[taskKey] = getFailureRevision(taskKey) + 1
    }

    private fun findIndexByKey(key: Long): Int = tasks.indexOfFirst { item ->  item.key == key }

    private fun replaceTask(index: Int, newTask: Task): Boolean {
        val current = tasks[index]
        if (current == newTask) {
            return true
        }
        tasks[index] = newTask
        runtimeStore.saveTaskSnapshot(newTask)
        notifyChanged()
        return true
    }

    fun addOrUpdate(task: Task) {
        withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                replaceTask(index, task)
            } else {
                tasks.add(task)
                runtimeStore.saveTaskSnapshot(task)
                notifyChanged()
            }
        }
    }

    fun update(task: Task): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            index >= 0 && replaceTask(index, task)
        }
    }

    fun delete(key: Long): Boolean {
        val removed = withMutableTaskSnapshot {
            val removed = tasks.removeAll { item ->  item.key == key }
            clearSignals(key)
            if (removed) {
                clearRuntimeMetricTrackers(key)
                failureRevisions.remove(key)
                failureResultStore.deleteTask(key)
                runtimeStore.deleteTaskSnapshot(key)
                runtimeStore.clearTaskRuntime(key)
                notifyChanged()
            }
            removed
        }
        if (removed) ExternalFileResourceLeaseRegistry.releaseTask(key)
        return removed
    }

    fun delete(task: Task): Boolean {
        val removed = withMutableTaskSnapshot {
            val removed = tasks.removeAll { item ->  item.key == task.key }
            if (removed) {
                clearSignals(task.key)
                clearRuntimeMetricTrackers(task.key)
                failureRevisions.remove(task.key)
                failureResultStore.deleteTask(task.key)
                runtimeStore.deleteTaskSnapshot(task.key)
                runtimeStore.clearTaskRuntime(task.key)
                notifyChanged()
            }
            removed
        }
        if (removed) ExternalFileResourceLeaseRegistry.releaseTask(task.key)
        return removed
    }

    fun updateStatus(task: Task, status: StatusEnum): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val updated = tasks[index].withStatus(status).let { updatedTask ->
                    when (status) {
                        StatusEnum.SUCCESS,
                        StatusEnum.FAILURE -> updatedTask.clearTransientResult().clearActiveResults()

                        StatusEnum.LOADING,
                        StatusEnum.PAUSE -> updatedTask
                    }
                }
                replaceTask(index, updated)
            } else {
                false
            }
        }
    }

    fun requestPause(task: Task) {
        taskScheduler.requestPause(task)
    }

    fun requestResume(task: Task) {
        taskScheduler.requestResume(task)
    }

    fun requestCancel(task: Task, reason: String = AppStrings.ui_user_cancels_task) {
        taskScheduler.requestCancel(task, reason)
    }

    fun isTaskCancelled(taskKey: Long): Boolean = taskScheduler.isTaskCancelled(taskKey)

    fun isTaskPaused(taskKey: Long): Boolean = taskScheduler.isTaskPaused(taskKey)

    suspend fun awaitIfPaused(taskKey: Long): Boolean {
        return taskScheduler.awaitIfPaused(taskKey)
    }

    fun clearSignals(taskKey: Long) {
        taskScheduler.clearSignals(taskKey)
    }

    fun loadRuntimeMeta(taskKey: Long): TaskRuntimeMeta? = runtimeStore.loadMeta(taskKey)

    fun saveRuntimeMeta(meta: TaskRuntimeMeta) {
        runtimeStore.saveMeta(meta)
    }

    fun loadRuntimeRunState(taskKey: Long): TaskRuntimeRunState? = runtimeStore.loadRunState(taskKey)

    fun saveRuntimeRunState(runState: TaskRuntimeRunState) {
        runtimeStore.saveRunState(runState)
    }

    fun appendRuntimeQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        entries: List<TaskRuntimeQueueEntry>,
    ) {
        runtimeStore.appendQueueEntries(taskKey, stage, category, entries)
    }

    fun peekNextRuntimeQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        preferredFile: String? = null,
    ): TaskRuntimeQueuedEntry? {
        return runtimeStore.peekNextQueueEntry(taskKey, stage, category, preferredFile)
    }

    /**
     * 确认运行队列条目完成；entryId 非空时允许并发 worker 按完成项精确删除。
     */
    fun ackRuntimeQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
        entryId: String? = null,
    ) {
        runtimeStore.ackQueueEntry(taskKey, stage, category, fileName, entryId)
    }

    /**
     * 批量读取仍待处理的队列条目，用于自适应并发 worker 一次拉取一个处理窗口。
     */
    fun loadPendingRuntimeQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        limit: Int = Int.MAX_VALUE,
    ): List<TaskRuntimeQueuedEntry> {
        return runtimeStore.loadPendingQueueEntries(taskKey, stage, category, limit)
    }

    fun listRuntimeQueueFiles(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): List<String> {
        return runtimeStore.listPendingQueueFiles(taskKey, stage, category)
    }

    fun countPendingRuntimeEntries(
        taskKey: Long,
        stage: TaskRuntimeStage? = null,
        category: TaskRuntimeQueueCategory? = null,
    ): Int {
        return runtimeStore.countPendingEntries(taskKey, stage, category)
    }

    fun hasPendingRuntimeEntries(taskKey: Long): Boolean {
        return runtimeStore.hasPendingEntries(taskKey)
    }

    fun loadRuntimeTransferCheckpoint(taskKey: Long, entryId: String): TaskRuntimeTransferCheckpoint? {
        return runtimeStore.loadTransferCheckpoint(taskKey, entryId)
    }

    fun saveRuntimeTransferCheckpoint(checkpoint: TaskRuntimeTransferCheckpoint) {
        runtimeStore.saveTransferCheckpoint(checkpoint)
    }

    fun deleteRuntimeTransferCheckpoint(taskKey: Long, entryId: String) {
        runtimeStore.deleteTransferCheckpoint(taskKey, entryId)
    }

    fun clearRuntime(taskKey: Long) {
        clearRuntimeMetricTrackers(taskKey)
        runtimeStore.clearTaskRuntime(taskKey)
    }

    fun beginRuntimeByteMetrics(task: Task, totalBytes: Long): Boolean {
        return beginRuntimeByteMetrics(task, totalBytes, Clock.System.now().toEpochMilliseconds())
    }

    fun beginRuntimeByteMetrics(task: Task, totalBytes: Long, startedAt: Long): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedTotal = totalBytes.coerceAtLeast(0L)
        val normalizedStartedAt = startedAt.takeIf { item -> item > 0L } ?: now
        byteMetricTrackers[task.key] = RuntimeByteMetricTracker(
            totalBytes = normalizedTotal,
            startedAt = normalizedStartedAt,
        )
        resetRuntimeMetricRateTracker(
            taskKey = task.key,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            total = normalizedTotal,
            completed = 0L,
            sampleAt = normalizedStartedAt,
        )
        return putRuntimeMetricValues(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = 0L,
            total = normalizedTotal,
            speed = 0.0,
            etaMs = -1L,
            startedAt = normalizedStartedAt,
            updatedAt = now,
        )
    }

    fun ensureRuntimeByteMetrics(task: Task, totalBytes: Long): Boolean {
        val currentTask = getTask(task.key) ?: task
        val normalizedTotal = totalBytes.coerceAtLeast(0L)
        return when (currentTask.values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY]) {
            TASK_RUNTIME_METRIC_KIND_BYTES -> {
                val currentTotal = currentTask.values[TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY]
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                currentTotal <= 0L && normalizedTotal > 0L && !byteMetricTrackers.containsKey(task.key) && beginRuntimeByteMetrics(task, normalizedTotal)
            }

            TASK_RUNTIME_METRIC_KIND_ITEMS -> false

            else -> beginRuntimeByteMetrics(task, normalizedTotal)
        }
    }

    fun updateRuntimeByteMetrics(task: Task, totalBytes: Long): Boolean {
        if (usesRuntimeItemMetrics(task)) return false
        val currentTracker = byteMetricTrackers[task.key] ?: return beginRuntimeByteMetrics(task, totalBytes)
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedTotal = totalBytes.coerceAtLeast(0L)
        val updatedTracker = currentTracker.copy(totalBytes = normalizedTotal)
        byteMetricTrackers[task.key] = updatedTracker
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = updatedTracker.displayCompleted(),
            total = normalizedTotal,
            now = now,
            startedAtOverride = updatedTracker.startedAt,
        )
    }

    fun beginRuntimeItemMetrics(task: Task, completed: Int = 0, total: Int): Boolean {
        return beginRuntimeItemMetrics(task, completed, total, Clock.System.now().toEpochMilliseconds())
    }

    fun beginRuntimeItemMetrics(task: Task, completed: Int = 0, total: Int, startedAt: Long): Boolean {
        byteMetricTrackers.remove(task.key)
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedTotal = total.coerceAtLeast(0).toLong()
        val normalizedCompleted = completed.coerceAtLeast(0).toLong().coerceAtMost(normalizedTotal)
        val normalizedStartedAt = startedAt.takeIf { item -> item > 0L } ?: now
        resetRuntimeMetricRateTracker(
            taskKey = task.key,
            kind = TASK_RUNTIME_METRIC_KIND_ITEMS,
            total = normalizedTotal,
            completed = normalizedCompleted,
            sampleAt = normalizedStartedAt,
        )
        val etaMs = if (normalizedCompleted >= normalizedTotal) 0L else -1L
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_ITEMS,
            completed = normalizedCompleted,
            total = normalizedTotal,
            now = now,
            startedAtOverride = normalizedStartedAt,
            presetSpeed = 0.0,
            presetEtaMs = etaMs,
        )
    }

    fun beginRuntimeDirectoryItemMetrics(task: Task, completed: Int = 0, total: Int, startedAt: Long): Boolean {
        return beginRuntimeItemMetrics(task, completed, total, startedAt)
    }

    fun restoreRuntimeByteMetrics(task: Task, totalBytes: Long, startedAt: Long): Boolean {
        return beginRuntimeByteMetrics(task, totalBytes, startedAt)
    }

    fun putRuntimeItemProgress(task: Task, completed: Int, total: Int): Boolean {
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_ITEMS,
            completed = completed.coerceAtLeast(0).toLong(),
            total = total.coerceAtLeast(0).toLong(),
            now = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun putRuntimeScanMetrics(
        task: Task,
        discoveredEntries: Int,
        speedEntriesPerSecond: Double? = null,
        currentParallelism: Int? = null,
    ): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val normalizedCount = discoveredEntries.coerceAtLeast(0).toLong()
        val currentTask = getTask(task.key) ?: task
        val shouldReuseScanStart = currentTask.values[TASK_RUNTIME_PHASE_VALUE_KEY] == "scan" &&
            currentTask.values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY] == TASK_RUNTIME_METRIC_KIND_ITEMS
        val startedAt = if (shouldReuseScanStart) {
            currentTask.values[TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY]
                ?.toLongOrNull()
                ?.takeIf { item -> item > 0L }
                ?: now
        } else {
            now
        }
        val normalizedSpeed = speedEntriesPerSecond
            ?.takeIf { speed -> speed >= 0.0 && !speed.isNaN() && !speed.isInfinite() }
            ?: if (normalizedCount > 0L) {
                val elapsedMillis = (now - startedAt).coerceAtLeast(1L)
                normalizedCount * 1000.0 / elapsedMillis
            } else {
                0.0
            }
        val metricsUpdated = putRuntimeMetricValues(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_ITEMS,
            completed = normalizedCount,
            total = normalizedCount,
            speed = normalizedSpeed,
            etaMs = -1L,
            startedAt = startedAt,
            updatedAt = now,
        )
        val parallelUpdated = currentParallelism
            ?.let { count ->
                putValue(
                    task,
                    TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY,
                    count.coerceAtLeast(0).toString(),
                )
            }
            ?: false
        return metricsUpdated || parallelUpdated
    }

    fun putRuntimeParallelCount(task: Task, count: Int): Boolean {
        return putValue(
            task,
            TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY,
            count.coerceAtLeast(0).toString(),
        )
    }

    fun startRuntimeByteItem(task: Task, path: String, totalBytes: Long): Boolean {
        if (usesRuntimeItemMetrics(task)) return false
        val currentTracker = byteMetricTrackers[task.key]
            ?: RuntimeByteMetricTracker(
                totalBytes = currentRuntimeMetricTotal(task),
                startedAt = currentRuntimeMetricStartedAt(task) ?: Clock.System.now().toEpochMilliseconds(),
                completedBytes = currentRuntimeMetricCompleted(task),
            )
        val tracker = currentTracker.started(path, totalBytes)
        byteMetricTrackers[task.key] = tracker
        return putRuntimeByteProgress(task, path, 0L)
    }

    fun putRuntimeByteProgress(task: Task, path: String, transferredBytes: Long): Boolean {
        if (usesRuntimeItemMetrics(task)) return false
        val tracker = byteMetricTrackers[task.key] ?: return false
        val updated = tracker.progress(path, transferredBytes)
        byteMetricTrackers[task.key] = updated
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = updated.displayCompleted(),
            total = updated.totalBytes,
            now = Clock.System.now().toEpochMilliseconds(),
            startedAtOverride = updated.startedAt,
        )
    }

    fun finishRuntimeByteItem(task: Task, path: String, totalBytes: Long): Boolean {
        if (usesRuntimeItemMetrics(task)) return false
        val tracker = byteMetricTrackers[task.key] ?: return false
        val updated = tracker.finished(path, totalBytes)
        byteMetricTrackers[task.key] = updated
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = updated.displayCompleted(),
            total = updated.totalBytes,
            now = Clock.System.now().toEpochMilliseconds(),
            startedAtOverride = updated.startedAt,
        )
    }

    fun clearRuntimeByteItem(task: Task, path: String): Boolean {
        if (usesRuntimeItemMetrics(task)) return false
        val tracker = byteMetricTrackers[task.key] ?: return false
        val updated = tracker.cleared(path)
        byteMetricTrackers[task.key] = updated
        return putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = updated.displayCompleted(),
            total = updated.totalBytes,
            now = Clock.System.now().toEpochMilliseconds(),
            startedAtOverride = updated.startedAt,
        )
    }

    private fun updateRuntimeByteMetricFromMessage(task: Task, path: String, message: String) {
        if (usesRuntimeItemMetrics(task)) return
        val percent = TASK_PROGRESS_PERCENT_REGEX.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: return
        val tracker = byteMetricTrackers[task.key] ?: return
        val updated = tracker.progressPercent(path, percent)
        if (updated == tracker) return
        byteMetricTrackers[task.key] = updated
        putRuntimeMetricProgress(
            task = task,
            kind = TASK_RUNTIME_METRIC_KIND_BYTES,
            completed = updated.displayCompleted(),
            total = updated.totalBytes,
            now = Clock.System.now().toEpochMilliseconds(),
            startedAtOverride = updated.startedAt,
        )
    }

    private fun currentRuntimeMetricTotal(task: Task): Long {
        return (getTask(task.key) ?: task).values[TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY]
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun currentRuntimeMetricCompleted(task: Task): Long {
        return (getTask(task.key) ?: task).values[TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY]
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun currentRuntimeMetricStartedAt(task: Task): Long? {
        return (getTask(task.key) ?: task).values[TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY]
            ?.toLongOrNull()
            ?.takeIf { item -> item > 0L }
    }

    fun usesRuntimeItemMetrics(task: Task): Boolean {
        return (getTask(task.key) ?: task).values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY] == TASK_RUNTIME_METRIC_KIND_ITEMS
    }

    private fun putRuntimeMetricProgress(
        task: Task,
        kind: String,
        completed: Long,
        total: Long,
        now: Long,
        startedAtOverride: Long? = null,
        forceResetStart: Boolean = false,
        presetSpeed: Double? = null,
        presetEtaMs: Long? = null,
    ): Boolean {
        val currentTask = getTask(task.key) ?: task
        val normalizedTotal = total.coerceAtLeast(0L)
        val normalizedCompleted = completed.coerceIn(0L, normalizedTotal)
        val currentKind = currentTask.values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY]
        val currentTotal = currentTask.values[TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY]?.toLongOrNull()
        val startedAt = when {
            startedAtOverride != null -> startedAtOverride
            forceResetStart || currentKind != kind || currentTotal != normalizedTotal ->
                now

            else -> currentTask.values[TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY]
                ?.toLongOrNull()
                ?.takeIf { item -> item > 0L }
                ?: now
        }
        val rateSample = if (presetSpeed != null && presetEtaMs != null) {
            TaskProgressRateSample(presetSpeed, presetEtaMs)
        } else {
            val rateTracker = runtimeMetricRateTracker(
                task = currentTask,
                kind = kind,
                total = normalizedTotal,
                startedAt = startedAt,
                forceResetStart = forceResetStart,
            )
            rateTracker.sampler.update(
                completed = normalizedCompleted,
                total = normalizedTotal,
                now = now,
                force = normalizedTotal in 1..normalizedCompleted,
            )
        }
        return putRuntimeMetricValues(
            task = task,
            kind = kind,
            completed = normalizedCompleted,
            total = normalizedTotal,
            speed = rateSample.speedPerSecond,
            etaMs = rateSample.etaMs,
            startedAt = startedAt,
            updatedAt = now,
        )
    }

    private fun runtimeMetricRateTracker(
        task: Task,
        kind: String,
        total: Long,
        startedAt: Long,
        forceResetStart: Boolean,
    ): RuntimeMetricRateTracker {
        val current = rateMetricTrackers[task.key]
        if (!forceResetStart && current != null && current.kind == kind && current.total == total) {
            return current
        }
        val baselineCompleted = if (task.values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY] == kind) {
            task.values[TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY]
                ?.toLongOrNull()
                ?.coerceIn(0L, total)
                ?: 0L
        } else {
            0L
        }
        val baselineAt = if (forceResetStart) {
            startedAt
        } else {
            task.values[TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY]
                ?.toLongOrNull()
                ?.takeIf { item -> item > 0L }
                ?: startedAt
        }
        return resetRuntimeMetricRateTracker(
            taskKey = task.key,
            kind = kind,
            total = total,
            completed = baselineCompleted,
            sampleAt = baselineAt,
        )
    }

    private fun resetRuntimeMetricRateTracker(
        taskKey: Long,
        kind: String,
        total: Long,
        completed: Long,
        sampleAt: Long,
    ): RuntimeMetricRateTracker {
        val tracker = RuntimeMetricRateTracker(
            kind = kind,
            total = total.coerceAtLeast(0L),
            sampler = TaskProgressRateSampler(
                initialCompleted = completed.coerceAtLeast(0L),
                initialAt = sampleAt,
            ),
        )
        rateMetricTrackers[taskKey] = tracker
        return tracker
    }

    private fun clearRuntimeMetricTrackers(taskKey: Long) {
        byteMetricTrackers.remove(taskKey)
        rateMetricTrackers.remove(taskKey)
    }

    private fun putRuntimeMetricValues(
        task: Task,
        kind: String,
        completed: Long,
        total: Long,
        speed: Double,
        etaMs: Long,
        startedAt: Long,
        updatedAt: Long,
    ): Boolean {
        return putValues(
            task,
            listOf(
                TASK_RUNTIME_METRIC_KIND_VALUE_KEY to kind,
                TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY to completed.toString(),
                TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY to total.toString(),
                TASK_RUNTIME_METRIC_SPEED_VALUE_KEY to speed.toString(),
                TASK_RUNTIME_METRIC_ETA_MS_VALUE_KEY to etaMs.toString(),
                TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY to startedAt.toString(),
                TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY to updatedAt.toString(),
            )
        )
    }

    fun putRuntimeStatus(
        task: Task,
        phase: TaskRuntimePhase,
        stage: TaskRuntimeStage? = null,
        category: TaskRuntimeQueueCategory? = null,
        queueFile: String? = null,
        remainingEntries: Int? = null,
        resumedFromRecovery: Boolean? = null,
    ): Boolean {
        val phaseValue = phase.toTaskRuntimePhaseValue() ?: return false
        val currentTask = getTask(task.key) ?: task
        val nextStageValue = stage?.name
        val nextCategoryValue = category?.name
        val shouldClearParallelCount =
            currentTask.values[TASK_RUNTIME_PHASE_VALUE_KEY] != phaseValue ||
                currentTask.values[TASK_RUNTIME_STAGE_VALUE_KEY] != nextStageValue ||
                currentTask.values[TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY] != nextCategoryValue
        val keysToRemove = buildList {
            if (stage == null) add(TASK_RUNTIME_STAGE_VALUE_KEY)
            if (category == null) add(TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY)
            if (queueFile == null) add(TASK_RUNTIME_QUEUE_FILE_VALUE_KEY)
            if (remainingEntries == null) add(TASK_RUNTIME_REMAINING_VALUE_KEY)
            if (resumedFromRecovery == null) add(TASK_RUNTIME_RESUMED_VALUE_KEY)
            if (shouldClearParallelCount) add(TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY)
        }
        if (keysToRemove.isNotEmpty()) {
            removeValues(task, keysToRemove)
        }
        val values = buildList {
            add(TASK_RUNTIME_PHASE_VALUE_KEY to phaseValue)
            stage?.let { currentStage -> add(TASK_RUNTIME_STAGE_VALUE_KEY to currentStage.name) }
            category?.let { currentCategory -> add(TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY to currentCategory.name) }
            queueFile?.let { currentQueueFile -> add(TASK_RUNTIME_QUEUE_FILE_VALUE_KEY to currentQueueFile) }
            remainingEntries?.let { pendingCount -> add(TASK_RUNTIME_REMAINING_VALUE_KEY to pendingCount.toString()) }
            resumedFromRecovery?.let { resumed -> add(TASK_RUNTIME_RESUMED_VALUE_KEY to resumed.toString()) }
        }
        return putValues(task, values)
    }

    fun canContinueTask(task: Task): Boolean {
        return task.status != StatusEnum.SUCCESS && runtimeStore.hasPendingEntries(task.key)
    }

    private fun restorePersistedTasks() {
        val restored = runtimeStore.loadTaskSnapshots()
            .map(::normalizeRestoredTask)
        if (restored.isNotEmpty()) {
            tasks.addAll(restored)
            notifyChanged()
        }
        ExternalFileResourceLeaseRegistry.restoreTaskBindings(restored)
    }

    private fun normalizeRestoredTask(task: Task): Task {
        if (task.status == StatusEnum.SUCCESS) {
            runtimeStore.clearTaskRuntime(task.key)
            return task
        }
        if (task.status != StatusEnum.LOADING && task.status != StatusEnum.PAUSE) {
            return task
        }
        val meta = runtimeStore.loadMeta(task.key)
        val runState = runtimeStore.loadRunState(task.key)
        val hasPendingEntries = runtimeStore.hasPendingEntries(task.key)
        val fallbackQueue = runState ?: resolveEarliestRuntimeRunState(task, meta)
        val interruptedPath = fallbackQueue?.currentEntryPath
            ?.takeIf { item -> item.isNotBlank() }
            ?: meta?.target?.path?.takeIf { item -> item.isNotBlank() }
            ?: meta?.source?.path?.takeIf { item -> item.isNotBlank() }
            ?: task.values[TASK_PATH_VALUE_KEY].orEmpty()
        val normalized = task
            .withStatus(StatusEnum.FAILURE)
            .clearTransientResult()
            .clearActiveResults()
            .let { current ->
                var updated = current
                if (interruptedPath.isNotBlank()) {
                    updated = updated.withValue(TASK_PATH_VALUE_KEY, interruptedPath)
                }
                updated = if (hasPendingEntries) {
                    updated.withResult(interruptedPath.ifBlank { "runtime" }, AppStrings.ui_task_interrupted_after_application_exits_task_can_continued)
                } else {
                    updated
                }
                if (meta != null) {
                    updated = updated.withValues(
                        listOfNotNull(
                            meta.currentPhase.toTaskRuntimePhaseValue()?.let { TASK_RUNTIME_PHASE_VALUE_KEY to it },
                            fallbackQueue?.currentStage?.name?.let { TASK_RUNTIME_STAGE_VALUE_KEY to it },
                            fallbackQueue?.currentQueueCategory?.name?.let { TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY to it },
                            fallbackQueue?.currentQueueFile?.takeIf { item -> item.isNotBlank() }?.let {
                                TASK_RUNTIME_QUEUE_FILE_VALUE_KEY to it
                            },
                            runtimeStore.countPendingEntries(task.key).toString().let { TASK_RUNTIME_REMAINING_VALUE_KEY to it },
                            if (hasPendingEntries) TASK_RUNTIME_RESUMED_VALUE_KEY to "true" else null,
                        )
                    )
                }
                updated
            }
        runtimeStore.saveTaskSnapshot(normalized)
        return normalized
    }

    private fun resolveEarliestRuntimeRunState(
        task: Task,
        meta: TaskRuntimeMeta?,
    ): TaskRuntimeRunState? {
        val stage = orderedRuntimeStages.firstOrNull { currentStage ->
            currentStage.orderedQueueCategories().any { category ->
                runtimeStore.listPendingQueueFiles(task.key, currentStage, category).isNotEmpty()
            }
        } ?: return null
        val category = stage.orderedQueueCategories().firstOrNull { currentCategory ->
            runtimeStore.listPendingQueueFiles(task.key, stage, currentCategory).isNotEmpty()
        } ?: return null
        val fileName = runtimeStore.listPendingQueueFiles(task.key, stage, category).firstOrNull().orEmpty()
        return TaskRuntimeRunState(
            taskKey = task.key,
            runId = buildTaskRuntimeRunId(task.key),
            currentPhase = TaskRuntimePhase.EXECUTING,
            currentStage = stage,
            currentQueueCategory = category,
            currentQueueFile = fileName,
            currentEntryPath = meta?.target?.path?.takeIf { item -> item.isNotBlank() }
                ?: meta?.source?.path?.takeIf { item -> item.isNotBlank() }
                ?: task.values[TASK_PATH_VALUE_KEY].orEmpty(),
        )
    }

    /**
     * 为任务写入进度或附加信息的键值对。
     */
    fun putValue(task: Task, key: String, value: String): Boolean {
        return putValues(task, listOf(key to value))
    }

    fun putValues(task: Task, entries: Iterable<Pair<String, String>>): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            index >= 0 && replaceTask(index, tasks[index].withValues(entries))
        }
    }

    fun putOverallProgress(task: Task, current: Int, total: Int): Boolean {
        return putValues(
            task,
            listOf(
                TASK_OVERALL_PROGRESS_CUR_VALUE_KEY to current.coerceAtLeast(0).toString(),
                TASK_OVERALL_PROGRESS_MAX_VALUE_KEY to total.coerceAtLeast(0).toString(),
            )
        )
    }

    /**
     * 移除任务上的临时状态值。
     */
    fun removeValue(task: Task, key: String): Boolean {
        return removeValues(task, listOf(key))
    }

    fun removeValues(task: Task, keys: Collection<String>): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val target = tasks[index]
                !keys.none { key -> target.values.containsKey(key) } && replaceTask(index, target.withoutValues(keys))
            } else {
                false
            }
        }
    }

    /**
     * 记录任务结果信息（如错误原因）。
     */
    fun putResult(task: Task, key: String, value: String): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val normalizedValue = value.toExplicitPermissionFailureMessage()
                val target = tasks[index]
                val updated = if (normalizedValue.isTransientTaskResultMessage()) {
                    target.withTransientResult(key, normalizedValue)
                } else {
                    target
                        .withoutTransientResult(key)
                        .withoutActiveResult(key)
                        .withResult(key, normalizedValue)
                }
                val replaced = replaceTask(index, updated)
                if (normalizedValue.isTransientTaskResultMessage()) {
                    updateRuntimeByteMetricFromMessage(task, key, normalizedValue)
                }
                replaced
            } else {
                false
            }
        }
    }

    fun putTransientResult(task: Task, key: String, value: String): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            index >= 0 && replaceTask(index, tasks[index].withTransientResult(key, value))
        }
    }

    /**
     * 清除任务结果条目。
     */
    fun removeResult(task: Task, key: String): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val target = tasks[index]
                val clearedTransient = target.withoutTransientResult(key)
                val updated = if (clearedTransient.result.containsKey(key)) {
                    clearedTransient.withoutResult(key)
                } else {
                    clearedTransient
                }.withoutActiveResult(key)
                if (updated == target) {
                    clearRuntimeByteItem(task, key)
                } else {
                    val replaced = replaceTask(index, updated)
                    clearRuntimeByteItem(task, key)
                    replaced
                }
            } else {
                false
            }
        }
    }

    fun getMeaningfulResult(task: Task, preferredKey: String? = null): String? {
        val currentTask = getTask(task.key) ?: return null
        preferredKey?.let { key ->
            if (currentTask.firstFailurePath() == key) {
                currentTask.firstFailureMessage()
                    ?.takeIf { item -> item.isMeaningfulTaskResult() }
                    ?.let { return it }
            }
        }
        preferredKey?.let { key ->
            currentTask.retryEntries
                .firstOrNull { entry ->
                    entry.state == TaskRetryState.FAILURE &&
                        entry.resultPath == key &&
                        entry.displayFailureMessage().isMeaningfulTaskResult()
                }
                ?.displayFailureMessage()
                ?.let { return it }
        }
        val result = currentTask.result
        preferredKey?.let { key ->
            result[key]
                ?.let { value -> currentTask.resultMessage(key, value) }
                ?.takeIf { it.isMeaningfulTaskResult() }
                ?.let { return it }
        }
        currentTask.firstFailureMessage()
            ?.takeIf { item -> item.isMeaningfulTaskResult() }
            ?.let { return it }
        currentTask.retryEntries
            .asReversed()
            .firstOrNull { entry ->
                entry.state == TaskRetryState.FAILURE &&
                    entry.displayFailureMessage().isMeaningfulTaskResult()
            }
            ?.displayFailureMessage()
            ?.let { return it }
        return result.entries
            .toList()
            .asReversed()
            .firstNotNullOfOrNull { (key, value) ->
                currentTask.resultMessage(key, value).takeIf { it.isMeaningfulTaskResult() }
            }
    }

    fun getFailureCount(task: Task): Int {
        val currentTask = getTask(task.key) ?: task
        return getFailureCountSnapshot(currentTask)
    }

    fun getFailureCountSnapshot(task: Task): Int {
        val summaryCount = task.failureCount()
        if (summaryCount > 0) return summaryCount
        val storedFailures = failureResultStore.loadFailures(task.key).entries
        if (storedFailures.isNotEmpty()) {
            return storedFailures.count { item -> item.displayFailureMessage().isNotBlank() }
        }
        return task.failedRetryEntries().count { item -> item.displayFailureMessage().isNotBlank() }
    }

    fun getFailureSummary(task: Task): Pair<String, String>? {
        val currentTask = getTask(task.key) ?: task
        return getFailureSummarySnapshot(currentTask)
    }

    fun getFailureSummarySnapshot(task: Task): Pair<String, String>? {
        val storedFailure = failureResultStore.loadFailures(task.key).entries
            .firstOrNull { item -> item.displayFailureMessage().isNotBlank() }
        val path = task.firstFailurePath()?.takeIf { item -> item.isNotBlank() }
            ?: storedFailure?.resultPath?.takeIf { item -> item.isNotBlank() }
            ?: return null
        val message = task.firstFailureMessage()?.takeIf { item -> item.isNotBlank() }
            ?: storedFailure?.displayFailureMessage()?.takeIf { item -> item.isNotBlank() }
            ?: return null
        return normalizeTaskResultDisplay(path, message)
    }

    fun getTask(taskKey: Long): Task? = withMutableTaskSnapshot {
        tasks.firstOrNull { item -> item.key == taskKey }
    }

    /** Thread-safe immutable snapshot for non-Compose automation consumers. */
    fun snapshotTasks(): List<Task> = withMutableTaskSnapshot { tasks.toList() }

    fun replaceRetryEntries(task: Task, entries: List<TaskRetryEntry>): Boolean {
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            index >= 0 && replaceTask(index, tasks[index].withRetryEntries(entries))
        }
    }

    fun getRetryEntries(task: Task): List<TaskRetryEntry> {
        return getTask(task.key)?.retryEntries ?: task.retryEntries
    }

    fun getFailedRetryEntries(task: Task): List<TaskRetryEntry> {
        val currentTask = getTask(task.key) ?: task
        val storeEntries = failureResultStore.loadFailures(currentTask.key).entries
        if (storeEntries.isNotEmpty()) {
            return storeEntries
        }
        return getRetryEntries(currentTask).filter { item -> item.state == TaskRetryState.FAILURE }
    }

    fun hasFailedRetryEntries(task: Task): Boolean = getFailureCount(task) > 0

    fun hasInMemoryFailureMarkers(task: Task): Boolean {
        val currentTask = getTask(task.key) ?: task
        return currentTask.failureCount() > 0 || currentTask.failedRetryEntries().isNotEmpty()
    }

    fun loadFailureDisplayResults(task: Task): List<Pair<String, String>> {
        val currentTask = getTask(task.key) ?: task
        return loadFailureDisplayResultsSnapshot(currentTask)
    }

    fun loadFailureDisplayResultsSnapshot(task: Task): List<Pair<String, String>> {
        val loadedFailures = failureResultStore.loadFailures(task.key)
        val failedEntries = when {
            loadedFailures.entries.isNotEmpty() -> loadedFailures.entries
            else -> task.failedRetryEntries()
        }
        val failedResults = failedEntries.mapNotNull { entry ->
            entry.displayFailureMessage().takeIf { item -> item.isNotBlank() }?.let { message ->
                normalizeTaskResultDisplay(entry.resultPath, message)
            }
        }
        val summaryFallback = if (failedResults.isEmpty()) {
            getFailureSummarySnapshot(task)?.let { listOf(it) }.orEmpty()
        } else {
            emptyList()
        }
        val failedPaths = failedEntries.map { item -> item.resultPath }.toSet()
        return (failedResults + summaryFallback) + task.result.entries
            .filterNot { entry -> entry.key in failedPaths }
            .map { entry ->
                normalizeTaskResultDisplay(
                    entry.key,
                    task.resultMessage(entry.key, entry.value),
                )
            }
    }

    fun loadTaskQueueDisplayResults(
        task: Task,
        limit: Int = MAX_TASK_RESULT_ENTRIES,
    ): List<Pair<String, String>> {
        val currentTask = getTask(task.key) ?: task
        if (limit <= 0) return emptyList()
        val runState = runtimeStore.loadRunState(currentTask.key)
        val results = mutableListOf<Pair<String, String>>()
        orderedRuntimeStages.forEach { stage ->
            stage.orderedQueueCategories().forEach { category ->
                val remainingLimit = limit - results.size
                if (remainingLimit <= 0) return results
                val queuedEntries = runtimeStore.loadPendingQueueEntries(
                    taskKey = currentTask.key,
                    stage = stage,
                    category = category,
                    limit = remainingLimit,
                )
                queuedEntries.forEach { queued ->
                    val entry = queued.entry
                    results += normalizeTaskResultDisplay(
                        entry.queueDisplayPath(),
                        entry.queueDisplayMessage(
                            taskStatus = currentTask.status,
                            stage = stage,
                            category = category,
                            fileName = queued.fileName,
                            runState = runState,
                        ),
                    )
                }
            }
        }
        return results
    }

    fun loadActiveDisplayResultsSnapshot(task: Task): List<Pair<String, String>> {
        return task.activeResults.entries
            .filter { (_, value) -> value.isNotBlank() && !value.isTaskLevelRuntimeStatusMessage() }
            .map { entry ->
                normalizeTaskResultDisplay(
                    entry.key,
                    task.activeResultMessage(entry.key, entry.value),
                )
            }
    }

    fun loadTaskDisplayResults(task: Task): List<Pair<String, String>> {
        val currentTask = getTask(task.key) ?: task
        return loadTaskDisplayResultsSnapshot(currentTask)
    }

    fun loadTaskDisplayResultsSnapshot(task: Task): List<Pair<String, String>> {
        return when (task.status) {
            StatusEnum.LOADING,
            StatusEnum.PAUSE -> {
                loadActiveDisplayResultsSnapshot(task)
                    .takeLast(1)
                    .ifEmpty { loadTaskQueueDisplayResults(task, limit = 1) }
            }

            else -> loadFailureDisplayResultsSnapshot(task)
        }
    }

    fun recordRetryFailure(
        task: Task,
        entry: TaskRetryEntry,
        message: String,
        fallback: String,
        remainingFailures: List<TaskRetryEntry>? = null,
    ): String {
        val normalized = message.ifBlank { fallback }.toExplicitPermissionFailureMessage()
        failureResultStore.recordFailure(task.key, entry, normalized)
        removeResult(task, entry.resultPath)
        withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val target = tasks[index]
                val updated = when {
                    remainingFailures != null -> target
                        .withFailureSummary(remainingFailures)
                        .withRetryEntries(emptyList())

                    else -> target
                        .appendFailureSummary(entry.resultPath, normalized)
                        .withRetryEntries(emptyList())
                }
                replaceTask(index, updated)
                notifyFailureChanged(task.key)
            }
        }
        return normalized
    }

    fun recordRetrySuccess(task: Task, entry: TaskRetryEntry, remainingFailures: List<TaskRetryEntry>? = null) {
        failureResultStore.recordResolved(task.key, entry)
        removeResult(task, entry.resultPath)
        withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index >= 0) {
                val target = tasks[index]
                val updated = when {
                    remainingFailures != null -> target
                        .withFailureSummary(remainingFailures)
                        .withRetryEntries(emptyList())

                    else -> {
                        val nextCount = (target.failureCount() - 1).coerceAtLeast(0)
                        if (nextCount == 0) {
                            target.withFailureSummary(0, null, null).withRetryEntries(emptyList())
                        } else {
                            target.withFailureSummary(
                                failureCount = nextCount,
                                firstFailurePath = target.firstFailurePath(),
                                firstFailureMessage = target.firstFailureMessage(),
                            ).withRetryEntries(emptyList())
                        }
                    }
                }
                replaceTask(index, updated)
                notifyFailureChanged(task.key)
            }
        }
    }

    fun clearRetryFailureForRetry(task: Task, entry: TaskRetryEntry): Boolean {
        failureResultStore.recordResolved(task.key, entry)
        removeResult(task, entry.resultPath)
        return withMutableTaskSnapshot {
            val index = findIndexByKey(task.key)
            if (index < 0) return@withMutableTaskSnapshot false
            val target = tasks[index]
            val remainingFailures = getFailedRetryEntries(target)
                .filterNot { item -> item.entryKey == entry.entryKey }
            val updated = target
                .withFailureSummary(remainingFailures)
                .withRetryEntries(emptyList())
                .withoutResult(entry.resultPath)
                .withoutTransientResult(entry.resultPath)
            replaceTask(index, updated).also { changed ->
                if (changed) notifyFailureChanged(task.key)
            }
        }
    }


    /**
     * 注册任务执行逻辑，按队列串行运行。
     *
     * @param task 需要调度的任务
     * @param work 具体执行逻辑
     * @return 无返回值
     */
    fun registerTaskHandler(task: Task, work: suspend () -> Unit) {
        taskScheduler.registerTaskHandler(task, work)
    }

    private fun handleTaskExecutionFailure(
        taskKey: Long,
        latestTask: Task?,
        error: Exception,
    ) {
        LogKit.e(AppStrings.ui_task_execution_exception_taskkey_arg0.format(arg0 = (taskKey).toString()), error)
        val task = getTask(taskKey) ?: latestTask ?: return
        val preferredPath = task.values["path"]?.takeIf { item -> item.isNotBlank() }
        val fallback = when {
            error is CancellationException -> AppStrings.message_task_cancelled
            else -> when (task.taskType) {
                TaskType.Copy -> AppStrings.ui_copy_failed
                TaskType.Move -> AppStrings.ui_move_failed
                TaskType.Delete -> AppStrings.ui_delete_failed
                TaskType.Download -> AppStrings.ui_download_failed
            }
        }
        val message = resolveTaskFailureMessage(
            task = task,
            preferredPath = preferredPath,
            error = error,
            fallback = fallback,
        )
        putResult(task, preferredPath ?: "", message)
        updateStatus(task, StatusEnum.FAILURE)
        clearSignals(taskKey)
    }

    /**
     * 当前任务结束后的收尾逻辑，触发下一个排队任务。
     *
     * @return 无返回值
     */
    private fun finalizeCompletedTask(taskKey: Long) {
        val currentTask = getTask(taskKey)
        if (currentTask?.status == StatusEnum.SUCCESS) {
            clearRuntimeMetricTrackers(taskKey)
            runtimeStore.clearTaskRuntime(taskKey)
            update(currentTask.clearCompletedRuntimeState())
        }
    }
}
