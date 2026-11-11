package com.folderspan.ui.screen.sync

import com.folderspan.service.sync.SyncRunCondition
import androidx.compose.ui.platform.testTag

import strings.AppStrings
import com.folderspan.data.StatusEnum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.utils.currentSyncTimeZone
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
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

private val SyncTaskIdSetSaver = listSaver<Set<Long>, Long>(
    save = { ids -> ids.toList() },
    restore = { values -> values.toSet() }
)

object SyncManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val syncState = koinInject<SyncState>()
        val taskState = koinInject<TaskState>()
        val deviceState = koinInject<DeviceState>()
        val database = koinInject<FolderSpanDatabase>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var conditionConfirmation by remember { mutableStateOf<SyncConditionConfirmation?>(null) }
        fun requestRun(targets: List<SyncTask>, run: (Boolean) -> Unit) {
            val missing = targets.filterNot { syncState.requiresSyncPreview(it) || it.lastStatus in listOf(SyncRunStatus.Running, SyncRunStatus.Queued) }.flatMap { syncState.unmetRunConditions(it) }.toSet()
            if (missing.isEmpty()) run(false)
            else conditionConfirmation = SyncConditionConfirmation(missing) { run(true) }
        }
        conditionConfirmation?.let { confirmation ->
            SyncConditionOverrideDialog(confirmation.conditions, onConfirm = {
                conditionConfirmation = null
                confirmation.run()
            }, onDismiss = { conditionConfirmation = null })
        }
        val filterSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedTaskIds by rememberSaveable(stateSaver = SyncTaskIdSetSaver) {
            mutableStateOf(emptySet())
        }

        var filterDialogOpen by rememberSaveable { mutableStateOf(false) }
        var filterQuery by rememberSaveable { mutableStateOf("") }
        var filterQueryDraft by rememberSaveable { mutableStateOf("") }
        var filterByName by rememberSaveable { mutableStateOf(true) }
        var filterByPath by rememberSaveable { mutableStateOf(true) }
        var enabledFilter by rememberSaveable { mutableStateOf(SyncEnabledFilter.All) }
        var statusFilter by rememberSaveable { mutableStateOf(SyncStatusFilter.All) }
        var scheduleFilter by rememberSaveable { mutableStateOf(SyncScheduleFilter.All) }
        var sourceTypeFilter by rememberSaveable { mutableStateOf(SyncEndpointFilter.All) }
        var targetTypeFilter by rememberSaveable { mutableStateOf(SyncEndpointFilter.All) }
        var modeFilter by rememberSaveable { mutableStateOf<SyncMode?>(null) }
        var sortOption by rememberSaveable { mutableStateOf(SyncSortOption.UpdatedAt) }
        var sortDirection by rememberSaveable { mutableStateOf(SyncSortDirection.Desc) }

        val tasks = syncState.tasks.toList()
        val connectedDeviceNameById = deviceState.devices.associate { item -> item.id to item.name }
        var persistedDeviceNameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        val taskDeviceIds = remember(tasks) {
            buildSet {
                tasks.forEach { task ->
                    if (task.sourceType == SyncEndpointType.Device && task.sourceRef.isNotBlank()) {
                        add(task.sourceRef)
                    }
                    if (task.targetType == SyncEndpointType.Device && task.targetRef.isNotBlank()) {
                        add(task.targetRef)
                    }
                }
            }
        }
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
        val normalizedQuery = remember(filterQuery) { filterQuery.trim() }
        val filteredTasks by remember(
            tasks,
            normalizedQuery,
            filterByName,
            filterByPath,
            enabledFilter,
            statusFilter,
            scheduleFilter,
            sourceTypeFilter,
            targetTypeFilter,
            modeFilter,
        ) {
            derivedStateOf {
                tasks.filter { task ->
                    val enabledMatched = when (enabledFilter) {
                        SyncEnabledFilter.All -> true
                        SyncEnabledFilter.Enabled -> task.scheduleType != SyncScheduleType.Manual && task.enabled
                        SyncEnabledFilter.Disabled -> task.scheduleType != SyncScheduleType.Manual && !task.enabled
                    }

                    val statusMatched = statusFilter.matches(task.lastStatus)
                    val scheduleMatched = scheduleFilter.matches(task.scheduleType)
                    val sourceTypeMatched = sourceTypeFilter.matches(task.sourceType)
                    val targetTypeMatched = targetTypeFilter.matches(task.targetType)

                    val queryMatched = if (normalizedQuery.isBlank()) {
                        true
                    } else {
                        val nameMatched = filterByName &&
                                task.name.contains(normalizedQuery, ignoreCase = true)
                        val pathMatched = filterByPath && (
                                task.sourcePath.contains(normalizedQuery, ignoreCase = true) ||
                                        task.targetPath.contains(normalizedQuery, ignoreCase = true) ||
                                        task.sourceRef.contains(normalizedQuery, ignoreCase = true) ||
                                        task.targetRef.contains(normalizedQuery, ignoreCase = true)
                                )
                        nameMatched || pathMatched
                    }

                    (modeFilter == null || task.mode == modeFilter) && enabledMatched && statusMatched && scheduleMatched &&
                            sourceTypeMatched && targetTypeMatched && queryMatched
                }
            }
        }

        val displayTasks by remember(filteredTasks, sortOption, sortDirection) {
            derivedStateOf { sortSyncTasks(filteredTasks, sortOption, sortDirection) }
        }
        val visibleTaskIds by remember(displayTasks) {
            derivedStateOf { displayTasks.mapTo(mutableSetOf()) { item -> item.id } }
        }
        val selectedTasks by remember(displayTasks, selectedTaskIds) {
            derivedStateOf { displayTasks.filter { item -> item.id in selectedTaskIds } }
        }
        val showBatchActions by remember(selectionMode, selectedTasks) {
            derivedStateOf { selectionMode && selectedTasks.isNotEmpty() }
        }
        val showAddAction by remember(selectionMode) { derivedStateOf { !selectionMode } }
        val allVisibleSelected by remember(displayTasks, selectedTaskIds) {
            derivedStateOf {
                displayTasks.isNotEmpty() && selectedTaskIds.size == displayTasks.size
            }
        }
        val activeFilterCount = remember(
            normalizedQuery,
            filterByName,
            filterByPath,
            enabledFilter,
            statusFilter,
            scheduleFilter,
            sourceTypeFilter,
            targetTypeFilter,
            sortOption,
            sortDirection,
            modeFilter,
        ) {
            (if (normalizedQuery.isBlank()) 0 else 1) +
                (if (filterByName && filterByPath) 0 else 1) +
                (if (enabledFilter == SyncEnabledFilter.All) 0 else 1) +
                (if (statusFilter == SyncStatusFilter.All) 0 else 1) +
                (if (scheduleFilter == SyncScheduleFilter.All) 0 else 1) +
                (if (sourceTypeFilter == SyncEndpointFilter.All) 0 else 1) +
                (if (targetTypeFilter == SyncEndpointFilter.All) 0 else 1) +
                (if (modeFilter == null) 0 else 1) +
                (if (sortOption == SyncSortOption.UpdatedAt) 0 else 1) +
                (if (sortDirection == SyncSortDirection.Desc) 0 else 1)
        }
        val hasActiveFilter = activeFilterCount > 0

        LaunchedEffect(visibleTaskIds, selectionMode) {
            if (!selectionMode) return@LaunchedEffect

            selectedTaskIds = selectedTaskIds.intersect(visibleTaskIds)
            if (visibleTaskIds.isEmpty()) {
                selectionMode = false
                selectedTaskIds = emptySet()
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectionMode) {
                            Text(AppStrings.ui_arg0_items_selected.format(arg0 = (selectedTaskIds.size).toString()))
                        } else {
                            Text(AppStrings.ui_synchronization_management)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedTaskIds = emptySet()
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
                        if (!selectionMode) {
                            if (hasActiveFilter) {
                                FilledTonalIconButton(onClick = {
                                    filterQueryDraft = filterQuery
                                    filterDialogOpen = true
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = AppStrings.ui_search_filter_synchronization_tasks_currently_arg0_conditions.format(arg0 = (activeFilterCount).toString())
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    filterQueryDraft = filterQuery
                                    filterDialogOpen = true
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_filter_synchronization_tasks)
                                }
                            }
                        }
                        if (!selectionMode && displayTasks.isNotEmpty()) {
                            IconButton(onClick = {
                                selectionMode = true
                                selectedTaskIds = emptySet()
                            }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                        if (selectionMode && displayTasks.isNotEmpty()) {
                            TextButton(onClick = {
                                selectedTaskIds = if (allVisibleSelected) {
                                    emptySet()
                                } else {
                                    displayTasks.map { item -> item.id }.toSet()
                                }
                            }) {
                                Text(if (allVisibleSelected) AppStrings.ui_deselect_all else AppStrings.ui_select_all)
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                when {
                    showBatchActions -> {
                        val selectedScheduledTasks = selectedTasks.filter { item ->
                            item.scheduleType != SyncScheduleType.Manual
                        }
                        val enableSelected = selectedScheduledTasks.any { item -> !item.enabled }
                        SyncBatchActionsFab(
                            showAutoToggle = selectedScheduledTasks.isNotEmpty(),
                            enableSelected = enableSelected,
                            onBatchRun = {
                                val targets = selectedTasks.filter(syncState::isTaskOwnedByCurrentDevice)
                                requestRun(targets) { ignore ->
                                    val started = targets.count {
                                        syncState.runNowIfIdle(it.id, ignore && sameSyncConditionSettings(it, syncState.getTask(it.id))) != null
                                    }
                                    scope.launch {
                                        snackbarHostState.showLatestSnackbar(AppStrings.sync_batch_result_arg0_arg1.format(
                                            arg0 = started.toString(), arg1 = (selectedTasks.size - targets.size).toString(),
                                        ))
                                    }
                                }
                            },
                            onBatchToggleEnabled = {
                                if (selectedScheduledTasks.isEmpty()) return@SyncBatchActionsFab
                                val owned = selectedScheduledTasks.filter(syncState::isTaskOwnedByCurrentDevice)
                                val nextEnabled = owned.any { !it.enabled }
                                val updated = owned.count { syncState.updateEnabled(it.id, nextEnabled) }
                                scope.launch {
                                    snackbarHostState.showLatestSnackbar(AppStrings.sync_batch_result_arg0_arg1.format(
                                        arg0 = updated.toString(), arg1 = (selectedScheduledTasks.size - owned.size).toString(),
                                    ))
                                }
                            },
                            onBatchDelete = {
                                val targets = selectedTasks.toList()
                                if (targets.isEmpty()) return@SyncBatchActionsFab
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showLatestSnackbar(
                                        message = AppStrings.ui_you_sure_you_want_delete_arg0_tasks.format(arg0 = (targets.size).toString()),
                                        actionLabel = AppStrings.ui_delete,
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        targets.forEach { task ->
                                            syncState.deleteTask(task.id)
                                        }
                                        selectionMode = false
                                        selectedTaskIds = emptySet()
                                        snackbarHostState.showLatestSnackbar(AppStrings.ui_arg0_tasks_deleted.format(arg0 = (targets.size).toString()))
                                    }
                                }
                            }
                        )
                    }

                    showAddAction -> {
                        ExtendedFloatingActionButton(
                            onClick = { navigator.push(SyncEditScreen()) },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text(AppStrings.ui_add_new_task) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                isEmpty = displayTasks.isEmpty(),
                emptyMessage = if (tasks.isEmpty()) {
                    AppStrings.ui_no_sync_tasks_yet
                } else {
                    AppStrings.ui_no_matching_sync_task
                },
                floatingActionButtonPadding = if (showBatchActions || showAddAction) {
                    GridListFabPadding
                } else {
                    0.dp
                }
            ) {
                item(key = "sync-schedule-scope", span = { GridItemSpan(maxLineSpan) }) {
                    Text(syncSchedulePlatformHint(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                items(displayTasks, key = { item -> item.id }) { task ->
                    val owned = syncState.isTaskOwnedByCurrentDevice(task)
                    val runtime = rememberSyncRuntimeStatistics(task.id, owned && task.lastStatus == SyncRunStatus.Running) {
                        val operations = syncState.activeOperationTasks(task.id)
                        syncRuntimeStatistics(operations, operations.filter { it.status == StatusEnum.LOADING }.flatMapTo(mutableSetOf()) { taskState.transferWaitReasons(it.key) })
                    }
                    SyncTaskCard(
                        task = task,
                        ownedByCurrentDevice = owned,
                        runtime = runtime,
                        deviceNameByIdUiState = StringMapUiState(deviceNameById),
                        selectionMode = selectionMode,
                        isSelected = task.id in selectedTaskIds,
                        onSelectionToggle = {
                            selectedTaskIds = if (task.id in selectedTaskIds) {
                                selectedTaskIds - task.id
                            } else {
                                selectedTaskIds + task.id
                            }
                        },
                        onClick = { navigator.push(SyncRunDetailScreen(task.id)) },
                        onViewDetail = { navigator.push(SyncRunDetailScreen(task.id)) },
                        onRun = {
                            if (syncState.requiresSyncPreview(task)) {
                                navigator.push(SyncRunPreviewScreen(task.id))
                            } else {
                                requestRun(listOf(task)) { ignore ->
                                    val accepted = syncState.runNowIfIdle(task.id,
                                        ignore && sameSyncConditionSettings(task, syncState.getTask(task.id))) != null
                                    scope.launch {
                                        snackbarHostState.showLatestSnackbar(
                                            if (accepted) AppStrings.sync_request_accepted.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync })
                                            else AppStrings.sync_already_active,
                                        )
                                    }
                                }
                            }
                        },
                        onToggle = {
                            val nextEnabled = !task.enabled
                            syncState.updateEnabled(task.id, nextEnabled)
                            scope.launch {
                                snackbarHostState.showLatestSnackbar(
                                    if (nextEnabled) AppStrings.ui_scheduled_execution_enabled else AppStrings.ui_scheduled_execution_closed
                                )
                            }
                        },
                        onEdit = { navigator.push(SyncEditScreen(task.id, takeOwnership = !syncState.isTaskOwnedByCurrentDevice(task))) },
                        onDuplicate = {
                            syncState.duplicateTask(task.id)
                            scope.launch {
                                snackbarHostState.showLatestSnackbar(AppStrings.ui_task_copied)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val snackbarResult = snackbarHostState.showLatestSnackbar(
                                    message = AppStrings.ui_confirm_deletion_task_arg0.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync }),
                                    actionLabel = AppStrings.ui_delete,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Short,
                                )
                                if (snackbarResult == SnackbarResult.ActionPerformed) {
                                    syncState.deleteTask(task.id)
                                    snackbarHostState.showLatestSnackbar(
                                        AppStrings.ui_deleted_task_arg0.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync })
                                    )
                                }
                            }
                        },
                        attention = syncState.currentTaskAttention(task.id),
                        waitingConditions = syncState.waitingRunConditions[task.id],
                        onCancelWaiting = { syncState.cancelActiveRun(task.id) },
                        onPreview = { navigator.push(SyncRunPreviewScreen(task.id)) },
                        onPendingDeletions = { navigator.push(SyncRunPreviewScreen(task.id, pendingDeletionsOnly = true)) },
                    )
                }
            }
        }

        if (filterDialogOpen) {
            SyncFilterSheet(
                sheetState = filterSheetState,
                activeFilterCount = activeFilterCount,
                filterQuery = filterQueryDraft,
                onFilterQueryChange = { filterQueryDraft = it },
                filterByName = filterByName,
                onFilterByNameChange = { value ->
                    if (value || filterByPath) {
                        filterByName = value
                    }
                },
                filterByPath = filterByPath,
                onFilterByPathChange = { value ->
                    if (value || filterByName) {
                        filterByPath = value
                    }
                },
                enabledFilter = enabledFilter,
                onEnabledFilterChange = { enabledFilter = it },
                statusFilter = statusFilter,
                onStatusFilterChange = { statusFilter = it },
                scheduleFilter = scheduleFilter,
                onScheduleFilterChange = { scheduleFilter = it },
                sourceTypeFilter = sourceTypeFilter,
                onSourceTypeFilterChange = { sourceTypeFilter = it },
                targetTypeFilter = targetTypeFilter,
                onTargetTypeFilterChange = { targetTypeFilter = it },
                modeFilter = modeFilter,
                onModeFilterChange = { modeFilter = it },
                sortOption = sortOption,
                onSortOptionChange = { sortOption = it },
                sortDirection = sortDirection,
                onSortDirectionChange = { sortDirection = it },
                onApply = {
                    filterQuery = filterQueryDraft.trim()
                    filterDialogOpen = false
                },
                onDismissRequest = { filterDialogOpen = false },
                onReset = {
                    filterQuery = ""
                    filterQueryDraft = ""
                    filterByName = true
                    filterByPath = true
                    enabledFilter = SyncEnabledFilter.All
                    statusFilter = SyncStatusFilter.All
                    scheduleFilter = SyncScheduleFilter.All
                    sourceTypeFilter = SyncEndpointFilter.All
                    targetTypeFilter = SyncEndpointFilter.All
                    modeFilter = null
                    sortOption = SyncSortOption.UpdatedAt
                    sortDirection = SyncSortDirection.Desc
                }
            )
        }
    }
}

@Composable
internal fun SyncTaskCard(
    task: SyncTask,
    ownedByCurrentDevice: Boolean,
    deviceNameByIdUiState: StringMapUiState,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSelectionToggle: () -> Unit,
    onClick: () -> Unit,
    onViewDetail: () -> Unit,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    attention: SyncTaskAttention? = null,
    waitingConditions: Set<SyncRunCondition>? = null,
    onCancelWaiting: () -> Unit = {},
    onPreview: () -> Unit = {},
    onPendingDeletions: () -> Unit = {},
    runtime: SyncRuntimeStatistics? = null,
) {
    val statusText = if (task.lastStatus == SyncRunStatus.Success && task.lastMessage == AppStrings.ui_no_changes) AppStrings.ui_no_changes else statusLabel(task.lastStatus)
    val showStatus = task.lastStatus != SyncRunStatus.Idle
    val scheduleText = syncScheduleLabel(task)
    val autoStateText = when (task.scheduleType) {
        SyncScheduleType.Manual -> null
        SyncScheduleType.Interval, SyncScheduleType.Daily, SyncScheduleType.Weekly -> if (task.enabled) AppStrings.ui_timer else AppStrings.ui_scheduled_off
    }
    val sourceLabel = endpointText(task.sourceType.name, task.sourceRef, task.sourcePath, deviceNameByIdUiState.items)
    val fixedTargets = if (ownedByCurrentDevice) attention?.fixedTargets.orEmpty() else emptyList()
    val groupTargets = fixedTargets.ifEmpty { task.destinations }
    val recommendedPending = task.mode != SyncMode.Update && task.selectRecommended && task.destinations.isNotEmpty() && fixedTargets.isEmpty()
    val targetLabel = when {
        recommendedPending -> AppStrings.sync_recommended_candidates_arg0.format(arg0 = task.destinations.size.toString())
        groupTargets.isNotEmpty() -> groupTargets.joinToString(" · ") { endpointText(it.type.name, it.ref, it.path, deviceNameByIdUiState.items) }
        else -> endpointText(task.targetType.name, task.targetRef, task.targetPath, deviceNameByIdUiState.items)
    }
    var menuExpanded by remember(task.id) { mutableStateOf(false) }

    val statusTint = when (task.lastStatus) {
        SyncRunStatus.Success -> MaterialTheme.colorScheme.primary
        SyncRunStatus.Failure, SyncRunStatus.Interrupted -> MaterialTheme.colorScheme.error
        SyncRunStatus.PartialSuccess -> MaterialTheme.colorScheme.tertiary
        SyncRunStatus.Running,
        SyncRunStatus.Queued,
            -> MaterialTheme.colorScheme.tertiary

        SyncRunStatus.Canceled,
        SyncRunStatus.Idle,
            -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val autoStateTint = if (task.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onSelectionToggle else onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            supportingColor = if (isSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        leadingContent = if (selectionMode) {
            {
                Icon(
                    imageVector = if (isSelected) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_task,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onSelectionToggle)
                )
            }
        } else {
            null
        },
        headlineContent = {
            Text(
                text = task.name.ifBlank { AppStrings.ui_unnamed_sync },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        overlineContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!ownedByCurrentDevice) {
                    SyncMetaLabel(
                        text = AppStrings.sync_owner_arg0.format(arg0 = task.ownerDeviceName.ifBlank { task.ownerDeviceId }),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showStatus) {
                    SyncMetaLabel(text = statusText, color = statusTint)
                }
                SyncMetaLabel(
                    text = scheduleText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (autoStateText != null) {
                    SyncMetaLabel(text = autoStateText, color = autoStateTint)
                }
            }
        },
        supportingContent = {
            Column {
                Text(syncModeLabel(task.mode), style = MaterialTheme.typography.labelMedium)
                if (ownedByCurrentDevice && runtime != null) SyncRuntimeStatisticsView(task.id, runtime)
                if (ownedByCurrentDevice && waitingConditions != null) {
                    Text(syncWaitingConditionsLabel(waitingConditions), Modifier.testTag("sync-waiting-${task.id}"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (ownedByCurrentDevice && task.enabled && task.scheduleType != SyncScheduleType.Manual && task.nextRunAt > 0) {
                    Text(AppStrings.sync_next_run_arg0.format(arg0 = task.nextRunAt.timestampToYMDHM(currentSyncTimeZone())), style = MaterialTheme.typography.bodySmall)
                }
                if (groupTargets.isNotEmpty() && !recommendedPending) {
                    Text(if (fixedTargets.isNotEmpty()) AppStrings.sync_fixed_recommended_target
                        else AppStrings.sync_participating_targets_arg0.format(arg0 = groupTargets.size.toString()),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(text = "$sourceLabel → $targetLabel", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ownedByCurrentDevice && task.mode != SyncMode.Update && attention?.previewConfirmed != true) {
                    Text(AppStrings.sync_preview_required, style = MaterialTheme.typography.bodySmall)
                }
                if (ownedByCurrentDevice && attention != null && attention.pendingDeletionCount > 0) {
                    Text(AppStrings.sync_pending_count.format(arg0 = attention.pendingDeletionCount.toString()), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            if (!selectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (ownedByCurrentDevice) {
                        FilledTonalIconButton(onClick = onRun) {
                            Icon(Icons.Default.PlayArrow, contentDescription = AppStrings.ui_execute_immediately)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (ownedByCurrentDevice && waitingConditions != null) {
                                DropdownMenuItem(text = { Text(AppStrings.sync_cancel_waiting) },
                                    onClick = { menuExpanded = false; onCancelWaiting() })
                            }
                            if (ownedByCurrentDevice) {
                                DropdownMenuItem(text = { Text(AppStrings.sync_preview_title) }, onClick = { menuExpanded = false; onPreview() })
                                if ((attention?.pendingDeletionCount ?: 0) > 0) {
                                    DropdownMenuItem(text = { Text(AppStrings.sync_pending_deletions_title) }, onClick = { menuExpanded = false; onPendingDeletions() })
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_details) },
                                onClick = {
                                    menuExpanded = false
                                    onViewDetail()
                                },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (ownedByCurrentDevice) AppStrings.ui_edit else AppStrings.sync_take_ownership) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            if (ownedByCurrentDevice && task.scheduleType != SyncScheduleType.Manual) {
                                DropdownMenuItem(
                                    text = { Text(if (task.enabled) AppStrings.ui_turn_off_scheduled_execution else AppStrings.ui_enable_scheduled_execution) },
                                    onClick = {
                                        menuExpanded = false
                                        onToggle()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (task.enabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_copy) },
                                onClick = {
                                    menuExpanded = false
                                    onDuplicate()
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_delete) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SyncMetaLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun SyncBatchActionsFab(
    showAutoToggle: Boolean,
    enableSelected: Boolean,
    onBatchRun: () -> Unit,
    onBatchToggleEnabled: () -> Unit,
    onBatchDelete: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(8.dp)
    ) {
        FloatingActionButton(onClick = onBatchRun) {
            Icon(Icons.Default.PlayArrow, contentDescription = AppStrings.ui_run_batches)
        }
        if (showAutoToggle) {
            ExtendedFloatingActionButton(
                onClick = onBatchToggleEnabled,
                icon = {
                    Icon(
                        if (enableSelected) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null
                    )
                },
                text = {
                    Text(if (enableSelected) AppStrings.ui_enable_scheduled_execution else AppStrings.ui_turn_off_scheduled_execution)
                }
            )
        }
        ExtendedFloatingActionButton(
            onClick = onBatchDelete,
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            icon = { Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_batch_delete) },
            text = { Text(AppStrings.ui_batch_delete) }
        )
    }
}


@Composable
internal fun SyncModeFilterRow(selected: SyncMode?, onChange: (SyncMode?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterOptionChip(selected = selected == null, label = AppStrings.ui_all, onClick = { onChange(null) })
        SyncMode.entries.forEach { mode ->
            FilterOptionChip(selected = selected == mode, label = syncModeLabel(mode), onClick = { onChange(mode) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncFilterSheet(
    sheetState: SheetState,
    activeFilterCount: Int,
    filterQuery: String,
    onFilterQueryChange: (String) -> Unit,
    filterByName: Boolean,
    onFilterByNameChange: (Boolean) -> Unit,
    filterByPath: Boolean,
    onFilterByPathChange: (Boolean) -> Unit,
    enabledFilter: SyncEnabledFilter,
    onEnabledFilterChange: (SyncEnabledFilter) -> Unit,
    statusFilter: SyncStatusFilter,
    onStatusFilterChange: (SyncStatusFilter) -> Unit,
    scheduleFilter: SyncScheduleFilter,
    onScheduleFilterChange: (SyncScheduleFilter) -> Unit,
    sourceTypeFilter: SyncEndpointFilter,
    onSourceTypeFilterChange: (SyncEndpointFilter) -> Unit,
    targetTypeFilter: SyncEndpointFilter,
    onTargetTypeFilterChange: (SyncEndpointFilter) -> Unit,
    modeFilter: SyncMode?,
    onModeFilterChange: (SyncMode?) -> Unit,
    sortOption: SyncSortOption,
    onSortOptionChange: (SyncSortOption) -> Unit,
    sortDirection: SyncSortDirection,
    onSortDirectionChange: (SyncSortDirection) -> Unit,
    onApply: () -> Unit,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        FilterSheetFrame(
            activeFilterCount = activeFilterCount,
            searchQuery = filterQuery,
            onSearchQueryChange = onFilterQueryChange,
            searchPlaceholder = AppStrings.ui_search_task_name_path,
            onReset = onReset,
            onApply = onApply,
        ) {
            FilterSectionCard(title = AppStrings.sync_mode_label, icon = Icons.Default.Sync) {
                SyncModeFilterRow(modeFilter, onModeFilterChange)
            }
            FilterSectionCard(
                title = AppStrings.ui_match_field,
                icon = Icons.Default.Search,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterOptionChip(
                        selected = filterByName,
                        label = AppStrings.ui_name,
                        onClick = { onFilterByNameChange(!filterByName) }
                    )
                    FilterOptionChip(
                        selected = filterByPath,
                        label = AppStrings.ui_path,
                        onClick = { onFilterByPathChange(!filterByPath) }
                    )
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_source_type,
                icon = Icons.Default.Storage,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncEndpointFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == sourceTypeFilter,
                            label = option.label,
                            onClick = { onSourceTypeFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_target_type,
                icon = Icons.Default.Storage,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncEndpointFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == targetTypeFilter,
                            label = option.label,
                            onClick = { onTargetTypeFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_scheduled_execution,
                icon = Icons.Default.Schedule,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncEnabledFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == enabledFilter,
                            label = option.label,
                            onClick = { onEnabledFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_running_status,
                icon = Icons.Default.Schedule,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncStatusFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == statusFilter,
                            label = option.label,
                            onClick = { onStatusFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_scheduling_method,
                icon = Icons.Default.Schedule,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncScheduleFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == scheduleFilter,
                            label = option.label,
                            onClick = { onScheduleFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_sort_field,
                icon = Icons.AutoMirrored.Default.Sort,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncSortOption.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == sortOption,
                            label = option.label,
                            onClick = { onSortOptionChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_sorting_direction,
                icon = Icons.AutoMirrored.Default.Sort,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncSortDirection.entries.forEach { option ->
                        FilterOptionChip(
                            selected = option == sortDirection,
                            label = option.label,
                            onClick = { onSortDirectionChange(option) }
                        )
                    }
                }
            }
        }
    }
}
private enum class SyncEnabledFilter(val label: String) {
    All(AppStrings.ui_all),
    Enabled(AppStrings.ui_already_turned),
    Disabled(AppStrings.ui_status_closed)
}

private enum class SyncStatusFilter(val label: String) {
    All(AppStrings.ui_all),
    Running(AppStrings.ui_executing),
    Success(AppStrings.ui_success),
    Failure(AppStrings.ui_failed)
}

private enum class SyncScheduleFilter(val label: String) {
    All(AppStrings.ui_all),
    Manual(AppStrings.ui_manual),
    Interval(AppStrings.ui_interval),
    Daily(AppStrings.sync_schedule_daily),
    Weekly(AppStrings.sync_schedule_weekly),
}

private enum class SyncEndpointFilter(val label: String, val endpointType: SyncEndpointType?) {
    All(AppStrings.ui_all, null),
    Local(AppStrings.ui_local, SyncEndpointType.Local),
    Device(AppStrings.ui_equipment, SyncEndpointType.Device),
    Network(AppStrings.ui_network, SyncEndpointType.Network),
}

private enum class SyncSortOption(val label: String) {
    UpdatedAt(AppStrings.ui_update_time),
    Name(AppStrings.ui_name),
    Status(AppStrings.ui_status),
}

private enum class SyncSortDirection(val label: String) {
    Asc(AppStrings.ui_ascending_order),
    Desc(AppStrings.ui_descending_order),
}

private fun SyncStatusFilter.matches(status: SyncRunStatus): Boolean {
    return when (this) {
        SyncStatusFilter.All -> true
        SyncStatusFilter.Running -> {
            status == SyncRunStatus.Running || status == SyncRunStatus.Queued
        }

        SyncStatusFilter.Success -> status == SyncRunStatus.Success
        SyncStatusFilter.Failure -> {
            status == SyncRunStatus.Failure ||
                    status == SyncRunStatus.PartialSuccess ||
                    status == SyncRunStatus.Canceled
        }
    }
}

private fun SyncScheduleFilter.matches(scheduleType: SyncScheduleType): Boolean {
    return when (this) {
        SyncScheduleFilter.All -> true
        SyncScheduleFilter.Manual -> scheduleType == SyncScheduleType.Manual
        SyncScheduleFilter.Interval -> scheduleType == SyncScheduleType.Interval
        SyncScheduleFilter.Daily -> scheduleType == SyncScheduleType.Daily
        SyncScheduleFilter.Weekly -> scheduleType == SyncScheduleType.Weekly
    }
}

private fun SyncEndpointFilter.matches(type: SyncEndpointType): Boolean {
    return endpointType == null || endpointType == type
}

private fun sortSyncTasks(
    tasks: List<SyncTask>,
    sortOption: SyncSortOption,
    sortDirection: SyncSortDirection,
): List<SyncTask> {
    val sorted = when (sortOption) {
        SyncSortOption.UpdatedAt -> tasks.sortedBy { task -> task.updatedAt }
        SyncSortOption.Name -> tasks.sortedWith(
            compareBy<SyncTask> { task -> task.name.lowercase() }
                .thenByDescending { task -> task.updatedAt }
        )

        SyncSortOption.Status -> tasks.sortedWith(
            compareBy<SyncTask> { task -> statusSortRank(task.lastStatus) }
                .thenBy { task -> task.name.lowercase() }
                .thenByDescending { task -> task.updatedAt }
        )
    }

    return when (sortDirection) {
        SyncSortDirection.Asc -> sorted
        SyncSortDirection.Desc -> sorted.asReversed()
    }
}

private fun statusSortRank(status: SyncRunStatus): Int {
    return when (status) {
        SyncRunStatus.Running -> 0
        SyncRunStatus.Queued -> 1
        SyncRunStatus.Failure -> 2
        SyncRunStatus.PartialSuccess -> 3
        SyncRunStatus.Success -> 4
        SyncRunStatus.Idle -> 5
        SyncRunStatus.Canceled -> 6
        SyncRunStatus.Interrupted -> 2
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
