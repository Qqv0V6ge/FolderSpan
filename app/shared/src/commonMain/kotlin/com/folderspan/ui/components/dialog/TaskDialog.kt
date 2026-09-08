package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.formatTaskResultDisplay
import com.folderspan.ui.state.main.normalizeTaskResultDisplay
import com.folderspan.utils.scrollToAfterFrame
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

data class TaskInfoDialogUiState(
    val task: Task,
    val canContinueTask: Boolean,
    val failureCount: Int,
    val failureSummary: Pair<String, String>?,
    val queueResults: List<Pair<String, String>>,
)

@Composable
fun TaskInfoDialogContainer(
    task: Task,
    onDismiss: () -> Unit,
    onToResult: () -> Unit
) {
    val taskState = koinInject<TaskState>()
    val fileState = koinInject<FileState>()
    val latestTask = rememberRefreshingTask(
        task = task,
        taskState = taskState,
        onTaskMissing = onDismiss,
    )
    val openedAsActiveTask = remember(task.key) { task.status.isTaskDialogActiveStatus() }
    val needsContinueState = latestTask.status == StatusEnum.FAILURE
    val canContinueTask = remember(latestTask, taskState.revision) {
        needsContinueState && taskState.canContinueTask(latestTask)
    }
    val failureCount = remember(latestTask, taskState.revision) {
        taskState.getFailureCount(latestTask)
    }

    LaunchedEffect(openedAsActiveTask, latestTask.status, failureCount) {
        if (
            openedAsActiveTask &&
            latestTask.status.isTaskDialogSuccessfulStatus() &&
            failureCount == 0
        ) {
            onDismiss()
        }
    }

    val failureSummary = remember(latestTask, taskState.revision) { taskState.getFailureSummary(latestTask) }
    val queueResults = remember(latestTask, taskState.revision) {
        when (latestTask.status) {
            StatusEnum.LOADING,
            StatusEnum.PAUSE -> taskState.loadTaskDisplayResultsSnapshot(latestTask).takeLast(1)
            else -> emptyList()
        }
    }

    TaskInfoDialog(
        uiState = TaskInfoDialogUiState(
            task = latestTask,
            canContinueTask = canContinueTask,
            failureCount = failureCount,
            failureSummary = failureSummary,
            queueResults = queueResults,
        ),
        onDismiss = onDismiss,
        onToResult = onToResult,
        onContinue = { fileState.continueTask(latestTask) },
        onDelete = {
            taskState.delete(latestTask)
            onDismiss()
        },
        onCancelTask = {
            taskState.requestCancel(latestTask)
            onDismiss()
        },
        onPauseTask = { taskState.requestPause(latestTask) },
        onResumeTask = { taskState.requestResume(latestTask) },
    )
}

