package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.service.operation.TraversalScanProgress
import kotlin.time.Clock

internal val TASK_COPY_SCAN_MESSAGE: String get() = AppStrings.message_task_scanning_copy_data
internal val TASK_DELETE_SCAN_MESSAGE: String get() = AppStrings.message_task_scanning_delete_data
internal val TASK_REMOTE_PAUSE_REQUEST_MESSAGE: String get() = AppStrings.message_task_requesting_remote_pause
internal val TASK_REMOTE_PAUSE_WAITING_MESSAGE: String get() = AppStrings.message_task_remote_pause_waiting
internal val TASK_REMOTE_PAUSE_FAILURE_MESSAGE: String get() = AppStrings.ui_remote_pause_request_failed
internal val TASK_REMOTE_RESUME_REQUEST_MESSAGE: String get() = AppStrings.message_task_requesting_remote_resume
internal val TASK_REMOTE_RESUME_WAITING_MESSAGE: String get() = AppStrings.message_task_remote_resume_waiting
internal val TASK_REMOTE_RESUME_FAILURE_MESSAGE: String get() = AppStrings.ui_remote_continued_request_failed
private const val TASK_DIRECTORY_SCAN_PROGRESS_MIN_DELTA = 50
private const val TASK_DIRECTORY_SCAN_PROGRESS_MIN_INTERVAL_MS = 300L

internal fun buildCreatingFolderMessage(path: String): String {
    val normalizedPath = path.trim()
    return if (normalizedPath.isEmpty()) {
        AppStrings.ui_creating_folder
    } else {
        AppStrings.ui_creating_folder_arg0.format(arg0 = normalizedPath)
    }
}

internal fun buildCreatingEmptyFileMessage(path: String): String {
    val normalizedPath = path.trim()
    return if (normalizedPath.isEmpty()) {
        AppStrings.ui_creating_empty_file
    } else {
        AppStrings.ui_creating_empty_file_arg0.format(arg0 = normalizedPath)
    }
}

internal fun buildCopyScanMessage(
    discoveredEntries: Int = 0,
): String {
    return buildScanMessage(
        prefix = TASK_COPY_SCAN_MESSAGE,
        discoveredEntries = discoveredEntries,
    )
}

internal fun buildDeleteScanMessage(
    discoveredEntries: Int = 0,
): String {
    return buildScanMessage(
        prefix = TASK_DELETE_SCAN_MESSAGE,
        discoveredEntries = discoveredEntries,
    )
}

internal fun TaskState.putCopyScanProgress(
    task: Task,
    discoveredEntries: Int = 0,
    speedEntriesPerSecond: Double? = null,
    currentParallelism: Int? = null,
): Boolean {
    val normalizedCount = discoveredEntries.coerceAtLeast(0)
    putScanRuntimeStatusIfNeeded(task)
    putRuntimeScanMetrics(
        task = task,
        discoveredEntries = normalizedCount,
        speedEntriesPerSecond = speedEntriesPerSecond,
        currentParallelism = currentParallelism,
    )
    putValue(task, "progressMax", normalizedCount.toString())
    return putResult(
        task,
        taskProgressDisplayPath(task),
        buildCopyScanMessage(
            discoveredEntries = normalizedCount,
        )
    )
}

internal fun TaskState.putDeleteScanProgress(
    task: Task,
    discoveredEntries: Int = 0,
    speedEntriesPerSecond: Double? = null,
    currentParallelism: Int? = null,
): Boolean {
    val normalizedCount = discoveredEntries.coerceAtLeast(0)
    putScanRuntimeStatusIfNeeded(task)
    putRuntimeScanMetrics(
        task = task,
        discoveredEntries = normalizedCount,
        speedEntriesPerSecond = speedEntriesPerSecond,
        currentParallelism = currentParallelism,
    )
    putValue(task, "progressMax", normalizedCount.toString())
    return putResult(
        task,
        taskProgressDisplayPath(task),
        buildDeleteScanMessage(
            discoveredEntries = normalizedCount,
        )
    )
}

