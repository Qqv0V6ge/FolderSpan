package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import kotlin.math.round

internal fun TaskRuntimeQueueEntry.queueDisplayPath(): String {
    return when (kind) {
        TaskRuntimeEntryKind.DIRECTORY_CREATE,
        TaskRuntimeEntryKind.EMPTY_FILE_CREATE,
        TaskRuntimeEntryKind.FILE_COPY -> dest.path.ifBlank { src.path }

        TaskRuntimeEntryKind.SOURCE_DELETE,
        TaskRuntimeEntryKind.TARGET_DELETE -> src.path.ifBlank { dest.path }
    }
}

internal fun TaskRuntimeQueueEntry.queueDisplayMessage(
    taskStatus: StatusEnum,
    stage: TaskRuntimeStage,
    category: TaskRuntimeQueueCategory,
    fileName: String,
    runState: TaskRuntimeRunState?,
): String {
    val path = queueDisplayPath()
    val isCurrent = runState?.currentStage == stage &&
        runState.currentQueueCategory == category &&
        runState.currentQueueFile == fileName &&
        runState.currentEntryPath == path
    val stateText = when {
        isCurrent && taskStatus == StatusEnum.PAUSE -> AppStrings.ui_paused
        isCurrent -> AppStrings.ui_executing
        else -> AppStrings.ui_queue
    }
    return "$stateText · ${queueActionText(category)}"
}

internal fun TaskRuntimeQueueEntry.queueActionText(category: TaskRuntimeQueueCategory): String {
    val targetType = when (category) {
        TaskRuntimeQueueCategory.DIRECTORIES -> AppStrings.ui_directory
        TaskRuntimeQueueCategory.EMPTY_FILES -> AppStrings.ui_empty_file
        TaskRuntimeQueueCategory.FILES -> AppStrings.ui_file
    }
    return when (kind) {
        TaskRuntimeEntryKind.DIRECTORY_CREATE -> AppStrings.ui_create_directory
        TaskRuntimeEntryKind.EMPTY_FILE_CREATE -> AppStrings.ui_create_empty_file
        TaskRuntimeEntryKind.FILE_COPY -> AppStrings.ui_copy_arg0.format(arg0 = targetType)
        TaskRuntimeEntryKind.SOURCE_DELETE -> AppStrings.ui_delete_source_arg0.format(arg0 = targetType)
        TaskRuntimeEntryKind.TARGET_DELETE -> AppStrings.ui_delete_arg0.format(arg0 = targetType)
    }
}

internal fun String.isMeaningfulTaskResult(): Boolean {
    val message = trim()
    return message.isNotEmpty() && !message.isTransientTaskResultMessage()
}

internal fun String.isTaskLevelRuntimeStatusMessage(): Boolean {
    return startsWith(TASK_COPY_SCAN_MESSAGE) ||
        startsWith(TASK_DELETE_SCAN_MESSAGE) ||
        startsWith(AppStrings.message_task_scanning_copy_data) ||
        startsWith(AppStrings.message_task_scanning_delete_data)
}

internal fun Double.formatItemsPerSecond(): String {
    val rounded = round(this * 10.0) / 10.0
    val longPart = rounded.toLong()
    val text = if (rounded == longPart.toDouble()) {
        "$longPart.0"
    } else {
        rounded.toString()
    }
    return AppStrings.ui_arg0_items_s.format(arg0 = text)
}

internal fun TaskRuntimePhase.toTaskRuntimePhaseValue(): String? {
    return when (this) {
        TaskRuntimePhase.SCANNING -> "scan"
        TaskRuntimePhase.EXECUTING -> "execute"
    }
}

internal fun TaskRuntimeStage.orderedQueueCategories(): List<TaskRuntimeQueueCategory> {
    return when (this) {
        TaskRuntimeStage.COPY -> listOf(
            TaskRuntimeQueueCategory.DIRECTORIES,
            TaskRuntimeQueueCategory.EMPTY_FILES,
            TaskRuntimeQueueCategory.FILES,
        )

        TaskRuntimeStage.DELETE_SOURCE,
        TaskRuntimeStage.DELETE -> listOf(
            TaskRuntimeQueueCategory.FILES,
            TaskRuntimeQueueCategory.DIRECTORIES,
        )
    }
}
