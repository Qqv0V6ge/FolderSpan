package com.folderspan.ui.screen.sync

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
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
        val deviceState = koinInject<DeviceState>()
        val database = koinInject<FolderSpanDatabase>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
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
        ) {
            derivedStateOf {
                tasks.filter { task ->
                    val enabledMatched = when (enabledFilter) {
                        SyncEnabledFilter.All -> true
                        SyncEnabledFilter.Enabled -> task.scheduleType == SyncScheduleType.Interval && task.enabled
                        SyncEnabledFilter.Disabled -> task.scheduleType == SyncScheduleType.Interval && !task.enabled
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

                    enabledMatched && statusMatched && scheduleMatched &&
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
        ) {
            (if (normalizedQuery.isBlank()) 0 else 1) +
                (if (filterByName && filterByPath) 0 else 1) +
                (if (enabledFilter == SyncEnabledFilter.All) 0 else 1) +
                (if (statusFilter == SyncStatusFilter.All) 0 else 1) +
                (if (scheduleFilter == SyncScheduleFilter.All) 0 else 1) +
                (if (sourceTypeFilter == SyncEndpointFilter.All) 0 else 1) +
                (if (targetTypeFilter == SyncEndpointFilter.All) 0 else 1) +
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
                            item.scheduleType == SyncScheduleType.Interval
                        }
                        val enableSelected = selectedScheduledTasks.any { item -> !item.enabled }
                        SyncBatchActionsFab(
                            showAutoToggle = selectedScheduledTasks.isNotEmpty(),
                            enableSelected = enableSelected,
                            onBatchRun = {
                                val targets = selectedTasks.toList()
                                targets.forEach { task ->
                                    syncState.runNow(task.id)
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(AppStrings.ui_arg0_tasks_started.format(arg0 = (targets.size).toString()))
                                }
                            },
                            onBatchToggleEnabled = {
                                if (selectedScheduledTasks.isEmpty()) return@SyncBatchActionsFab
                                val nextEnabled = selectedScheduledTasks.any { task -> !task.enabled }
                                selectedScheduledTasks.forEach { task ->
                                    syncState.updateEnabled(task.id, nextEnabled)
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (nextEnabled) {
                                            AppStrings.ui_scheduled_execution_arg0_tasks_has_been_enabled.format(arg0 = (selectedScheduledTasks.size).toString())
                                        } else {
                                            AppStrings.ui_scheduled_execution_arg0_tasks_has_been_closed.format(arg0 = (selectedScheduledTasks.size).toString())
                                        }
                                    )
                                }
                            },
                            onBatchDelete = {
                                val targets = selectedTasks.toList()
                                if (targets.isEmpty()) return@SyncBatchActionsFab
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = AppStrings.ui_you_sure_you_want_delete_arg0_tasks.format(arg0 = (targets.size).toString()),
                                        actionLabel = AppStrings.ui_delete,
                                        withDismissAction = true,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        targets.forEach { task ->
                                            syncState.deleteTask(task.id)
                                        }
                                        selectionMode = false
                                        selectedTaskIds = emptySet()
                                        snackbarHostState.showSnackbar(AppStrings.ui_arg0_tasks_deleted.format(arg0 = (targets.size).toString()))
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
                items(displayTasks, key = { item -> item.id }) { task ->
                    SyncTaskCard(
                        task = task,
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
                            syncState.runNow(task.id)
                            scope.launch {
                                snackbarHostState.showSnackbar(AppStrings.ui_execution_has_started_arg0.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync }))
                            }
                        },
                        onToggle = {
                            val nextEnabled = !task.enabled
                            syncState.updateEnabled(task.id, nextEnabled)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (nextEnabled) AppStrings.ui_scheduled_execution_enabled else AppStrings.ui_scheduled_execution_closed
                                )
                            }
                        },
                        onEdit = { navigator.push(SyncEditScreen(task.id)) },
                        onDuplicate = {
                            syncState.duplicateTask(task.id)
                            scope.launch {
                                snackbarHostState.showSnackbar(AppStrings.ui_task_copied)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val snackbarResult = snackbarHostState.showSnackbar(
                                    message = AppStrings.ui_confirm_deletion_task_arg0.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync }),
                                    actionLabel = AppStrings.ui_delete,
                                    withDismissAction = true,
                                )
                                if (snackbarResult == SnackbarResult.ActionPerformed) {
                                    syncState.deleteTask(task.id)
                                    snackbarHostState.showSnackbar(
                                        AppStrings.ui_deleted_task_arg0.format(arg0 = task.name.ifBlank { AppStrings.ui_unnamed_sync })
                                    )
                                }
                            }
                        }
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
                    sortOption = SyncSortOption.UpdatedAt
                    sortDirection = SyncSortDirection.Desc
                }
            )
        }
    }
}

@Composable
private fun SyncTaskCard(
    task: SyncTask,
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
) {
    val statusText = statusLabel(task.lastStatus)
    val showStatus = task.lastStatus != SyncRunStatus.Idle
    val scheduleText = when (task.scheduleType) {
        SyncScheduleType.Manual -> AppStrings.ui_manual_execution
        SyncScheduleType.Interval -> AppStrings.ui_every_arg0_minutes.format(arg0 = (task.intervalMinutes).toString())
    }
    val autoStateText = when (task.scheduleType) {
        SyncScheduleType.Manual -> null
        SyncScheduleType.Interval -> if (task.enabled) AppStrings.ui_timer else AppStrings.ui_scheduled_off
    }
    val sourceLabel = endpointText(task.sourceType.name, task.sourceRef, task.sourcePath, deviceNameByIdUiState.items)
    val targetLabel = endpointText(task.targetType.name, task.targetRef, task.targetPath, deviceNameByIdUiState.items)
    var menuExpanded by remember(task.id) { mutableStateOf(false) }

    val statusTint = when (task.lastStatus) {
        SyncRunStatus.Success -> MaterialTheme.colorScheme.primary
        SyncRunStatus.Failure -> MaterialTheme.colorScheme.error
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
            Text(
                text = "$sourceLabel → $targetLabel",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (!selectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalIconButton(onClick = onRun) {
                        Icon(Icons.Default.PlayArrow, contentDescription = AppStrings.ui_execute_immediately)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_details) },
                                onClick = {
                                    menuExpanded = false
                                    onViewDetail()
                                },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_edit) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            if (task.scheduleType == SyncScheduleType.Interval) {
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
    Interval(AppStrings.ui_timing)
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