/**
 * 构建复制扫描进度发布器，将遍历组件的速度与当前并发写入任务 runtime 指标。
 */
internal fun TaskState.buildCopyScanProgressPublisher(task: Task): suspend (TraversalScanProgress) -> Unit {
    return buildScanProgressPublisher(task) { currentTask, count, speed, parallelism ->
        putCopyScanProgress(
            task = currentTask,
            discoveredEntries = count,
            speedEntriesPerSecond = speed,
            currentParallelism = parallelism,
        )
    }
}

/**
 * 构建删除扫描进度发布器；删除执行顺序不变，只同步扫描阶段的 runtime 指标。
 */
internal fun TaskState.buildDeleteScanProgressPublisher(task: Task): suspend (TraversalScanProgress) -> Unit {
    return buildScanProgressPublisher(task) { currentTask, count, speed, parallelism ->
        putDeleteScanProgress(
            task = currentTask,
            discoveredEntries = count,
            speedEntriesPerSecond = speed,
            currentParallelism = parallelism,
        )
    }
}

internal fun TaskState.putCreatingFolderProgress(task: Task, path: String): Boolean {
    if (path.isNotBlank()) {
        putValue(task, "path", path)
    }
    return putResult(task, "", buildCreatingFolderMessage(path))
}

internal fun TaskState.putCreatingEmptyFileProgress(task: Task, path: String): Boolean {
    if (path.isNotBlank()) {
        putValue(task, "path", path)
    }
    return putResult(task, "", buildCreatingEmptyFileMessage(path))
}

private fun TaskState.putScanRuntimeStatusIfNeeded(task: Task) {
    when ((getTask(task.key) ?: task).runtimePhaseText()) {
        null -> putRuntimeStatus(task = task, phase = TaskRuntimePhase.SCANNING)
    }
}

private fun TaskState.buildScanProgressPublisher(
    task: Task,
    publish: TaskState.(
        task: Task,
        discoveredEntries: Int,
        speedEntriesPerSecond: Double,
        currentParallelism: Int,
    ) -> Unit,
): suspend (TraversalScanProgress) -> Unit {
    val startedAt = Clock.System.now().toEpochMilliseconds()
    var lastPublishedCount = 0
    var lastPublishedAt = 0L
    return progress@{ progress ->
        val normalizedCount = progress.discoveredEntries.coerceAtLeast(0)
        val now = Clock.System.now().toEpochMilliseconds()
        val shouldPublish = lastPublishedAt == 0L ||
            normalizedCount - lastPublishedCount >= TASK_DIRECTORY_SCAN_PROGRESS_MIN_DELTA ||
            now - lastPublishedAt >= TASK_DIRECTORY_SCAN_PROGRESS_MIN_INTERVAL_MS
        if (!shouldPublish) return@progress
        lastPublishedCount = normalizedCount
        lastPublishedAt = now
        publish(
            task,
            normalizedCount,
            calculateScanSpeed(
                discoveredEntries = normalizedCount,
                startedAt = startedAt,
                now = now,
            ),
            progress.currentParallelism,
        )
    }
}

private fun calculateScanSpeed(
    discoveredEntries: Int,
    startedAt: Long,
    now: Long,
): Double {
    val elapsedMillis = (now - startedAt).coerceAtLeast(1L)
    return discoveredEntries.coerceAtLeast(0) * 1000.0 / elapsedMillis
}

private fun buildScanMessage(
    prefix: String,
    discoveredEntries: Int,
): String {
    val details = mutableListOf<String>()
    val normalizedCount = discoveredEntries.coerceAtLeast(0)
    if (normalizedCount > 0) {
        details += AppStrings.ui_arg0_items_found.format(arg0 = (normalizedCount).toString())
    }
    return if (details.isEmpty()) {
        prefix
    } else {
        "$prefix：${details.joinToString("，")}"
    }
}

private fun TaskState.taskProgressDisplayPath(task: Task): String {
    return (getTask(task.key) ?: task).values["path"]
        ?.takeIf { item -> item.isNotBlank() }
        .orEmpty()
}
