package com.folderspan.ui.screen.file.share

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceConnectType.APPROVED
import com.folderspan.data.main.device.DeviceConnectType.AUTO_CONNECT
import com.folderspan.data.main.device.DeviceConnectType.PERMANENTLY_BANNED
import com.folderspan.data.main.device.DeviceConnectType.REJECTED
import com.folderspan.data.main.device.DeviceConnectType.WAITING
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.deviceReceiveShare.SelectAll
import com.folderspan.extensions.DeviceIcon
import com.folderspan.ui.components.dialog.PathSelectorDialog
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.screen.device.confirmDeviceDeletion
import com.folderspan.ui.screen.device.defaultDeviceTypeOptions
import com.folderspan.ui.screen.device.hasAllVisibleDevicesSelected
import com.folderspan.ui.screen.device.retainVisibleDeviceSelection
import com.folderspan.ui.screen.device.toggleAllVisibleDeviceIds
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.PathUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val shareReceiveConnectTypeNameMap = linkedMapOf(
    AUTO_CONNECT to AppStrings.ui_automatic_consent,
    WAITING to AppStrings.ui_confirm_every_time,
    PERMANENTLY_BANNED to AppStrings.ui_always_refuse,
)

private enum class ShareReceiveStatusFilter {
    All,
    AutoConfirm,
    ConfirmEach,
    Banned,
}

private enum class ShareReceivePathFilter {
    All,
    Configured,
    Missing,
}

class FileShareManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val database = koinInject<FolderSpanDatabase>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var devices by remember { mutableStateOf(emptyList<SelectAll>()) }
        var selectedDeviceType by remember { mutableStateOf<DeviceType?>(null) }
        var showFilterSheet by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchDraft by remember { mutableStateOf("") }
        var statusFilter by remember { mutableStateOf(ShareReceiveStatusFilter.All) }
        var pathFilter by remember { mutableStateOf(ShareReceivePathFilter.All) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedDeviceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var selectedDevice by remember { mutableStateOf<SelectAll?>(null) }
        var showBatchEditDialog by remember { mutableStateOf(false) }

        val displayDevices = remember(devices, selectedDeviceType, searchQuery, statusFilter, pathFilter) {
            devices
                .asSequence()
                .filter { device -> selectedDeviceType == null || device.type == selectedDeviceType }
                .filter { device -> statusFilter.matches(device.connectionType) }
                .filter { device -> pathFilter.matches(device.path) }
                .filter { device ->
                    searchQuery.isBlank() || device.name.contains(searchQuery, ignoreCase = true)
                }
                .sortedWith(
                    compareBy<SelectAll> { device ->
                        when (device.connectionType) {
                            AUTO_CONNECT -> 0
                            APPROVED, WAITING -> 1
                            PERMANENTLY_BANNED, REJECTED -> 2
                        }
                    }.thenBy { device -> device.name.lowercase() }
                )
                .toList()
        }
        val visibleDeviceIds = displayDevices.map { device -> device.id }.toSet()
        val selectedDevices = displayDevices.filter { device -> device.id in selectedDeviceIds }
        val hasAllVisibleSelected = hasAllVisibleDevicesSelected(visibleDeviceIds, selectedDeviceIds)
        val showBatchActions = selectionMode && selectedDeviceIds.isNotEmpty()
        val activeFilterCount =
            (if (searchQuery.isBlank()) 0 else 1) +
                (if (statusFilter == ShareReceiveStatusFilter.All) 0 else 1) +
                (if (pathFilter == ShareReceivePathFilter.All) 0 else 1)

        suspend fun refreshDevices() {
            devices = withContext(Dispatchers.Default) {
                database.deviceReceiveShareQueries.selectAll().executeAsListAwait()
            }
        }

        suspend fun deleteDevices(deviceIds: Collection<String>) {
            devices = withContext(Dispatchers.Default) {
                deviceIds.distinct().forEach { deviceId ->
                    database.deviceReceiveShareQueries.deleteById(deviceId).awaitDatabaseReady()
                }
                database.deviceReceiveShareQueries.selectAll().executeAsListAwait()
            }
        }

        suspend fun updateDevice(
            device: SelectAll,
            result: ShareDeviceEditResult
        ) {
            withContext(Dispatchers.Default) {
                if (device.name != result.deviceName) {
                    database.deviceQueries.updateNameAndEnableRemarksById(
                        name = result.deviceName,
                        id = device.id
                    ).awaitDatabaseReady()
                }
                database.deviceReceiveShareQueries.updateConnectionTypeAndPathById(
                    connectionType = result.connectionType,
                    path = result.path,
                    id = device.id
                ).awaitDatabaseReady()
            }
            refreshDevices()
        }

        suspend fun updateDevices(
            targetDevices: Collection<SelectAll>,
            result: ShareBatchEditResult
        ) {
            withContext(Dispatchers.Default) {
                targetDevices.forEach { device ->
                    database.deviceReceiveShareQueries.updateConnectionTypeAndPathById(
                        connectionType = result.connectionType,
                        path = result.path,
                        id = device.id
                    ).awaitDatabaseReady()
                }
            }
            refreshDevices()
        }

        fun clearSelection(exitSelectionMode: Boolean = false) {
            selectedDeviceIds = emptySet()
            if (exitSelectionMode) {
                selectionMode = false
            }
        }

        LaunchedEffect(Unit) {
            refreshDevices()
        }

        LaunchedEffect(visibleDeviceIds, selectionMode) {
            val retained = retainVisibleDeviceSelection(selectedDeviceIds, visibleDeviceIds)
            if (retained != selectedDeviceIds) {
                selectedDeviceIds = retained
            }
            if (selectionMode && visibleDeviceIds.isEmpty()) {
                clearSelection(exitSelectionMode = true)
            }
        }

        selectedDevice?.let { device ->
            ShareDeviceEditDialog(
                device = device,
                onDismiss = { selectedDevice = null },
                onConfirm = { result ->
                    scope.launch {
                        updateDevice(device, result)
                        selectedDevice = null
                    }
                }
            )
        }

        if (showBatchEditDialog) {
            ShareBatchEditDialog(
                count = selectedDevices.size,
                initialPath = selectedDevices.firstOrNull()?.path.orEmpty(),
                onDismiss = { showBatchEditDialog = false },
                onConfirm = { result ->
                    scope.launch {
                        updateDevices(selectedDevices, result)
                        showBatchEditDialog = false
                        clearSelection()
                    }
                }
            )
        }

        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFilterSheet = false }
            ) {
                FilterSheetFrame(
                    activeFilterCount = activeFilterCount,
                    searchQuery = searchDraft,
                    onSearchQueryChange = { value -> searchDraft = value },
                    searchPlaceholder = AppStrings.ui_search_device_name,
                    onReset = {
                        searchQuery = ""
                        searchDraft = ""
                        statusFilter = ShareReceiveStatusFilter.All
                        pathFilter = ShareReceivePathFilter.All
                        clearSelection()
                    },
                    onApply = {
                        searchQuery = searchDraft.trim()
                        clearSelection()
                        showFilterSheet = false
                    }
                ) {
                    FilterSectionCard(
                        title = AppStrings.ui_authorization_method,
                        icon = Icons.Default.CheckCircle
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShareReceiveStatusFilter.entries.forEach { filter ->
                                FilterOptionChip(
                                    selected = statusFilter == filter,
                                    label = filter.label(),
                                    onClick = { statusFilter = filter }
                                )
                            }
                        }
                    }

                    FilterSectionCard(
                        title = AppStrings.ui_receive_directory,
                        icon = Icons.Default.FolderOpen
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShareReceivePathFilter.entries.forEach { filter ->
                                FilterOptionChip(
                                    selected = pathFilter == filter,
                                    label = filter.label(),
                                    onClick = { pathFilter = filter }
                                )
                            }
                        }
                    }
                }
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (selectionMode) AppStrings.ui_arg0_items_selected.format(arg0 = (selectedDeviceIds.size).toString()) else AppStrings.ui_share_management)
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selectionMode) {
                                    clearSelection(exitSelectionMode = true)
                                } else {
                                    navigator.pop()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectionMode) {
                                    Icons.Default.Close
                                } else {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                },
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                searchDraft = searchQuery
                                showFilterSheet = true
                            }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (activeFilterCount > 0) {
                                        Badge { Text(activeFilterCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = AppStrings.ui_filtration_equipment)
                            }
                        }

                        if (selectionMode && displayDevices.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    selectedDeviceIds = toggleAllVisibleDeviceIds(visibleDeviceIds, selectedDeviceIds)
                                }
                            ) {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = if (hasAllVisibleSelected) AppStrings.ui_deselect_all else AppStrings.ui_select_all
                                )
                            }
                        }

                        if (!selectionMode && displayDevices.isNotEmpty()) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (showBatchActions) {
                    ShareBatchActionsFab(
                        onEdit = { showBatchEditDialog = true },
                        onDelete = {
                            val deleteIds = selectedDevices.map { device -> device.id }
                            scope.launch {
                                confirmDeviceDeletion(
                                    snackbarHostState = snackbarHostState,
                                    deviceIds = deleteIds,
                                    message = AppStrings.dialog_delete_selected_devices.format(
                                        count = deleteIds.size.toString(),
                                    ),
                                ) {
                                    deleteDevices(it)
                                    clearSelection(exitSelectionMode = true)
                                }
                            }
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                floatingActionButtonPadding = if (showBatchActions) GridListFabPadding else 0.dp,
                verticalSpacing = 12.dp,
                horizontalSpacing = 12.dp
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        defaultDeviceTypeOptions.forEachIndexed { index, (type, label) ->
                            SegmentedButton(
                                selected = selectedDeviceType == type,
                                onClick = {
                                    clearSelection()
                                    selectedDeviceType = type
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = defaultDeviceTypeOptions.size
                                ),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                if (displayDevices.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FileShareDevicePlaceholder(
                            if (activeFilterCount == 0) {
                                AppStrings.ui_no_shared_authorized_devices_yet
                            } else {
                                AppStrings.ui_no_matching_sharing_device
                            }
                        )
                    }
                } else {
                    items(displayDevices, key = { device -> device.id }) { device ->
                        FileShareDeviceItem(
                            device = device,
                            selectionMode = selectionMode,
                            isSelected = device.id in selectedDeviceIds,
                            onSelectionToggle = {
                                selectedDeviceIds = if (device.id in selectedDeviceIds) {
                                    selectedDeviceIds - device.id
                                } else {
                                    selectedDeviceIds + device.id
                                }
                            },
                            onEditClick = { selectedDevice = device },
                            onDeleteClick = {
                                scope.launch {
                                    confirmDeviceDeletion(
                                        snackbarHostState = snackbarHostState,
                                        deviceIds = listOf(device.id),
                                        message = AppStrings.dialog_delete_device.format(
                                            deviceName = device.name,
                                        ),
                                    ) {
                                        deleteDevices(it)
                                        clearSelection(exitSelectionMode = true)
                                    }
                                }
                            },
                            onConnectionTypeChange = { connectionType ->
                                scope.launch {
                                    val nextPath = when {
                                        connectionType == PERMANENTLY_BANNED -> ""
                                        device.path.isNotBlank() -> device.path
                                        else -> {
                                            selectedDevice = device.copy(connectionType = connectionType)
                                            return@launch
                                        }
                                    }
                                    updateDevice(
                                        device = device,
                                        result = ShareDeviceEditResult(
                                            deviceName = device.name,
                                            connectionType = connectionType,
                                            path = nextPath
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileShareDeviceItem(
    device: SelectAll,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSelectionToggle: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onConnectionTypeChange: (DeviceConnectType) -> Unit
) {
    val status = rememberShareDeviceStatus(device.connectionType)
    var expanded by remember(device.id, selectionMode) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                colorScheme.secondaryContainer
            } else {
                colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = if (selectionMode) {
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectionToggle)
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        device.type.DeviceIcon(modifier = Modifier.size(24.dp))
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = device.name,
                        style = typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectionToggle() }
                    )
                } else {
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_actions)
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_edit) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    expanded = false
                                    onEditClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(AppStrings.ui_remove_device) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    expanded = false
                                    onDeleteClick()
                                }
                            )
                        }
                    }
                }
            }

            ShareReceiveAccessBlock(
                status = status,
                connectionType = device.connectionType,
                path = device.path,
                enabled = !selectionMode,
                onConnectionTypeChange = onConnectionTypeChange
            )
        }
    }
}

