package com.folderspan.ui.screen.sync

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ignore.normalizeSupportedIgnoreFileNames
import com.folderspan.ui.components.dialog.SyncPathSelectorDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.menu.EditableExposedOutlinedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.screen.network.NetworkEditSectionTitle
import com.folderspan.ui.state.main.*
import com.folderspan.utils.executeAsListAwait
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.max

class SyncEditScreen(
    private val taskId: Long? = null,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val syncState = koinInject<SyncState>()
        val deviceState = koinInject<DeviceState>()
        val networkState = koinInject<NetworkState>()
        val database = koinInject<FolderSpanDatabase>()
        val coroutineScope = rememberCoroutineScope()
        val revision = syncState.revision
        val snackbarHostState = remember { SnackbarHostState() }

        val editingTask = remember(revision, taskId) {
            taskId?.let { item -> syncState.getTask(item) }
        }
        val template = remember(editingTask) {
            editingTask ?: syncState.newTaskTemplate()
        }

        val endpoints = syncState.availableEndpoints()

        var name by rememberSaveable(taskId) { mutableStateOf(template.name) }
        var enabled by rememberSaveable(taskId) { mutableStateOf(template.enabled) }
        var sourceType by rememberSaveable(taskId) { mutableStateOf(template.sourceType) }
        var sourceRef by rememberSaveable(taskId) { mutableStateOf(template.sourceRef) }
        var sourcePath by rememberSaveable(taskId) { mutableStateOf(template.sourcePath) }
        var targetType by rememberSaveable(taskId) { mutableStateOf(template.targetType) }
        var targetRef by rememberSaveable(taskId) { mutableStateOf(template.targetRef) }
        var targetPath by rememberSaveable(taskId) { mutableStateOf(template.targetPath) }
        var includeSubdirectories by rememberSaveable(taskId) { mutableStateOf(template.includeSubdirectories) }
        var includeEmptyDirectories by rememberSaveable(taskId) { mutableStateOf(template.includeEmptyDirectories) }
        var useIgnoreFiles by rememberSaveable(taskId) { mutableStateOf(template.useIgnoreFiles) }
        var ignoreFileSelection by rememberSaveable(taskId) {
            mutableStateOf(encodeIgnoreFileSelection(template.ignoreFileNames))
        }
        var ignoreFileDraftSelection by rememberSaveable(taskId) {
            mutableStateOf(encodeIgnoreFileSelection(template.ignoreFileNames))
        }
        var availableIgnoreFileNames by remember(taskId) { mutableStateOf(emptyList<String>()) }
        var showIgnoreFileSelection by rememberSaveable(taskId) { mutableStateOf(false) }
        var previousSourceType by remember(taskId) { mutableStateOf(template.sourceType) }
        var previousSourceRef by remember(taskId) { mutableStateOf(template.sourceRef) }
        var previousSourcePath by remember(taskId) { mutableStateOf(template.sourcePath) }
        val ignoreFileNames = decodeIgnoreFileSelection(ignoreFileSelection)
        val ignoreFileDraftNames = decodeIgnoreFileSelection(ignoreFileDraftSelection)
        var filterRaw by rememberSaveable(taskId) { mutableStateOf(template.filterRaw) }
        var conflictPolicy by rememberSaveable(taskId) { mutableStateOf(template.conflictPolicy) }
        var scheduleType by rememberSaveable(taskId) { mutableStateOf(template.scheduleType) }
        var intervalMinutesText by rememberSaveable(taskId) {
            mutableStateOf(template.intervalMinutes.takeIf { item -> item > 0 }?.toString() ?: "15")
        }
        var showSourcePathSelector by rememberSaveable(taskId) { mutableStateOf(false) }
        var selectedSourcePath by rememberSaveable(taskId) { mutableStateOf(sourcePath) }
        var showTargetPathSelector by rememberSaveable(taskId) { mutableStateOf(false) }
        var selectedTargetPath by rememberSaveable(taskId) { mutableStateOf(targetPath) }
        var persistedDeviceNameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

        fun labelForType(type: SyncEndpointType): String {
            return when (type) {
                SyncEndpointType.Local -> AppStrings.ui_local
                SyncEndpointType.Device -> AppStrings.ui_equipment
                SyncEndpointType.Network -> AppStrings.ui_network
            }
        }

        fun optionsFor(type: SyncEndpointType, requireReadable: Boolean): List<SyncEndpoint> {
            return endpoints.filter { item ->
                item.type == type && if (requireReadable) item.canRead else item.canWrite
            }
        }

        val connectedDeviceNameById = deviceState.devices.associate { item -> item.id to item.name }
        val deviceNameById = persistedDeviceNameById + connectedDeviceNameById

        fun savedEndpointLabel(type: SyncEndpointType, ref: String): String {
            return when (type) {
                SyncEndpointType.Local -> AppStrings.ui_local
                SyncEndpointType.Device -> {
                    val name = deviceNameById[ref]
                    if (name.isNullOrBlank()) AppStrings.ui_device_reference_arg0.format(arg0 = ref) else AppStrings.ui_arg0_offline.format(arg0 = name)
                }

                SyncEndpointType.Network -> AppStrings.ui_network_endpoint_saved_currently_unavailable
            }
        }

        fun withSavedEndpoint(
            type: SyncEndpointType,
            options: List<SyncEndpoint>,
            currentRef: String,
        ): List<SyncEndpoint> {
            if (type == SyncEndpointType.Local || currentRef.isBlank()) return options
            if (options.any { item -> item.ref == currentRef }) return options
            return listOf(
                SyncEndpoint(
                    type = type,
                    ref = currentRef,
                    label = savedEndpointLabel(type, currentRef),
                    canRead = true,
                    canWrite = true,
                )
            ) + options
        }

        fun selectedRefLabel(type: SyncEndpointType, ref: String, options: List<SyncEndpoint>): String {
            if (type == SyncEndpointType.Local) return AppStrings.ui_local
            return options.firstOrNull { item -> item.ref == ref }?.label
                ?: if (ref.isNotBlank()) savedEndpointLabel(type, ref) else ""
        }

        val sourceOptions = optionsFor(sourceType, requireReadable = true)
        val targetOptions = optionsFor(targetType, requireReadable = false)
        val sourceDisplayOptions = withSavedEndpoint(sourceType, sourceOptions, sourceRef)
        val targetDisplayOptions = withSavedEndpoint(targetType, targetOptions, targetRef)
        val sourceSelectorDesk = when (sourceType) {
            SyncEndpointType.Local -> Local()
            SyncEndpointType.Device -> deviceState.devices.firstOrNull { item -> item.id == sourceRef }
            SyncEndpointType.Network -> networkState.networks.firstOrNull { item -> item.buildProtocolId() == sourceRef }
        }
        val targetSelectorDesk = when (targetType) {
            SyncEndpointType.Local -> Local()
            SyncEndpointType.Device -> deviceState.devices.firstOrNull { item -> item.id == targetRef }
            SyncEndpointType.Network -> networkState.networks.firstOrNull { item -> item.buildProtocolId() == targetRef }
        }

        fun openSourcePathSelector() {
            if (sourceType != SyncEndpointType.Local && sourceRef.isBlank()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(AppStrings.ui_please_select_source_endpoint_first)
                }
                return
            }
            if (sourceSelectorDesk == null) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(AppStrings.ui_source_endpoint_unavailable_path_cannot_selected)
                }
                return
            }
            selectedSourcePath = sourcePath.ifBlank { sourceSelectorDesk.pathSeparator }
            showSourcePathSelector = true
        }

        fun openTargetPathSelector() {
            if (targetType != SyncEndpointType.Local && targetRef.isBlank()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(AppStrings.ui_please_select_target_endpoint_first)
                }
                return
            }
            if (targetSelectorDesk == null) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(AppStrings.ui_target_endpoint_unavailable_path_cannot_selected)
                }
                return
            }
            selectedTargetPath = targetPath.ifBlank { targetSelectorDesk.pathSeparator }
            showTargetPathSelector = true
        }

        val taskDeviceIds = remember(sourceType, sourceRef, targetType, targetRef) {
            buildSet {
                if (sourceType == SyncEndpointType.Device && sourceRef.isNotBlank()) {
                    add(sourceRef)
                }
                if (targetType == SyncEndpointType.Device && targetRef.isNotBlank()) {
                    add(targetRef)
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

        LaunchedEffect(sourceType, sourceOptions) {
            if (sourceType == SyncEndpointType.Local) {
                sourceRef = ""
            } else if (sourceOptions.any { item -> item.ref == sourceRef }) {
                // keep current selection
            } else if (
                editingTask != null &&
                sourceType == editingTask.sourceType &&
                sourceRef == editingTask.sourceRef
            ) {
                // keep persisted endpoint for edit mode (may be offline)
            } else {
                sourceRef = sourceOptions.firstOrNull()?.ref.orEmpty()
            }
        }
        LaunchedEffect(targetType, targetOptions) {
            if (targetType == SyncEndpointType.Local) {
                targetRef = ""
            } else if (targetOptions.any { item -> item.ref == targetRef }) {
                // keep current selection
            } else if (
                editingTask != null &&
                targetType == editingTask.targetType &&
                targetRef == editingTask.targetRef
            ) {
                // keep persisted endpoint for edit mode (may be offline)
            } else {
                targetRef = targetOptions.firstOrNull()?.ref.orEmpty()
            }
        }
        LaunchedEffect(sourceType, sourceRef, sourcePath, useIgnoreFiles) {
            val sourceChanged = syncSourceChanged(
                previousType = previousSourceType,
                previousRef = previousSourceRef,
                previousPath = previousSourcePath,
                currentType = sourceType,
                currentRef = sourceRef,
                currentPath = sourcePath,
            )
            var selectionForSource = ignoreFileNames
            if (sourceChanged) {
                selectionForSource = emptyList()
                ignoreFileSelection = encodeIgnoreFileSelection(emptyList())
                ignoreFileDraftSelection = encodeIgnoreFileSelection(emptyList())
                availableIgnoreFileNames = emptyList()
                showIgnoreFileSelection = false
            }
            previousSourceType = sourceType
            previousSourceRef = sourceRef
            previousSourcePath = sourcePath

            if (
                !useIgnoreFiles ||
                sourcePath.isBlank() ||
                (sourceType != SyncEndpointType.Local && sourceRef.isBlank())
            ) {
                availableIgnoreFileNames = emptyList()
                showIgnoreFileSelection = false
                return@LaunchedEffect
            }

            val discoveredFileNames = syncState.discoverSourceIgnoreFileNames(
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourcePath = sourcePath,
            ).getOrElse {
                availableIgnoreFileNames = emptyList()
                showIgnoreFileSelection = false
                return@LaunchedEffect
            }
            availableIgnoreFileNames = discoveredFileNames
            val retainedSelection = retainAvailableIgnoreFileSelection(
                selectedFileNames = selectionForSource,
                availableFileNames = discoveredFileNames,
            )
            ignoreFileSelection = encodeIgnoreFileSelection(retainedSelection)
            ignoreFileDraftSelection = encodeIgnoreFileSelection(retainedSelection)
        }

        val strictEndpointValidation = editingTask == null

        val validationError = remember(
            name,
            sourceType,
            sourceRef,
            sourcePath,
            targetType,
            targetRef,
            targetPath,
            revision,
            strictEndpointValidation,
        ) {
            syncState.validateTask(
                name = name,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourcePath = sourcePath,
                targetType = targetType,
                targetRef = targetRef,
                targetPath = targetPath,
                strictEndpointAvailability = strictEndpointValidation,
            )
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (editingTask == null) AppStrings.ui_add_sync else AppStrings.ui_edit_sync,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text(AppStrings.ui_save) },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        if (validationError != null) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(validationError)
                            }
                            return@ExtendedFloatingActionButton
                        }
                        coroutineScope.launch {
                            val interval = intervalMinutesText.toLongOrNull()?.coerceAtLeast(1L) ?: 15L

                            val task = SyncTask(
                                id = template.id,
                                name = name.trim(),
                                enabled = enabled,
                                sourceType = sourceType,
                                sourceRef = sourceRef,
                                sourcePath = sourcePath.trim(),
                                targetType = targetType,
                                targetRef = targetRef,
                                targetPath = targetPath.trim(),
                                includeSubdirectories = includeSubdirectories,
                                includeEmptyDirectories = includeEmptyDirectories,
                                useIgnoreFiles = useIgnoreFiles,
                                ignoreFileNames = ignoreFileNames,
                                filterRaw = filterRaw.trim(),
                                conflictPolicy = conflictPolicy,
                                scheduleType = scheduleType,
                                intervalMinutes = interval,
                                createdAt = template.createdAt,
                            )
                            val result = syncState.createOrUpdateTask(task)
                            if (result.isSuccess) {
                                navigator.pop()
                            } else {
                                snackbarHostState.showSnackbar(
                                    result.exceptionOrNull()?.message ?: AppStrings.ui_save_failed
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalSpacing = 16.dp,
                horizontalSpacing = 16.dp,
                floatingActionButtonPadding = GridListFabPadding,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkEditSectionTitle(AppStrings.ui_basics)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { item -> name = item },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(AppStrings.ui_task_name) },
                        supportingText = {
                            if (validationError != null) {
                                Text(validationError, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        isError = validationError != null,
                        singleLine = true,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkEditSectionTitle(AppStrings.ui_source)
                }
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        EditableExposedOutlinedDropdownMenu(
                            optionsUiState = StringListUiState(SyncEndpointType.entries.map { labelForType(it) }),
                            value = labelForType(sourceType),
                            onValueChange = { selected ->
                                sourceType = SyncEndpointType.entries.firstOrNull { labelForType(it) == selected }
                                    ?: sourceType
                            },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text(AppStrings.ui_source_type) },
                        )
                    }
                }
                item(span = { GridItemSpan(max(1, maxLineSpan - 1)) }) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (sourceType == SyncEndpointType.Local) {
                            SyncPathTextField(
                                value = sourcePath,
                                onValueChange = { item -> sourcePath = item },
                                modifier = Modifier.fillMaxWidth(),
                                label = AppStrings.ui_source_path,
                                selectContentDescription = AppStrings.ui_select_source_path,
                                onSelectClick = { openSourcePathSelector() },
                            )
                        } else {
                            EditableExposedOutlinedDropdownMenu(
                                optionsUiState = StringListUiState(sourceDisplayOptions.map { item -> item.label }),
                                value = selectedRefLabel(sourceType, sourceRef, sourceDisplayOptions),
                                onValueChange = { selected ->
                                    sourceRef = sourceDisplayOptions.firstOrNull { item -> item.label == selected }
                                        ?.ref
                                        .orEmpty()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                label = { Text(AppStrings.ui_source_endpoint) },
                            )
                        }
                    }
                }
                if (sourceType != SyncEndpointType.Local) {
                    if (sourceOptions.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = AppStrings.ui_no_source_endpoint_available_needs_support_reading,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SyncPathTextField(
                            value = sourcePath,
                            onValueChange = { item -> sourcePath = item },
                            modifier = Modifier.fillMaxWidth(),
                            label = AppStrings.ui_source_path,
                            selectContentDescription = AppStrings.ui_select_source_path,
                            onSelectClick = { openSourcePathSelector() },
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkEditSectionTitle(AppStrings.ui_target)
                }
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        EditableExposedOutlinedDropdownMenu(
                            optionsUiState = StringListUiState(SyncEndpointType.entries.map { labelForType(it) }),
                            value = labelForType(targetType),
                            onValueChange = { selected ->
                                targetType = SyncEndpointType.entries.firstOrNull { labelForType(it) == selected }
                                    ?: targetType
                            },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text(AppStrings.ui_target_type) },
                        )
                    }
                }
                item(span = { GridItemSpan(max(1, maxLineSpan - 1)) }) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (targetType == SyncEndpointType.Local) {
                            SyncPathTextField(
                                value = targetPath,
                                onValueChange = { item -> targetPath = item },
                                modifier = Modifier.fillMaxWidth(),
                                label = AppStrings.ui_target_path,
                                selectContentDescription = AppStrings.ui_select_target_path,
                                onSelectClick = { openTargetPathSelector() },
                            )
                        } else {
                            EditableExposedOutlinedDropdownMenu(
                                optionsUiState = StringListUiState(targetDisplayOptions.map { item -> item.label }),
                                value = selectedRefLabel(targetType, targetRef, targetDisplayOptions),
                                onValueChange = { selected ->
                                    targetRef = targetDisplayOptions.firstOrNull { item -> item.label == selected }
                                        ?.ref
                                        .orEmpty()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                label = { Text(AppStrings.ui_target_endpoint) },
                            )
                        }
                    }
                }
                if (targetType != SyncEndpointType.Local) {
                    if (targetOptions.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = AppStrings.ui_no_target_endpoint_available_needs_support_writing,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SyncPathTextField(
                            value = targetPath,
                            onValueChange = { item -> targetPath = item },
                            modifier = Modifier.fillMaxWidth(),
                            label = AppStrings.ui_target_path,
                            selectContentDescription = AppStrings.ui_select_target_path,
                            onSelectClick = { openTargetPathSelector() },
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkEditSectionTitle(AppStrings.ui_strategy)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SyncConflictPolicy.entries.forEachIndexed { index, policy ->
                            SegmentedButton(
                                selected = conflictPolicy == policy,
                                onClick = { conflictPolicy = policy },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = SyncConflictPolicy.entries.size,
                                ),
                            ) {
                                Text(
                                    when (policy) {
                                        SyncConflictPolicy.Replace -> AppStrings.ui_cover
                                        SyncConflictPolicy.Skip -> AppStrings.ui_skip
                                        SyncConflictPolicy.Rename -> AppStrings.ui_rename
                                    }
                                )
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(AppStrings.ui_contains_subdirectories)
                        Switch(
                            checked = includeSubdirectories,
                            onCheckedChange = { item -> includeSubdirectories = item }
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(AppStrings.ui_synchronize_empty_directories)
                        Switch(
                            checked = includeEmptyDirectories,
                            onCheckedChange = { item -> includeEmptyDirectories = item }
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AdvancedIgnoreConfiguration(
                        enabled = useIgnoreFiles,
                        selectedFileNames = ignoreFileNames,
                        availableFileNames = availableIgnoreFileNames,
                        onEnabledChange = { value ->
                            useIgnoreFiles = value
                            if (!value) {
                                ignoreFileDraftSelection = ignoreFileSelection
                                showIgnoreFileSelection = false
                            }
                        },
                        onSelectFiles = {
                            ignoreFileDraftSelection = ignoreFileSelection
                            showIgnoreFileSelection = true
                        },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = filterRaw,
                        onValueChange = { item -> filterRaw = item },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(AppStrings.ui_filter_rules) },
                        minLines = 3,
                        maxLines = 8,
                        supportingText = { Text(AppStrings.ui_one_rule_per_line_example_tmp_log) }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkEditSectionTitle(AppStrings.ui_scheduling)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SyncScheduleType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = scheduleType == type,
                                onClick = { scheduleType = type },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = SyncScheduleType.entries.size,
                                ),
                            ) {
                                Text(
                                    when (type) {
                                        SyncScheduleType.Manual -> AppStrings.ui_manual
                                        SyncScheduleType.Interval -> AppStrings.ui_interval
                                    }
                                )
                            }
                        }
                    }
                }
                if (scheduleType == SyncScheduleType.Interval) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(AppStrings.ui_enable_scheduled_execution)
                            Switch(checked = enabled, onCheckedChange = { item -> enabled = item })
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = intervalMinutesText,
                            onValueChange = { item -> intervalMinutesText = item },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(AppStrings.ui_interval_minutes) },
                            singleLine = true,
                        )
                    }
                }
            }
        }

        if (showSourcePathSelector && sourceSelectorDesk != null) {
            SyncPathSelectorDialog(
                title = AppStrings.ui_select_source_path,
                deskType = sourceSelectorDesk,
                selectedPath = selectedSourcePath,
                onConfirm = { path ->
                    selectedSourcePath = path
                    sourcePath = path
                    showSourcePathSelector = false
                },
                onDismiss = { showSourcePathSelector = false }
            )
        }

        if (showTargetPathSelector && targetSelectorDesk != null) {
            SyncPathSelectorDialog(
                title = AppStrings.ui_select_target_path,
                deskType = targetSelectorDesk,
                selectedPath = selectedTargetPath,
                onConfirm = { path ->
                    selectedTargetPath = path
                    targetPath = path
                    showTargetPathSelector = false
                },
                onDismiss = { showTargetPathSelector = false }
            )
        }

        if (showIgnoreFileSelection) {
            IgnoreFileSelectionDialog(
                availableFileNames = availableIgnoreFileNames,
                selectedFileNames = ignoreFileDraftNames,
                onSelectionChange = { fileNames ->
                    ignoreFileDraftSelection = encodeIgnoreFileSelection(fileNames)
                },
                onConfirm = {
                    ignoreFileSelection = ignoreFileDraftSelection
                    showIgnoreFileSelection = false
                },
                onDismiss = {
                    ignoreFileDraftSelection = ignoreFileSelection
                    showIgnoreFileSelection = false
                },
            )
        }
    }
}

