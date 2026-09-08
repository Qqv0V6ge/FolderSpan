package com.folderspan.ui.screen.task

import strings.AppStrings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.toIcon
import com.folderspan.ui.navigation.pushSafe
import com.folderspan.ui.components.dialog.TaskInfoDialogContainer
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class TaskListSnapshot(
    val tasks: List<Task>,
    val tasksByKey: Map<Long, Task>,
    val loadingCount: Int,
    val pauseCount: Int,
    val failureCount: Int,
)

private data class TaskFailureCountInput(
    val taskKey: Long,
    val summaryCount: Int,
    val failureRevision: Int,
)

private data class TaskRecoverySnapshot(
    val taskByKey: Map<Long, Task> = emptyMap(),
    val canContinueByKey: Map<Long, Boolean> = emptyMap(),
)

private fun buildTaskListSnapshot(source: List<Task>): TaskListSnapshot {
    val loadingTasks = ArrayList<Task>()
    val otherTasks = ArrayList<Task>()
    val tasksByKey = HashMap<Long, Task>(source.size)
    var pauseCount = 0
    var failureCount = 0

    source.forEach { task ->
        tasksByKey[task.key] = task
        when (task.status) {
            StatusEnum.LOADING -> loadingTasks += task
            StatusEnum.PAUSE -> {
                pauseCount++
                otherTasks += task
            }

            StatusEnum.FAILURE -> {
                failureCount++
                otherTasks += task
            }

            StatusEnum.SUCCESS -> otherTasks += task
        }
    }

    return TaskListSnapshot(
        tasks = buildList(source.size) {
            addAll(loadingTasks)
            addAll(otherTasks)
        },
        tasksByKey = tasksByKey,
        loadingCount = loadingTasks.size,
        pauseCount = pauseCount,
        failureCount = failureCount,
    )
}

object TaskListScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val taskState = koinInject<TaskState>()
        val fileState = koinInject<FileState>()

        var checkedTask by remember { mutableStateOf<Task?>(null) }
        var selectedFilter by remember { mutableStateOf<StatusEnum?>(null) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedTasks by remember { mutableStateOf(setOf<Long>()) }

        val revision = taskState.revision
        val taskListSnapshot = remember(revision) {
            buildTaskListSnapshot(taskState.tasks)
        }
        val tasks = taskListSnapshot.tasks
        val failureCountInputs = tasks.map { task ->
            TaskFailureCountInput(
                taskKey = task.key,
                summaryCount = task.failureCount(),
                failureRevision = taskState.getFailureRevision(task.key),
            )
        }
        val failureCountInputByTaskKey = remember(failureCountInputs) {
            failureCountInputs.associateBy(TaskFailureCountInput::taskKey)
        }
        val loadedFailureCounts by produceState<Map<TaskFailureCountInput, Int>>(
            initialValue = emptyMap(),
            key1 = failureCountInputs,
        ) {
            val taskByKey = taskListSnapshot.tasksByKey
            val activeInputs = failureCountInputs.toSet()
            val retainedCounts = value.filterKeys { input -> input in activeInputs }
            value = retainedCounts
            val missingInputs = failureCountInputs.filterNot(retainedCounts::containsKey)
            val loadedCounts = withContext(Dispatchers.Default) {
                buildMap {
                    missingInputs.forEach { input ->
                        currentCoroutineContext().ensureActive()
                        val count = input.summaryCount.takeIf { summary -> summary > 0 } ?: run {
                            val currentTask = taskByKey[input.taskKey]
                            if (currentTask == null) {
                                input.summaryCount
                            } else {
                                taskState.getFailureCountSnapshot(currentTask)
                            }
                        }
                        put(input, count)
                    }
                }
            }
            value = retainedCounts + loadedCounts
        }
        val recoverableTasks = remember(tasks) {
            tasks.filter { task ->
                task.status == StatusEnum.FAILURE && task.taskType != TaskType.Download
            }
        }
        val taskRecoverySnapshot by produceState(
            initialValue = TaskRecoverySnapshot(),
            key1 = recoverableTasks,
        ) {
            val currentTaskByKey = recoverableTasks.associateBy(Task::key)
            val retainedCanContinue = currentTaskByKey.mapNotNull { (taskKey, task) ->
                value.canContinueByKey[taskKey]
                    ?.takeIf { value.taskByKey[taskKey] == task }
                    ?.let { canContinue -> taskKey to canContinue }
            }.toMap()
            value = TaskRecoverySnapshot(
                taskByKey = currentTaskByKey,
                canContinueByKey = retainedCanContinue,
            )
            val missingTasks = recoverableTasks.filter { task -> task.key !in retainedCanContinue }
            val loadedCanContinue = withContext(Dispatchers.Default) {
                buildMap {
                    missingTasks.forEach { task ->
                        currentCoroutineContext().ensureActive()
                        put(task.key, taskState.canContinueTask(task))
                    }
                }
            }
            value = TaskRecoverySnapshot(
                taskByKey = currentTaskByKey,
                canContinueByKey = retainedCanContinue + loadedCanContinue,
            )
        }
        val filteredTasks = remember(tasks, selectedFilter) {
            selectedFilter?.let { filter ->
                tasks.filter { item -> item.status == filter }
            } ?: tasks
        }
        val selectedTaskItems = remember(taskListSnapshot.tasksByKey, selectedTasks) {
            selectedTasks.mapNotNull(taskListSnapshot.tasksByKey::get)
        }
        val canPauseSelectedTasks = remember(selectedTaskItems) {
            selectedTaskItems.any { item ->
                item.status == StatusEnum.LOADING && item.taskType != TaskType.Download
            }
        }
        val canResumeSelectedTasks = remember(selectedTaskItems) {
            selectedTaskItems.any { item -> item.status == StatusEnum.PAUSE }
        }
        val canContinueSelectedTasks = remember(selectedTaskItems, taskRecoverySnapshot.canContinueByKey) {
            selectedTaskItems.any { task -> taskRecoverySnapshot.canContinueByKey[task.key] == true }
        }

        // 当进入选择模式但过滤后没有任务时，退出选择模式
        LaunchedEffect(filteredTasks.size, selectionMode) {
            if (selectionMode && filteredTasks.isEmpty()) {
                selectionMode = false
                selectedTasks = emptySet()
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectionMode) {
                            Text(AppStrings.ui_arg0_items_selected.format(arg0 = (selectedTasks.size).toString()))
                        } else {
                            Text(if (tasks.isEmpty()) AppStrings.ui_task_management else AppStrings.ui_task_management_arg0.format(arg0 = (tasks.size).toString()))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedTasks = emptySet()
                            } else {
                                navigator.pop()
                            }
                        }) {
                            Icon(
                                if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (!selectionMode && tasks.isNotEmpty()) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                        if (selectionMode) {
                            // 全选/取消全选
                            TextButton(onClick = {
                                selectedTasks = if (selectedTasks.size == filteredTasks.size) {
                                    emptySet()
                                } else {
                                    filteredTasks.map { item -> item.key }.toSet()
                                }
                            }) {
                                Text(
                                    if (selectedTasks.size == filteredTasks.size) {
                                        AppStrings.ui_deselect_all
                                    } else {
                                        AppStrings.ui_select_all
                                    },
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (selectionMode && selectedTasks.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        // 暂停按钮
                        if (canPauseSelectedTasks) {
                            FloatingActionButton(
                                onClick = {
                                    selectedTaskItems.forEach { task ->
                                        if (task.status == StatusEnum.LOADING && task.taskType != TaskType.Download) {
                                            taskState.requestPause(task)
                                        }
                                    }
                                },
                                containerColor = colorScheme.secondary
                            ) {
                                Icon(Icons.Default.Pause, AppStrings.ui_pause)
                            }
                        }

                        // 继续按钮
                        if (canResumeSelectedTasks) {
                            FloatingActionButton(
                                onClick = {
                                    selectedTaskItems.forEach { task ->
                                        if (task.status == StatusEnum.PAUSE) {
                                            taskState.requestResume(task)
                                        }
                                    }
                                },
                                containerColor = colorScheme.primary
                            ) {
                                Icon(Icons.Default.PlayArrow, AppStrings.ui_continue)
                            }
                        }

                        if (canContinueSelectedTasks) {
                            FloatingActionButton(
                                onClick = {
                                    selectedTaskItems.forEach { task ->
                                        if (task.status == StatusEnum.FAILURE && taskState.canContinueTask(task)) {
                                            fileState.continueTask(task)
                                        }
                                    }
                                },
                                containerColor = colorScheme.primary
                            ) {
                                Icon(Icons.Default.Refresh, AppStrings.ui_continue_task)
                            }
                        }

                        // 删除按钮
                        FloatingActionButton(
                            onClick = {
                                selectedTaskItems.forEach { task ->
                                    when (task.status) {
                                        StatusEnum.LOADING, StatusEnum.PAUSE -> taskState.requestCancel(task)
                                        else -> taskState.delete(task)
                                    }
                                }
                                selectedTasks = emptySet()
                                selectionMode = false
                            },
                            containerColor = colorScheme.error
                        ) {
                            Icon(Icons.Default.Delete, AppStrings.ui_delete)
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                // 过滤按钮
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                ) {
                    SegmentedButton(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)
                    ) {
                        Text(AppStrings.ui_all_arg0.format(arg0 = (tasks.size).toString()))
                    }
                    SegmentedButton(
                        selected = selectedFilter == StatusEnum.LOADING,
                        onClick = {
                            selectedFilter = if (selectedFilter == StatusEnum.LOADING) null else StatusEnum.LOADING
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)
                    ) {
                        Text(AppStrings.ui_executing_arg0.format(arg0 = (taskListSnapshot.loadingCount).toString()))
                    }
                    SegmentedButton(
                        selected = selectedFilter == StatusEnum.PAUSE,
                        onClick = {
                            selectedFilter = if (selectedFilter == StatusEnum.PAUSE) null else StatusEnum.PAUSE
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)
                    ) {
                        Text(AppStrings.ui_pause_arg0.format(arg0 = (taskListSnapshot.pauseCount).toString()))
                    }
                    SegmentedButton(
                        selected = selectedFilter == StatusEnum.FAILURE,
                        onClick = {
                            selectedFilter = if (selectedFilter == StatusEnum.FAILURE) null else StatusEnum.FAILURE
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)
                    ) {
                        Text(AppStrings.ui_failed_arg0.format(arg0 = (taskListSnapshot.failureCount).toString()))
                    }
                }

                GridList(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    isEmpty = filteredTasks.isEmpty(),
                ) {
                    items(filteredTasks, key = { item -> item.key }) { task ->
                        val canContinueTask = taskRecoverySnapshot.canContinueByKey[task.key] == true
                        val failureCountInput = failureCountInputByTaskKey.getValue(task.key)
                        val failedResultCount = failureCountInput.summaryCount.takeIf { count -> count > 0 }
                            ?: loadedFailureCounts[failureCountInput]
                            ?: 0
                        TaskListItem(
                            task = task,
                            canContinueTask = canContinueTask,
                            failedResultCount = failedResultCount,
                            onClick = { clickedTask ->
                                if (selectionMode) {
                                    selectedTasks = if (clickedTask.key in selectedTasks) {
                                        selectedTasks - clickedTask.key
                                    } else {
                                        selectedTasks + clickedTask.key
                                    }
                                } else {
                                    checkedTask = clickedTask
                                }
                            },
                            selected = task.key in selectedTasks,
                            selectionMode = selectionMode,
                            onPause = { item -> taskState.requestPause(item) },
                            onResume = { item -> taskState.requestResume(item) },
                            onContinue = { item -> fileState.continueTask(item) },
                            onDelete = { item ->
                                when (item.status) {
                                    StatusEnum.LOADING, StatusEnum.PAUSE -> taskState.requestCancel(item)
                                    else -> taskState.delete(item)
                                }
                            },
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
        }

        if (checkedTask != null) {
            TaskInfoDialogContainer(
                task = checkedTask!!,
                onDismiss = { checkedTask = null },
                onToResult = {
                    navigator.pushSafe(TaskResultScreen(checkedTask!!))
                    checkedTask = null
                }
            )
        }
    }
}

@Composable
private fun TaskListItem(
    task: Task,
    canContinueTask: Boolean,
    failedResultCount: Int,
    onClick: (Task) -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onPause: (Task) -> Unit,
    onResume: (Task) -> Unit,
    onContinue: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentIndex = task.currentProgressCur()
    val total = task.currentProgressMax()
    val progress = if (total > 0) (currentIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val containerColor = when {
        selected -> colorScheme.primaryContainer
        else -> colorScheme.surface
    }
    val contentColor = when {
        selected -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurface
    }
    val supportingContentColor = when {
        selected -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = if (selected) {
            BorderStroke(2.dp, colorScheme.onPrimaryContainer)
        } else {
            CardDefaults.outlinedCardBorder(enabled = true)
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Box(
            Modifier.combinedClickable(
                onClick = { onClick(task) },
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：选择框（选择模式时）或任务图标
                    if (selectionMode) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onClick(task) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colorScheme.onPrimaryContainer,
                                checkmarkColor = colorScheme.primaryContainer,
                            ),
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = when (task.taskType) {
                                TaskType.Copy -> colorScheme.primaryContainer
                                TaskType.Move -> colorScheme.tertiaryContainer
                                TaskType.Delete -> colorScheme.errorContainer.copy(alpha = 0.7f)
                                TaskType.Download -> colorScheme.secondaryContainer
                            },
                            tonalElevation = 2.dp
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                CompositionLocalProvider(
                                    LocalContentColor provides when (task.taskType) {
                                        TaskType.Copy -> colorScheme.onPrimaryContainer
                                        TaskType.Move -> colorScheme.onTertiaryContainer
                                        TaskType.Delete -> colorScheme.onErrorContainer
                                        TaskType.Download -> colorScheme.onSecondaryContainer
                                    }
                                ) {
                                    task.ToIcon()
                                }
                            }
                        }
                    }

                    // 中间：任务信息
                    Column(modifier = Modifier.weight(1f)) {
                        task.ToTitle(style = typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            task.protocol.toIcon(
                                tint = if (selected) colorScheme.onPrimaryContainer else colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = task.values["path"] ?: "",
                                style = typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = supportingContentColor
                            )
                        }
                    }

                    // 右侧：状态图标
                    task.ToStatusIcon(
                        tint = colorScheme.onPrimaryContainer.takeIf { selected }
                    )
                }

                // 操作按钮行（非选择模式时显示）
                if (!selectionMode) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 暂停/继续按钮
                        when (task.status) {
                            StatusEnum.LOADING if task.taskType != TaskType.Download -> {
                                FilledTonalButton(
                                    onClick = { onPause(task) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.secondaryContainer,
                                        contentColor = colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(AppStrings.ui_pause)
                                }
                            }

                            StatusEnum.PAUSE if task.taskType != TaskType.Download -> {
                                FilledTonalButton(
                                    onClick = { onResume(task) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.primaryContainer,
                                        contentColor = colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(AppStrings.ui_continue)
                                }
                            }

                            StatusEnum.FAILURE if canContinueTask -> {
                                FilledTonalButton(
                                    onClick = { onContinue(task) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.primaryContainer,
                                        contentColor = colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(AppStrings.ui_continue_task)
                                }
                            }

                            else -> {
                                // 占位，保持布局一致
                                Spacer(Modifier.weight(1f))
                            }
                        }

                        // 删除按钮
                        val deleteLabel = if (task.status == StatusEnum.LOADING || task.status == StatusEnum.PAUSE) {
                            AppStrings.ui_cancel_task
                        } else {
                            AppStrings.ui_delete_task
                        }
                        FilledTonalButton(
                            onClick = { onDelete(task) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colorScheme.errorContainer,
                                contentColor = colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(deleteLabel)
                        }
                    }
                }

                // 进度条
                if (task.status == StatusEnum.LOADING || task.status == StatusEnum.PAUSE) {
                    Spacer(Modifier.height(12.dp))
                    Column {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .height(8.dp),
                            color = when (task.status) {
                                StatusEnum.PAUSE -> if (selected) contentColor else colorScheme.secondary
                                else -> if (selected) contentColor else colorScheme.primary
                            },
                            trackColor = if (selected) {
                                colorScheme.onPrimaryContainer.copy(alpha = 0.24f)
                            } else {
                                colorScheme.surfaceVariant
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$currentIndex / $total",
                                style = typography.bodySmall,
                                color = supportingContentColor
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = typography.bodySmall,
                                color = contentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = AppStrings.ui_speed_arg0.format(arg0 = task.runtimeMetricSpeedText() ?: "--"),
                                style = typography.bodySmall,
                                color = supportingContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = AppStrings.ui_remaining_arg0.format(arg0 = task.runtimeMetricRemainingText() ?: "--"),
                                style = typography.bodySmall,
                                color = supportingContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = AppStrings.ui_parallel_arg0.format(arg0 = (task.activeParallelCount()).toString()),
                                style = typography.bodySmall,
                                color = supportingContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 错误信息
                if (failedResultCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = AppStrings.ui_arg0_errors.format(arg0 = (failedResultCount).toString()),
                                style = typography.bodySmall,
                                color = colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // 成功信息
                if (task.status == StatusEnum.SUCCESS && task.result.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = task.result.entries.firstOrNull()?.let { entry ->
                            task.resultMessage(entry.key, entry.value)
                        } ?: AppStrings.ui_complete,
                        style = typography.bodySmall,
                        color = if (selected) contentColor else colorScheme.primary
                    )
                }
            }
        }
    }
}