@Composable
private fun ShareReceiveAccessBlock(
    status: ShareDeviceStatusUi,
    connectionType: DeviceConnectType,
    path: String,
    enabled: Boolean,
    onConnectionTypeChange: (DeviceConnectType) -> Unit
) {
    val currentConnectionType = normalizeShareReceiveConnectionType(connectionType)
    var expanded by remember(connectionType, enabled) { mutableStateOf(false) }

    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = RoundedCornerShape(14.dp),
        modifier = if (enabled) {
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = AppStrings.ui_receive_share,
                    style = typography.labelLarge,
                    color = colorScheme.onSurface
                )

                Box {
                    Row(
                        modifier = if (enabled) Modifier.clickable { expanded = true } else Modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = status.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = status.tint
                        )
                        Text(
                            text = status.label,
                            style = typography.bodySmall,
                            color = status.tint
                        )
                        if (enabled) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = AppStrings.ui_modify_receiving_sharing_authorization,
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        shareReceiveConnectTypeNameMap
                            .filterKeys { type -> type != currentConnectionType }
                            .forEach { (type, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        expanded = false
                                        onConnectionTypeChange(type)
                                    }
                                )
                            }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurfaceVariant
                )
                Text(
                    text = path.ifBlank { AppStrings.ui_no_receiving_directory_set },
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ShareBatchActionsFab(
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp)
    ) {
        FloatingActionButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = AppStrings.ui_modify_sharing_authorization_batches)
        }
        ExtendedFloatingActionButton(
            onClick = onDelete,
            containerColor = colorScheme.error,
            icon = { Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete_devices_batches) },
            text = { Text(AppStrings.ui_delete) }
        )
    }
}