@Composable
private fun SyncPathTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    selectContentDescription: String,
    onSelectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onSelectClick) {
                Icon(Icons.Default.FolderOpen, contentDescription = selectContentDescription)
            }
        },
        singleLine = true,
    )
}

@Composable
internal fun AdvancedIgnoreConfiguration(
    enabled: Boolean,
    selectedFileNames: List<String>,
    availableFileNames: List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onSelectFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedSelection = normalizeSupportedIgnoreFileNames(selectedFileNames)
    val normalizedAvailable = normalizeSupportedIgnoreFileNames(availableFileNames)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            ) {
                Text(AppStrings.ui_advanced_ignore)
                Text(
                    text = AppStrings.ui_advanced_ignore_description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("sync-advanced-ignore-switch"),
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            if (normalizedAvailable.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("sync-ignore-file-selector")
                        .clickable(onClick = onSelectFiles)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                    ) {
                        Text(AppStrings.ui_ignore_files)
                        Text(
                            text = normalizedSelection
                                .takeIf { fileNames -> fileNames.isNotEmpty() }
                                ?.joinToString(", ")
                                ?: AppStrings.ui_no_ignore_files_selected,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = AppStrings.ui_select_ignore_files,
                    )
                }
            } else {
                Text(
                    text = AppStrings.ui_no_ignore_files_found_in_source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun IgnoreFileSelectionDialog(
    availableFileNames: List<String>,
    selectedFileNames: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val normalizedAvailable = normalizeSupportedIgnoreFileNames(availableFileNames)
    val normalizedSelection = retainAvailableIgnoreFileSelection(
        selectedFileNames = selectedFileNames,
        availableFileNames = normalizedAvailable,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_ignore_file_selection) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                normalizedAvailable.forEach { fileName ->
                    val selected = fileName in normalizedSelection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync-ignore-file-$fileName")
                            .toggleable(
                                value = selected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    val updated = if (checked) {
                                        normalizedSelection + fileName
                                    } else {
                                        normalizedSelection - fileName
                                    }
                                    onSelectionChange(
                                        retainAvailableIgnoreFileSelection(
                                            selectedFileNames = updated,
                                            availableFileNames = normalizedAvailable,
                                        )
                                    )
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(fileName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}

internal fun encodeIgnoreFileSelection(fileNames: List<String>): String =
    normalizeSupportedIgnoreFileNames(fileNames).joinToString("\n")

internal fun decodeIgnoreFileSelection(encoded: String): List<String> =
    normalizeSupportedIgnoreFileNames(encoded.lineSequence().toList())

internal fun retainAvailableIgnoreFileSelection(
    selectedFileNames: List<String>,
    availableFileNames: List<String>,
): List<String> {
    val available = normalizeSupportedIgnoreFileNames(availableFileNames).toSet()
    return normalizeSupportedIgnoreFileNames(selectedFileNames)
        .filter { fileName -> fileName in available }
}

internal fun syncSourceChanged(
    previousType: SyncEndpointType,
    previousRef: String,
    previousPath: String,
    currentType: SyncEndpointType,
    currentRef: String,
    currentPath: String,
): Boolean =
    previousType != currentType || previousRef != currentRef || previousPath != currentPath
