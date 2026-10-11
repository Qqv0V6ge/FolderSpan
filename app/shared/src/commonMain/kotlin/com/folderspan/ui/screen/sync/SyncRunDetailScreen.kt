package com.folderspan.ui.screen.sync

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.ui.components.confirmSnackbarAction
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.main.*
import com.folderspan.utils.executeAsListAwait
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

class SyncRunDetailScreen(
    private val taskId: Long,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val syncState = koinInject<SyncState>()
        val deviceState = koinInject<DeviceState>()
        val database = koinInject<FolderSpanDatabase>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val task = syncState.getTask(taskId)
        val records = syncState.recordsFor(taskId)
        val showClearRecordsAction = task != null && records.isNotEmpty()
        val connectedDeviceNameById = deviceState.devices.associate { item -> item.id to item.name }
        val taskDeviceIds = remember(task) {
            buildSet {
                task?.let { item ->
                    if (item.sourceType == SyncEndpointType.Device && item.sourceRef.isNotBlank()) {
                        add(item.sourceRef)
                    }
                    if (item.targetType == SyncEndpointType.Device && item.targetRef.isNotBlank()) {
                        add(item.targetRef)
                    }
                }
            }
        }
        var persistedDeviceNameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(taskDeviceIds) {
            if (taskDeviceIds.isEmpty()) {
                persistedDeviceNameById = emptyMap()
                return@LaunchedEffect
            }
            val dbDevices = withContext(Dispatchers.Default) {
                database.deviceQueries.queryByIds(taskDeviceIds).executeAsListAwait()
            }
            persistedDeviceNameById = dbDevices.associate { item -> item.id to item.name }
        }
        val deviceNameById = persistedDeviceNameById + connectedDeviceNameById

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(task?.name?.ifBlank { AppStrings.ui_run_details } ?: AppStrings.ui_run_details)
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        if (task != null) {
                            IconButton(onClick = { navigator.push(SyncEditScreen(task.id)) }) {
                                Icon(Icons.Default.Edit, contentDescription = AppStrings.ui_edit)
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = if (showClearRecordsAction) {
                {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val recordCount = records.size
                            scope.launch {
                                snackbarHostState.confirmSnackbarAction(
                                    message = AppStrings.ui_arg0_execution_records_current_task_will_cleared_this_action.format(
                                        arg0 = recordCount.toString(),
                                    ),
                                    actionLabel = AppStrings.ui_clear,
                                ) {
                                    syncState.clearRunRecords(taskId)
                                    snackbarHostState.showLatestSnackbar(AppStrings.ui_execution_records_cleared)
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(AppStrings.ui_clear_record) },
                    )
                }
            } else {
                null
            },
        ) { paddingValues ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(bottom = 8.dp),
                verticalSpacing = 10.dp,
                horizontalSpacing = 10.dp,
                floatingActionButtonPadding = if (showClearRecordsAction) GridListFabPadding else 0.dp,
            ) {
                if (task == null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        ) {
                            Text(
                                text = AppStrings.ui_task_does_not_exist_has_been_deleted,
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SyncTaskSummaryCard(task, StringMapUiState(deviceNameById))
                    }

                    if (records.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                )
                            ) {
                                Text(
                                    text = AppStrings.ui_no_running_records_yet,
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    } else {
                        items(records, key = { item -> item.runId }) { record ->
                            SyncRunRecordCard(
                                task = task,
                                record = record,
                                onRetry = {
                                    syncState.retryFailedItems(task.id, record.runId)
                                    scope.launch {
                                        snackbarHostState.showLatestSnackbar(AppStrings.ui_retry_failed_item_triggered)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun SyncTaskSummaryCard(task: SyncTask, deviceNameByIdUiState: StringMapUiState) {
    val scheduleText = when (task.scheduleType) {
        SyncScheduleType.Manual -> AppStrings.ui_manual
        SyncScheduleType.Interval -> AppStrings.ui_every_arg0_minutes.format(arg0 = (task.intervalMinutes).toString())
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = task.name.ifBlank { AppStrings.ui_unnamed_sync },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = AppStrings.ui_source_arg0.format(arg0 = endpointText(task.sourceType.name, task.sourceRef, task.sourcePath, deviceNameByIdUiState.items)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AppStrings.ui_target_arg0.format(arg0 = endpointText(task.targetType.name, task.targetRef, task.targetPath, deviceNameByIdUiState.items)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AppStrings.ui_scheduling_arg0.format(arg0 = scheduleText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncRunRecordCard(
    task: SyncTask,
    record: SyncRunRecord,
    onRetry: () -> Unit,
) {
    val runTimeText = remember(record.startedAt, record.endedAt) {
        AppStrings.ui_start_arg0_end_arg1.format(arg0 = record.startedAt.timestampToYMDHM(), arg1 = record.endedAt.timestampToYMDHM())
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${record.trigger} · ${statusLabel(record.status)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = AppStrings.ui_success_arg0_arg1_failure_arg2.format(arg0 = (record.successCount).toString(), arg1 = (record.totalCount).toString(), arg2 = (record.failureCount).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = runTimeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            record.items.take(8).forEach { item ->
                Text(
                    text = "${if (item.success) "✓" else "✗"} ${item.path} ${item.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.success) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (record.failureCount > 0) {
                AssistChip(
                    onClick = onRetry,
                    label = { Text(AppStrings.ui_retry_failed_items) },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

private fun statusLabel(status: SyncRunStatus): String {
    return when (status) {
        SyncRunStatus.Idle -> AppStrings.sync_status_idle
        SyncRunStatus.Queued -> AppStrings.ui_queuing
        SyncRunStatus.Running -> AppStrings.ui_executing
        SyncRunStatus.Success -> AppStrings.ui_success
        SyncRunStatus.PartialSuccess -> AppStrings.ui_partially_successful
        SyncRunStatus.Failure -> AppStrings.ui_failed
        SyncRunStatus.Canceled -> AppStrings.ui_canceled
    }
}

private fun endpointText(
    type: String,
    ref: String,
    path: String,
    deviceNameById: Map<String, String>,
): String {
    val endpoint = when (type) {
        "Local" -> AppStrings.ui_local
        "Device" -> {
            val deviceName = deviceNameById[ref].orEmpty()
            AppStrings.ui_device_arg0.format(arg0 = deviceName.ifBlank { "?" })
        }
        "Network" -> AppStrings.ui_network_arg0.format(arg0 = ref.ifBlank { "?" })
        else -> type
    }
    return "$endpoint:$path"
}