@Composable
fun TaskInfoDialog(
    uiState: TaskInfoDialogUiState,
    onDismiss: () -> Unit,
    onToResult: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    onCancelTask: () -> Unit,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
) {
    val latestTask = uiState.task

    AlertDialog(
        title = {
            latestTask.ToTitle(style = MaterialTheme.typography.titleLarge)
        },
        text = {
            FileOperation(uiState)
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
        dismissButton = {
            Row {
                if (latestTask.status == StatusEnum.FAILURE && uiState.canContinueTask) {
                    TextButton(onClick = onContinue) {
                        Text(AppStrings.ui_continue_task)
                    }
                }

                if (uiState.failureCount > 0) {
                    TextButton(onToResult) {
                        Text(AppStrings.ui_view_results)
                    }
                }

                when (latestTask.status) {
                    StatusEnum.SUCCESS -> Unit
                    StatusEnum.FAILURE -> TextButton(onClick = onDelete) {
                        Text(AppStrings.ui_delete_task)
                    }

                    StatusEnum.LOADING -> {
                        TextButton(onClick = onCancelTask) {
                            Text(AppStrings.ui_cancel_task)
                        }
                        TextButton(onClick = onPauseTask) {
                            Text(AppStrings.ui_pause_task)
                        }
                    }

                    StatusEnum.PAUSE -> {
                        TextButton(onClick = onCancelTask) {
                            Text(AppStrings.ui_cancel_task)
                        }
                        TextButton(onClick = onResumeTask) {
                            Text(AppStrings.ui_continue_task)
                        }
                    }
                }
            }
        }

    )
}

@Composable
private fun rememberRefreshingTask(
    task: Task,
    taskState: TaskState,
    onTaskMissing: () -> Unit,
): Task {
    var latestTask by remember(task.key) { mutableStateOf(task) }
    val currentOnTaskMissing by rememberUpdatedState(onTaskMissing)

    LaunchedEffect(task) {
        latestTask = task
    }

    LaunchedEffect(task.key) {
        while (true) {
            val refreshedTask = taskState.tasks.firstOrNull { item -> item.key == task.key }
            if (refreshedTask == null) {
                currentOnTaskMissing()
                break
            }
            latestTask = refreshedTask
            delay(500.milliseconds)
        }
    }

    return latestTask
}

private fun StatusEnum.isTaskDialogActiveStatus(): Boolean =
    this == StatusEnum.LOADING || this == StatusEnum.PAUSE

private fun StatusEnum.isTaskDialogSuccessfulStatus(): Boolean =
    this == StatusEnum.SUCCESS

@Composable
private fun FileOperation(uiState: TaskInfoDialogUiState) {
    val task = uiState.task
    val scrollState = rememberScrollState()

    LaunchedEffect(task.key) {
        while (true) {
            delay(500.milliseconds)

            if (scrollState.value != scrollState.maxValue) {
                scrollState.scrollToAfterFrame(scrollState.maxValue)
            }
        }
    }

    val currentIndex = task.currentProgressCur()
    val total = task.currentProgressMax()
    val progress = if (total > 0) (currentIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val transientMessage = task.transientResultMessage()
    val transientPath = task.transientResultPath().orEmpty()
    val failureCount = uiState.failureCount
    val failureSummary = uiState.failureSummary
    val queueResults = uiState.queueResults
    val summaryResults = remember(task.result) {
        task.result.entries.take(1).map { entry ->
            normalizeTaskResultDisplay(
                entry.key,
                task.resultMessage(entry.key, entry.value),
            )
        }
    }
    val isActiveTask = task.status == StatusEnum.LOADING || task.status == StatusEnum.PAUSE
    val shouldShowTransientMessage = !transientMessage.isNullOrBlank() &&
        (queueResults.isEmpty() || !isActiveTask)
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .height(10.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(AppStrings.ui_arg0_total.format(arg0 = (total).toString()))
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ProgressIndicatorDefaults.linearColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(AppStrings.ui_arg0_complete.format(arg0 = (currentIndex).toString()))
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ProgressIndicatorDefaults.linearTrackColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(AppStrings.ui_arg0_remaining.format(arg0 = ((total - currentIndex).coerceAtLeast(0)).toString()))
            }
        }
        if (task.status == StatusEnum.LOADING || task.status == StatusEnum.PAUSE) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(AppStrings.ui_speed_arg0.format(arg0 = task.runtimeMetricSpeedText() ?: "--"))
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.ui_estimated_remaining_arg0.format(arg0 = task.runtimeMetricRemainingText() ?: "--"))
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.ui_parallel_arg0.format(arg0 = (task.activeParallelCount()).toString()))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (queueResults.isNotEmpty()) {
            SelectionContainer {
                Box(
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        buildString {
                            queueResults.forEachIndexed { index, item ->
                                if (index > 0) append('\n')
                                append(formatTaskResultDisplay(item.first, item.second))
                            }
                        }
                    )
                }
            }
        } else if (shouldShowTransientMessage) {
            SelectionContainer {
                Box(
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        if (transientPath.isNotEmpty()) {
                            "$transientPath: $transientMessage"
                        } else {
                            transientMessage
                        }
                    )
                }
            }
        }
        if (failureCount > 0) {
            Spacer(Modifier.height(12.dp))
            failureSummary?.let { (path, message) ->
                SelectionContainer {
                    Box(
                        Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(formatTaskResultDisplay(path, message))
                    }
                }
            }
            if (failureCount > 1) {
                Spacer(Modifier.height(8.dp))
                Text(AppStrings.ui_other_failure_details_please_click_view_results)
            }
        }
        if (failureCount == 0 && summaryResults.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Box(
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        buildString {
                            summaryResults.forEachIndexed { index, item ->
                                if (index > 0) append('\n')
                                append(formatTaskResultDisplay(item.first, item.second))
                            }
                        }
                    )
                }
            }
        }
    }
}