@Composable
private fun FileShareDevicePlaceholder(text: String) {
    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
        )
    }
}

@Composable
private fun ShareDeviceEditDialog(
    device: SelectAll,
    onDismiss: () -> Unit,
    onConfirm: (ShareDeviceEditResult) -> Unit
) {
    var deviceName by remember(device.id) { mutableStateOf(device.name) }
    var connectionLabel by remember(device.id) {
        mutableStateOf(shareReceiveConnectionLabel(device.connectionType))
    }
    var path by remember(device.id) { mutableStateOf(device.path) }
    var showPathSelector by remember(device.id) { mutableStateOf(false) }
    val connectionType = shareReceiveConnectionTypeForLabel(connectionLabel)
    val requiresPath = connectionType != PERMANENTLY_BANNED
    val pathIsError = requiresPath && path.isBlank()

    if (showPathSelector) {
        PathSelectorDialog(
            deskType = Local(),
            openPath = path.ifBlank { PathUtils.getHomePath() },
            onDismiss = { showPathSelector = false },
            onConfirm = { selected ->
                showPathSelector = false
                selected?.let { file -> path = file.path }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_edit_shared_device) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = deviceName,
                    onValueChange = { value -> deviceName = value },
                    label = { Text(AppStrings.ui_device_name) },
                    isError = deviceName.isBlank(),
                    supportingText = {
                        if (deviceName.isBlank()) {
                            Text(AppStrings.ui_device_name_cannot_empty)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                EditableExposedDropdownMenu(
                    optionsUiState = StringListUiState(shareReceiveConnectTypeNameMap.values.toList()),
                    value = connectionLabel,
                    onValueChange = { value -> connectionLabel = value },
                    label = { Text(AppStrings.ui_authorization_method) },
                    readOnly = true,
                    isError = connectionType == null,
                    modifier = Modifier.fillMaxWidth()
                )

                TextField(
                    value = path,
                    enabled = requiresPath,
                    onValueChange = { value -> path = value },
                    label = { Text(AppStrings.ui_receive_directory) },
                    trailingIcon = {
                        IconButton(
                            onClick = { showPathSelector = true },
                            enabled = requiresPath
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = AppStrings.ui_select_receiving_directory)
                        }
                    },
                    supportingText = {
                        if (pathIsError) {
                            Text(AppStrings.ui_receiving_directory_cannot_empty)
                        }
                    },
                    isError = pathIsError,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = deviceName.isNotBlank() && connectionType != null && !pathIsError,
                onClick = {
                    val resolvedConnectionType = connectionType ?: return@TextButton
                    onConfirm(
                        ShareDeviceEditResult(
                            deviceName = deviceName.trim(),
                            connectionType = resolvedConnectionType,
                            path = if (requiresPath) path.trim() else ""
                        )
                    )
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
private fun ShareBatchEditDialog(
    count: Int,
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (ShareBatchEditResult) -> Unit
) {
    var connectionLabel by remember { mutableStateOf(shareReceiveConnectionLabel(WAITING)) }
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    var showPathSelector by remember { mutableStateOf(false) }
    val connectionType = shareReceiveConnectionTypeForLabel(connectionLabel)
    val requiresPath = connectionType != PERMANENTLY_BANNED
    val pathIsError = requiresPath && path.isBlank()

    if (showPathSelector) {
        PathSelectorDialog(
            deskType = Local(),
            openPath = path.ifBlank { PathUtils.getHomePath() },
            onDismiss = { showPathSelector = false },
            onConfirm = { selected ->
                showPathSelector = false
                selected?.let { file -> path = file.path }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_modify_sharing_authorization_batches) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(AppStrings.ui_shared_reception_settings_selected_arg0_devices_will_modified.format(arg0 = (count).toString()))

                EditableExposedDropdownMenu(
                    optionsUiState = StringListUiState(shareReceiveConnectTypeNameMap.values.toList()),
                    value = connectionLabel,
                    onValueChange = { value -> connectionLabel = value },
                    label = { Text(AppStrings.ui_authorization_method) },
                    readOnly = true,
                    isError = connectionType == null,
                    modifier = Modifier.fillMaxWidth()
                )

                TextField(
                    value = path,
                    enabled = requiresPath,
                    onValueChange = { value -> path = value },
                    label = { Text(AppStrings.ui_receive_directory) },
                    trailingIcon = {
                        IconButton(
                            onClick = { showPathSelector = true },
                            enabled = requiresPath
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = AppStrings.ui_select_receiving_directory)
                        }
                    },
                    supportingText = {
                        if (pathIsError) {
                            Text(AppStrings.ui_receiving_directory_cannot_empty)
                        }
                    },
                    isError = pathIsError,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = count > 0 && connectionType != null && !pathIsError,
                onClick = {
                    val resolvedConnectionType = connectionType ?: return@TextButton
                    onConfirm(
                        ShareBatchEditResult(
                            connectionType = resolvedConnectionType,
                            path = if (requiresPath) path.trim() else ""
                        )
                    )
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
private fun rememberShareDeviceStatus(connectionType: DeviceConnectType): ShareDeviceStatusUi {
    return when (connectionType) {
        AUTO_CONNECT -> ShareDeviceStatusUi(Icons.Default.CheckCircle, AppStrings.ui_automatic_consent, colorScheme.primary)
        PERMANENTLY_BANNED -> ShareDeviceStatusUi(Icons.Default.Cancel, AppStrings.ui_always_refuse, colorScheme.error)
        REJECTED -> ShareDeviceStatusUi(Icons.Default.Cancel, AppStrings.ui_rejected, colorScheme.error)
        APPROVED, WAITING -> ShareDeviceStatusUi(Icons.Default.Schedule, AppStrings.ui_confirm_every_time, colorScheme.tertiary)
    }
}

private fun normalizeShareReceiveConnectionType(connectionType: DeviceConnectType): DeviceConnectType {
    return when (connectionType) {
        AUTO_CONNECT -> AUTO_CONNECT
        PERMANENTLY_BANNED, REJECTED -> PERMANENTLY_BANNED
        APPROVED, WAITING -> WAITING
    }
}

private fun shareReceiveConnectionLabel(connectionType: DeviceConnectType): String {
    return shareReceiveConnectTypeNameMap.getValue(normalizeShareReceiveConnectionType(connectionType))
}

private fun shareReceiveConnectionTypeForLabel(label: String): DeviceConnectType? {
    return shareReceiveConnectTypeNameMap.entries.firstOrNull { entry -> entry.value == label }?.key
}

private fun ShareReceiveStatusFilter.label(): String {
    return when (this) {
        ShareReceiveStatusFilter.All -> AppStrings.ui_all
        ShareReceiveStatusFilter.AutoConfirm -> AppStrings.ui_automatic_consent
        ShareReceiveStatusFilter.ConfirmEach -> AppStrings.ui_confirm_every_time
        ShareReceiveStatusFilter.Banned -> AppStrings.ui_reject
    }
}

private fun ShareReceiveStatusFilter.matches(connectionType: DeviceConnectType): Boolean {
    val normalized = normalizeShareReceiveConnectionType(connectionType)
    return when (this) {
        ShareReceiveStatusFilter.All -> true
        ShareReceiveStatusFilter.AutoConfirm -> normalized == AUTO_CONNECT
        ShareReceiveStatusFilter.ConfirmEach -> normalized == WAITING
        ShareReceiveStatusFilter.Banned -> normalized == PERMANENTLY_BANNED
    }
}

private fun ShareReceivePathFilter.label(): String {
    return when (this) {
        ShareReceivePathFilter.All -> AppStrings.ui_all
        ShareReceivePathFilter.Configured -> AppStrings.ui_already_set
        ShareReceivePathFilter.Missing -> AppStrings.ui_not_set
    }
}

private fun ShareReceivePathFilter.matches(path: String): Boolean {
    return when (this) {
        ShareReceivePathFilter.All -> true
        ShareReceivePathFilter.Configured -> path.isNotBlank()
        ShareReceivePathFilter.Missing -> path.isBlank()
    }
}

private data class ShareDeviceEditResult(
    val deviceName: String,
    val connectionType: DeviceConnectType,
    val path: String,
)

private data class ShareBatchEditResult(
    val connectionType: DeviceConnectType,
    val path: String,
)

private data class ShareDeviceStatusUi(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
)
