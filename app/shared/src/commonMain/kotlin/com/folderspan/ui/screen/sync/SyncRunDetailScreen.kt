package com.folderspan.ui.screen.sync

import strings.AppStrings
import com.folderspan.data.StatusEnum

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.folderspan.extensions.fileOperationKey
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.utils.currentSyncTimeZone
import com.folderspan.ui.components.confirmSnackbarAction
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import kotlinx.coroutines.CancellationException
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
    override val routeKey: String get() = "SyncRunDetail:$taskId"
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val syncState = koinInject<SyncState>()
        val taskState = koinInject<TaskState>()
        val deviceState = koinInject<DeviceState>()
        val networkState = koinInject<NetworkState>()
        val database = koinInject<FolderSpanDatabase>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var conditionConfirmation by remember { mutableStateOf<SyncConditionConfirmation?>(null) }
        fun requestRetry(expected: SyncTask, retry: (Boolean) -> Unit) {
            val missing = syncState.unmetRunConditions(expected)
            if (missing.isEmpty()) retry(false)
            else conditionConfirmation = SyncConditionConfirmation(missing) {
                retry(sameSyncConditionSettings(expected, syncState.getTask(expected.id)))
            }
        }
        conditionConfirmation?.let { confirmation ->
            SyncConditionOverrideDialog(confirmation.conditions, onConfirm = {
                conditionConfirmation = null
                confirmation.run()
            }, onDismiss = { conditionConfirmation = null })
        }

        val task = syncState.getTask(taskId)
        val records = syncState.recordsFor(taskId)
        val ownedByCurrentDevice = task?.let(syncState::isTaskOwnedByCurrentDevice) == true
        val runtime = rememberSyncRuntimeStatistics(taskId, ownedByCurrentDevice && task.lastStatus == SyncRunStatus.Running) {
            val operations = syncState.activeOperationTasks(taskId)
            syncRuntimeStatistics(operations, operations.filter { it.status == StatusEnum.LOADING }.flatMapTo(mutableSetOf()) { taskState.transferWaitReasons(it.key) })
        }
        var failureRecord by remember { mutableStateOf<SyncRunRecord?>(null) }
        var failureItems by remember { mutableStateOf<List<SyncFailureItem>?>(null) }
        var loadingFailures by remember { mutableStateOf(false) }
        var failureLoadError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(failureRecord?.runId) {
            val record = failureRecord ?: return@LaunchedEffect
            loadingFailures = true
            failureLoadError = null
            failureItems = try {
                syncState.failedItemsFor(taskId, record.runId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failureLoadError = failure.message ?: AppStrings.ui_operation_failed
                null
            } finally { loadingFailures = false }
        }
        var detailRecord by remember { mutableStateOf<SyncRunRecord?>(null) }
        var detailKind by remember { mutableStateOf<SyncRunItemKind?>(null) }
        var showRecovery by remember { mutableStateOf(false) }
        var hasRecovery by remember { mutableStateOf(false) }
        LaunchedEffect(task, records) {
            if (!ownedByCurrentDevice) {
                detailRecord = null
                showRecovery = false
                failureRecord = null
            }
            hasRecovery = try { ownedByCurrentDevice && syncState.pendingRecoveryPage(taskId, limit = 1).isNotEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ownedByCurrentDevice }
        }
        detailRecord?.takeIf { ownedByCurrentDevice }?.let { captured ->
            key(captured.runId, detailKind) {
                SyncRunDetailsDialog(detailKind?.let(::syncResultLabel) ?: AppStrings.sync_result_details,
                    captured.participants.associate { it.id to it.label },
                    loadPage = { offset, limit -> syncState.runDetailPage(captured, offset, limit, detailKind) },
                    onDismiss = { detailRecord = null },
                    onRetryItem = if (captured.targets.isEmpty() || task == null) null else ({ item ->
                        val target = item.participant.removePrefix("target-").toIntOrNull()?.let(captured.targets::getOrNull)
                        val source = item.source
                        if (target != null && source != null) requestRetry(task) { ignore ->
                            syncState.retryStorageTargets(taskId, captured.runId, setOf(target.operationTaskKey),
                                mapOf(target.operationTaskKey to setOf(source.fileOperationKey())), ignore)
                            detailRecord = null
                        }
                    }))
            }
        }
        if (showRecovery && ownedByCurrentDevice) SyncRunDetailsDialog(AppStrings.sync_result_recovery, emptyMap(),
            loadPage = { offset, limit -> syncState.pendingRecoveryPage(taskId, offset, limit) },
            onDismiss = { showRecovery = false })
        val showClearRecordsAction = ownedByCurrentDevice && records.isNotEmpty()
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
                            IconButton(onClick = { navigator.push(SyncEditScreen(task.id, takeOwnership = !ownedByCurrentDevice)) }) {
                                Icon(Icons.Default.Edit, contentDescription = if (ownedByCurrentDevice) AppStrings.ui_edit else AppStrings.sync_take_ownership)
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
                        SyncTaskSummaryCard(task, StringMapUiState(deviceNameById), runtime, syncState.currentTaskAttention(task.id))
                    }

                    if (hasRecovery) item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedButton(onClick = { showRecovery = true }) { Text(AppStrings.sync_result_recovery) }
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
                                canControl = ownedByCurrentDevice,
                                onShowFailures = { failureItems = null; loadingFailures = true; failureRecord = record },
                                onShowDetails = { kind -> detailKind = kind; detailRecord = record },
                                targetNames = networkState.entries.associate { it.id to it.network.name },
                                onItemRetry = { key, sourceKey ->
                                    requestRetry(task) { ignore ->
                                        syncState.retryStorageTargets(task.id, record.runId, setOf(key), mapOf(key to setOf(sourceKey)), ignore)
                                    }
                                },
                                onTargetRetry = { key ->
                                    requestRetry(task) { ignore ->
                                        syncState.retryStorageTargets(task.id, record.runId, setOf(key), emptyMap(), ignore)
                                    }
                                },
                                onControl = { control -> syncState.controlStorageRun(task.id, control) },
                                onRetry = {
                                    scope.launch {
                                        val failures = syncState.failedItemsFor(task.id, record.runId)
                                        val fullRetry = failures.isNullOrEmpty() || record.unsavedFailureCount > 0
                                        requestRetry(task) { ignore ->
                                            syncState.retryFailedItems(task.id, record.runId, ignore)
                                            scope.launch { snackbarHostState.showLatestSnackbar(
                                                if (fullRetry) AppStrings.sync_retry_whole_task else AppStrings.sync_request_accepted.format(arg0 = task.name),
                                            ) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        failureRecord?.let {
            AlertDialog(
                onDismissRequest = { failureRecord = null },
                title = { Text(AppStrings.sync_show_failures) },
                text = {
                    PageStateLayout(
                        state = if (loadingFailures) PageViewState.Loading
                            else if (failureLoadError != null) PageViewState.Error(PageErrorState(PageErrorType.General, failureLoadError))
                            else if (failureItems.isNullOrEmpty()) PageViewState.Empty(AppStrings.sync_retry_whole_task)
                            else PageViewState.Content,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                        fillMaxSize = false,
                    ) {
                        LazyColumn {
                            items(failureItems.orEmpty()) { failure ->
                                Text(failure.relativePath + " · " + failure.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { failureRecord = null }) { Text(AppStrings.ui_confirm) } },
            )
        }
    }
}

@Composable
private fun SyncTaskSummaryCard(task: SyncTask, deviceNameByIdUiState: StringMapUiState, runtime: SyncRuntimeStatistics? = null,
    attention: SyncTaskAttention? = null) {
    val scheduleText = syncScheduleLabel(task)
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
            if (runtime != null) SyncRuntimeStatisticsView(task.id, runtime)
            Text(
                text = task.name.ifBlank { AppStrings.ui_unnamed_sync },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = AppStrings.ui_source_arg0.format(arg0 = endpointText(task.sourceType.name, task.sourceRef, task.sourcePath, deviceNameByIdUiState.items)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(syncModeLabel(task.mode), style = MaterialTheme.typography.labelMedium)
            val targets = attention?.fixedTargets.orEmpty().ifEmpty { task.destinations }
            if (task.mode != SyncMode.Update && task.selectRecommended && task.destinations.isNotEmpty() && attention?.fixedTargets.isNullOrEmpty()) {
                Text(AppStrings.sync_recommended_candidates_arg0.format(arg0 = task.destinations.size.toString()), style = MaterialTheme.typography.bodySmall)
            } else if (targets.isNotEmpty()) {
                Text(if (attention?.fixedTargets?.isNotEmpty() == true) AppStrings.sync_fixed_recommended_target
                    else AppStrings.sync_participating_targets_arg0.format(arg0 = targets.size.toString()), style = MaterialTheme.typography.labelSmall)
                targets.forEach { destination ->
                    Text(endpointText(destination.type.name, destination.ref, destination.path, deviceNameByIdUiState.items), style = MaterialTheme.typography.bodySmall)
                }
            } else Text(AppStrings.ui_target_arg0.format(arg0 = endpointText(task.targetType.name, task.targetRef, task.targetPath, deviceNameByIdUiState.items)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = AppStrings.ui_scheduling_arg0.format(arg0 = scheduleText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SyncRunRecordCard(
    task: SyncTask,
    record: SyncRunRecord,
    targetNames: Map<Long, String>,
    onRetry: () -> Unit,
    onTargetRetry: (Long) -> Unit,
    onItemRetry: (Long, String) -> Unit,
    onControl: (StorageGroupControl) -> Unit,
    canControl: Boolean = true,
    onShowFailures: () -> Unit = {},
    onShowDetails: (SyncRunItemKind?) -> Unit = {},
) {
    val runTimeText = remember(record.startedAt, record.endedAt) {
        AppStrings.ui_start_arg0_end_arg1.format(arg0 = record.startedAt.timestampToYMDHM(currentSyncTimeZone()), arg1 = record.endedAt.timestampToYMDHM(currentSyncTimeZone()))
    }
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (record.retryOfRunId > 0) AppStrings.sync_retry_run_arg0.format(arg0 = record.retryOfRunId.toString()) + " · " + statusLabel(record.status)
                    else "${record.trigger} · ${if (record.status == SyncRunStatus.Success && record.copiedCount + record.renamedCount + record.createdDirectoryCount == 0 && record.unchangedCount > 0) AppStrings.ui_no_changes else statusLabel(record.status)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = AppStrings.ui_success_arg0_arg1_failure_arg2.format(arg0 = (record.successCount).toString(), arg1 = (record.totalCount).toString(), arg2 = (record.failureCount).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AppStrings.sync_counts_arg0_arg1_arg2_arg3_arg4.format(
                    arg0 = record.copiedCount.toString(), arg1 = record.renamedCount.toString(),
                    arg2 = record.createdDirectoryCount.toString(), arg3 = record.unchangedCount.toString(),
                    arg4 = record.skippedCount.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = AppStrings.sync_transfer_arg0_arg1.format(
                    arg0 = record.transferredBytes.formatFileSize(),
                    arg1 = syncAverageBytesPerSecond(record).formatFileSize(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.resultCounts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.resultCounts.forEach { count ->
                        TextButton(onClick = { onShowDetails(count.kind) }, enabled = canControl) {
                            Text(syncResultLabel(count.kind) + " · " + AppStrings.sync_result_counts.format(
                                arg0 = count.successCount.toString(), arg1 = count.failureCount.toString()))
                        }
                    }
                }
            }
            if (canControl && (record.detailCount > 0 || record.items.isNotEmpty() || record.targets.any { it.items.isNotEmpty() })) {
                TextButton(onClick = { onShowDetails(null) }) { Text(AppStrings.sync_result_details) }
            }
            if (record.unsavedDetailCount > 0) Text(AppStrings.sync_result_unsaved.format(arg0 = record.unsavedDetailCount.toString()))
            if (record.unsavedFailureCount > 0) {
                Text(AppStrings.sync_unsaved_failures_arg0.format(arg0 = record.unsavedFailureCount.toString()))
            }
            Text(
                text = runTimeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(syncModeLabel(record.policySnapshot?.mode ?: SyncMode.Update), style = MaterialTheme.typography.labelMedium)
            if (record.participants.isNotEmpty()) {
                Text(AppStrings.sync_run_participants, style = MaterialTheme.typography.titleSmall)
                record.participants.forEach { participant ->
                    Text(participant.label, style = MaterialTheme.typography.labelMedium)
                    participant.paths.forEach { path -> Text(path, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (record.targets.isNotEmpty()) {
                Text(if (record.targets.any { it.uncertain }) AppStrings.ui_storage_uncertain
                    else AppStrings.ui_storage_group, style = MaterialTheme.typography.titleMedium)
                record.targets.forEach { target ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val targetName = target.destination.networkEntryId?.let(targetNames::get) ?: target.destination.ref
                        Text(targetName + " · " + target.destination.path + " · " +
                            if (target.uncertain) AppStrings.ui_storage_uncertain else statusLabel(target.status))
                        Text(AppStrings.ui_success_arg0_arg1_failure_arg2.format(
                            arg0 = target.successCount.toString(),
                            arg1 = (target.successCount + target.failureCount).toString(),
                            arg2 = target.failureCount.toString(),
                        ), style = MaterialTheme.typography.bodySmall)
                        if (target.message.isNotBlank()) Text(target.message, style = MaterialTheme.typography.bodySmall)
                        target.items.asSequence().filter { canControl && !it.success && it.source != null }.take(8).forEach { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.path + " · " + item.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { item.source?.let { onItemRetry(target.operationTaskKey, it.fileOperationKey()) } }) {
                                    Text(AppStrings.ui_retry_failed_items)
                                }
                            }
                        }

                        if (canControl && target.status in listOf(SyncRunStatus.Failure, SyncRunStatus.PartialSuccess, SyncRunStatus.Canceled)) {
                            TextButton(onClick = { onTargetRetry(target.operationTaskKey) }) { Text(AppStrings.ui_retry_failed_items) }
                        }
                    }
                }
                if (canControl && (record.status == SyncRunStatus.Running || record.status == SyncRunStatus.Queued)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onControl(StorageGroupControl.Pause) }) { Text(AppStrings.ui_pause) }
                        TextButton(onClick = { onControl(StorageGroupControl.Resume) }) { Text(AppStrings.ui_continue) }
                        TextButton(onClick = { onControl(StorageGroupControl.Cancel) }) { Text(AppStrings.ui_cancel) }
                    }
                }
                Text(AppStrings.ui_storage_lifecycle, style = MaterialTheme.typography.bodySmall)
            }
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
                TextButton(onClick = onShowFailures) { Text(AppStrings.sync_show_failures) }
            }
            if (canControl && record.failureCount > 0) {
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
        SyncRunStatus.Interrupted -> AppStrings.sync_interrupted
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

internal fun syncAverageBytesPerSecond(record: SyncRunRecord): Long =
    if (record.transferDurationMillis <= 0) 0
    else (record.transferredBytes.toDouble().coerceAtLeast(0.0) * 1000 / record.transferDurationMillis).toLong()
