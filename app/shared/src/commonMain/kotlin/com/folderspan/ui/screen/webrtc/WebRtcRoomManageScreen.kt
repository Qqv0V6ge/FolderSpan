package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.data.main.webrtc.isBusy
import com.folderspan.data.main.webrtc.matchesActiveConfig
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.toRoomDisplayLabel
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.WebRtcRoomState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val WebRtcRoomIdSetSaver = listSaver<Set<Long>, Long>(
    save = { ids -> ids.toList() },
    restore = { values -> values.toSet() }
)

internal val WebRtcRoomDeleteActionLabel: String
    get() = AppStrings.android_action_delete

internal fun webRtcRoomDeleteConfirmMessage(name: String): String =
    AppStrings.dialog_delete_named_item.format(name = name)

internal fun webRtcRoomBatchDeleteConfirmMessage(count: Int): String = AppStrings.ui_you_sure_you_want_delete_selected_arg0_rooms.format(arg0 = (count).toString())

class WebRtcRoomManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val roomState = koinInject<WebRtcRoomState>()
        val deviceState = koinInject<DeviceState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val filterSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

        val connectionStatus by deviceState.webRtcConnectionStatus.collectAsState()
        val activeConfig by deviceState.webRtcRoomConfig.collectAsState()
        val peerSessionStates by deviceState.webRtcPeerSessionStates.collectAsState()
        val lastError by deviceState.webRtcLastError.collectAsState()

        var pendingConnectRoom by remember { mutableStateOf<WebRtcRoomProfile?>(null) }
        var showFilterSheet by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }
        var connectionFilter by rememberSaveable { mutableStateOf(WebRtcRoomConnectionFilter.All) }
        var connectionFilterDraft by rememberSaveable { mutableStateOf(WebRtcRoomConnectionFilter.All) }
        var pinFilter by rememberSaveable { mutableStateOf(WebRtcRoomPinFilter.All) }
        var pinFilterDraft by rememberSaveable { mutableStateOf(WebRtcRoomPinFilter.All) }
        var sourceFilter by rememberSaveable { mutableStateOf(WebRtcRoomSourceFilter.All) }
        var sourceFilterDraft by rememberSaveable { mutableStateOf(WebRtcRoomSourceFilter.All) }
        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedRoomIds by rememberSaveable(stateSaver = WebRtcRoomIdSetSaver) {
            mutableStateOf(emptySet())
        }

        val keyword = searchQuery.trim()
        val activeFilterCount =
            (if (searchQuery.isNotBlank()) 1 else 0) +
                (if (connectionFilter != WebRtcRoomConnectionFilter.All) 1 else 0) +
                (if (pinFilter != WebRtcRoomPinFilter.All) 1 else 0) +
                (if (sourceFilter != WebRtcRoomSourceFilter.All) 1 else 0)
        val hasActiveFilter = activeFilterCount > 0
        val filteredRooms = roomState.rooms.filter { room ->
            val matchesSearch = webRtcRoomMatchesSearch(
                name = room.name,
                wssUrl = room.wssUrl,
                roomId = room.roomId,
                source = room.source,
                keyword = keyword
            )
            val isConnectedRoom = room.matchesConnectedState(activeConfig, connectionStatus)
            val matchesConnection = when (connectionFilter) {
                WebRtcRoomConnectionFilter.All -> true
                WebRtcRoomConnectionFilter.Connected -> isConnectedRoom
                WebRtcRoomConnectionFilter.Unconnected -> !isConnectedRoom
            }
            val matchesPin = when (pinFilter) {
                WebRtcRoomPinFilter.All -> true
                WebRtcRoomPinFilter.Pinned -> room.pinned
                WebRtcRoomPinFilter.Unpinned -> !room.pinned
            }
            val matchesSource = sourceFilter.matches(room.source)
            matchesSearch && matchesConnection && matchesPin && matchesSource
        }
        val filteredRoomIds = filteredRooms.map { room -> room.id }.toSet()
        val selectedRooms = filteredRooms.filter { room -> room.id in selectedRoomIds }
        val allVisibleSelected = filteredRoomIds.isNotEmpty() && selectedRoomIds.size == filteredRoomIds.size

        fun openFilterSheet() {
            searchDraft = searchQuery
            connectionFilterDraft = connectionFilter
            pinFilterDraft = pinFilter
            sourceFilterDraft = sourceFilter
            showFilterSheet = true
        }

        fun clearSelection() {
            selectionMode = false
            selectedRoomIds = emptySet()
        }

        fun toggleSelection(id: Long) {
            val updatedSelection = if (id in selectedRoomIds) {
                selectedRoomIds - id
            } else {
                selectedRoomIds + id
            }
            selectedRoomIds = updatedSelection
        }

        fun resetFilters() {
            searchQuery = ""
            searchDraft = ""
            connectionFilter = WebRtcRoomConnectionFilter.All
            connectionFilterDraft = WebRtcRoomConnectionFilter.All
            pinFilter = WebRtcRoomPinFilter.All
            pinFilterDraft = WebRtcRoomPinFilter.All
            sourceFilter = WebRtcRoomSourceFilter.All
            sourceFilterDraft = WebRtcRoomSourceFilter.All
        }

        suspend fun performBatchPinned(pinned: Boolean) {
            val updated = roomState.setPinned(selectedRoomIds, pinned)
            clearSelection()
            snackbarHostState.showSnackbar(
                when {
                    updated > 0 && pinned -> AppStrings.ui_pinned_arg0_items.format(arg0 = (updated).toString())
                    updated > 0 -> AppStrings.ui_arg0_items_unpinned.format(arg0 = (updated).toString())
                    pinned -> AppStrings.ui_all_selected_rooms_have_been_pinned_top
                    else -> AppStrings.ui_all_selected_rooms_have_been_unpinned
                }
            )
        }

        suspend fun deleteRooms(roomsToDelete: List<WebRtcRoomProfile>) {
            if (
                roomsToDelete.any { room -> room.matchesActiveConfig(activeConfig) } &&
                    connectionStatus.isBusy()
            ) {
                deviceState.disconnectWebRtcRoom()
            }
            val deleted = roomState.deleteRooms(roomsToDelete.map { room -> room.id })
            clearSelection()
            snackbarHostState.showSnackbar(
                if (deleted > 0) {
                    AppStrings.ui_arg0_items_deleted.format(arg0 = (deleted).toString())
                } else {
                    AppStrings.ui_no_rooms_deleted
                }
            )
        }

        suspend fun deleteRoom(room: WebRtcRoomProfile) {
            if (room.matchesActiveConfig(activeConfig) && connectionStatus.isBusy()) {
                deviceState.disconnectWebRtcRoom()
            }
            roomState.deleteRoom(room.id)
            snackbarHostState.showSnackbar(AppStrings.ui_arg0_deleted.format(arg0 = room.name))
        }

        LaunchedEffect(Unit) {
            roomState.loadPersisted()
        }

        LaunchedEffect(filteredRoomIds, selectionMode) {
            if (!selectionMode) return@LaunchedEffect
            val previousSelection = selectedRoomIds
            val updatedSelection = previousSelection.filter { id -> id in filteredRoomIds }.toSet()
            if (updatedSelection != previousSelection) {
                selectedRoomIds = updatedSelection
            }
            if (previousSelection.isNotEmpty() && updatedSelection.isEmpty()) {
                selectionMode = false
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selectionMode) {
                                AppStrings.ui_arg0_items_selected.format(arg0 = (selectedRoomIds.size).toString())
                            } else {
                                AppStrings.ui_cross_network_management
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                clearSelection()
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
                        if (selectionMode) {
                            TextButton(
                                onClick = {
                                    if (allVisibleSelected) {
                                        clearSelection()
                                    } else {
                                        selectionMode = true
                                        selectedRoomIds = filteredRoomIds
                                    }
                                },
                                enabled = filteredRoomIds.isNotEmpty()
                            ) {
                                Text(if (allVisibleSelected) AppStrings.ui_deselect_all else AppStrings.ui_select_all)
                            }
                        } else {
                            if (hasActiveFilter) {
                                FilledTonalIconButton(onClick = { openFilterSheet() }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = AppStrings.ui_search_filter_rooms_current_arg0_conditions.format(arg0 = (activeFilterCount).toString())
                                    )
                                }
                            } else {
                                IconButton(onClick = { openFilterSheet() }) {
                                    Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_filter_rooms)
                                }
                            }
                            if (filteredRooms.isNotEmpty()) {
                                IconButton(onClick = { selectionMode = true }) {
                                    Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (selectionMode) {
                    WebRtcRoomBatchBottomBar(
                        hasSelection = selectedRooms.isNotEmpty(),
                        canConnect = selectedRooms.size == 1,
                        onConnect = {
                            selectedRooms.singleOrNull()?.let { room ->
                                pendingConnectRoom = room
                            }
                        },
                        onPin = {
                            scope.launch {
                                performBatchPinned(true)
                            }
                        },
                        onUnpin = {
                            scope.launch {
                                performBatchPinned(false)
                            }
                        },
                        onDelete = {
                            val roomsToDelete = selectedRooms.toList()
                            if (roomsToDelete.isNotEmpty()) {
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = webRtcRoomBatchDeleteConfirmMessage(roomsToDelete.size),
                                        actionLabel = WebRtcRoomDeleteActionLabel,
                                        withDismissAction = true,
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        deleteRooms(roomsToDelete)
                                    }
                                }
                            }
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (!selectionMode) {
                    ExtendedFloatingActionButton(
                        onClick = { navigator.push(WebRtcRoomEditScreen()) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(AppStrings.ui_add_new_room) }
                    )
                }
            }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                isEmpty = filteredRooms.isEmpty(),
                floatingActionButtonPadding = if (!selectionMode) GridListFabPadding else 0.dp
            ) {
                items(
                    count = filteredRooms.size,
                    key = { index -> filteredRooms[index].id }
                ) { index ->
                    val room = filteredRooms[index]
                    val isActiveRoom = room.matchesActiveConfig(activeConfig)
                    val isConnectedRoom = isActiveRoom && connectionStatus.isBusy()
                    WebRtcRoomListContent(
                        room = room,
                        isSelected = room.id in selectedRoomIds,
                        selectionMode = selectionMode,
                        connectionStatus = connectionStatus,
                        isActiveRoom = isActiveRoom,
                        isConnectedRoom = isConnectedRoom,
                        deviceCount = if (isActiveRoom) peerSessionStates.size else 0,
                        lastError = if (isActiveRoom) lastError else null,
                        onSelectionToggle = { toggleSelection(room.id) },
                        onEdit = { navigator.push(WebRtcRoomEditScreen(room)) },
                        onDelete = {
                            scope.launch {
                                val snackbarResult = snackbarHostState.showSnackbar(
                                    message = webRtcRoomDeleteConfirmMessage(room.name),
                                    actionLabel = WebRtcRoomDeleteActionLabel,
                                    withDismissAction = true,
                                )
                                if (snackbarResult == SnackbarResult.ActionPerformed) {
                                    deleteRoom(room)
                                }
                            }
                        },
                        onTogglePinned = {
                            scope.launch {
                                roomState.togglePinned(room.id)
                            }
                        },
                        onOpenDevices = { navigator.push(WebRtcRoomDevicesScreen()) },
                        onDisconnect = { deviceState.disconnectWebRtcRoom() },
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(room.id)
                            } else {
                                if (isConnectedRoom) {
                                    navigator.push(WebRtcRoomDevicesScreen())
                                } else {
                                    pendingConnectRoom = room
                                }
                            }
                        }
                    )
                }
            }
        }

        if (showFilterSheet) {
            WebRtcRoomFilterSheet(
                sheetState = filterSheetState,
                searchQuery = searchDraft,
                onSearchQueryChange = { searchDraft = it },
                connectionFilter = connectionFilterDraft,
                onConnectionFilterChange = { connectionFilterDraft = it },
                pinFilter = pinFilterDraft,
                onPinFilterChange = { pinFilterDraft = it },
                sourceFilter = sourceFilterDraft,
                onSourceFilterChange = { sourceFilterDraft = it },
                activeFilterCount = activeFilterCount,
                onApply = {
                    searchQuery = searchDraft.trim()
                    connectionFilter = connectionFilterDraft
                    pinFilter = pinFilterDraft
                    sourceFilter = sourceFilterDraft
                    showFilterSheet = false
                },
                onDismissRequest = { showFilterSheet = false },
                onReset = { resetFilters() }
            )
        }

        pendingConnectRoom?.let { room ->
            AlertDialog(
                onDismissRequest = { pendingConnectRoom = null },
                title = { Text(AppStrings.ui_connecting_rooms) },
                text = {
                    Text(
                        buildString {
                            append(AppStrings.ui_whether_connect)
                            append(room.name)
                            append("”？")
                            append('\n')
                            append('\n')
                            append(AppStrings.ui_signaling)
                            append(room.wssUrl)
                            append('\n')
                            append("Room ID：")
                            append(room.roomId)
                            append('\n')
                            append(AppStrings.webrtc_source_prefix)
                            append(room.source.label)
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val shouldClearSelection = selectionMode
                            pendingConnectRoom = null
                            if (shouldClearSelection) {
                                clearSelection()
                            }
                            deviceState.connectWebRtcRoom(room)
                        }
                    ) {
                        Text(AppStrings.ui_connect)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConnectRoom = null }) {
                        Text(AppStrings.ui_cancel)
                    }
                }
            )
        }
    }
}

@Composable
private fun WebRtcRoomListContent(
    room: WebRtcRoomProfile,
    isSelected: Boolean,
    selectionMode: Boolean,
    connectionStatus: WebRtcConnectionStatus,
    isActiveRoom: Boolean,
    isConnectedRoom: Boolean,
    deviceCount: Int,
    lastError: String?,
    onSelectionToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    onOpenDevices: () -> Unit,
    onDisconnect: () -> Unit,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectionLabel = when {
        isActiveRoom -> connectionStatus.toRoomDisplayLabel()
        !room.roomIdValid -> AppStrings.ui_configuration_exception
        else -> AppStrings.ui_not_connected
    }
    val supportText = buildString {
        append(room.wssUrl)
        if (!lastError.isNullOrBlank()) {
            append('\n')
            append(AppStrings.webrtc_error_prefix)
            append(lastError)
        }
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onSelectionToggle else onClick),
        colors = ListItemDefaults.colors(
            containerColor = when {
                isSelected -> colorScheme.tertiaryContainer
                isActiveRoom -> colorScheme.secondaryContainer
                else -> colorScheme.surface
            },
            headlineColor = when {
                isSelected -> colorScheme.onTertiaryContainer
                isActiveRoom -> colorScheme.onSecondaryContainer
                else -> colorScheme.onSurface
            },
            supportingColor = when {
                isSelected -> colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                isActiveRoom -> colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                else -> colorScheme.outline
            }
        ),
        leadingContent = when {
            selectionMode -> {
                {
                    IconButton(onClick = onSelectionToggle) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_room
                        )
                    }
                }
            }

            room.pinned -> {
                {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = AppStrings.ui_pin_top,
                        tint = colorScheme.primary
                    )
                }
            }

            else -> null
        },
        overlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(connectionLabel)
                WebRtcRoomSourceBadge(room.source)
                if (selectionMode && room.pinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = AppStrings.ui_pin_top,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (isActiveRoom) {
                    Text(AppStrings.ui_online_arg0_station.format(arg0 = (deviceCount).toString()))
                }
            }
        },
        headlineContent = {
            Text(
                text = room.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = supportText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = if (!selectionMode) {
            {
                var menuExpanded by remember(room.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (room.pinned) AppStrings.ui_unpin else AppStrings.ui_pin_top) },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePinned()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.PushPin, contentDescription = null)
                                }
                            )
                            if (isConnectedRoom) {
                                DropdownMenuItem(
                                    text = { Text(AppStrings.ui_edit) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(AppStrings.ui_equipment) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenDevices()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Devices, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(AppStrings.ui_close) },
                                    onClick = {
                                        menuExpanded = false
                                        onDisconnect()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(AppStrings.ui_edit) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(AppStrings.ui_delete) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Delete, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun WebRtcRoomBatchBottomBar(
    hasSelection: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit
) {
    BottomAppBar(
        modifier = Modifier.fillMaxWidth()
    ) {
        WebRtcRoomBottomBarAction(
            label = AppStrings.ui_connect,
            icon = Icons.Default.Link,
            enabled = canConnect,
            onClick = onConnect
        )
        WebRtcRoomBottomBarAction(
            label = AppStrings.ui_pin_top,
            icon = Icons.Default.PushPin,
            enabled = hasSelection,
            onClick = onPin
        )
        WebRtcRoomBottomBarAction(
            label = AppStrings.ui_unpin,
            icon = Icons.Default.PushPin,
            enabled = hasSelection,
            onClick = onUnpin
        )
        WebRtcRoomBottomBarAction(
            label = AppStrings.ui_delete,
            icon = Icons.Default.Delete,
            enabled = hasSelection,
            isDestructive = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun RowScope.WebRtcRoomBottomBarAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (isDestructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        ),
        modifier = Modifier.weight(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, contentDescription = null)
            Text(label, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebRtcRoomFilterSheet(
    sheetState: SheetState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    connectionFilter: WebRtcRoomConnectionFilter,
    onConnectionFilterChange: (WebRtcRoomConnectionFilter) -> Unit,
    pinFilter: WebRtcRoomPinFilter,
    onPinFilterChange: (WebRtcRoomPinFilter) -> Unit,
    sourceFilter: WebRtcRoomSourceFilter,
    onSourceFilterChange: (WebRtcRoomSourceFilter) -> Unit,
    activeFilterCount: Int,
    onApply: () -> Unit,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        FilterSheetFrame(
            activeFilterCount = activeFilterCount,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            searchPlaceholder = AppStrings.ui_search_room_name_websocket_address_room_id_source,
            onReset = onReset,
            onApply = onApply
        ) {
            FilterSectionCard(
                title = AppStrings.ui_source,
                icon = Icons.Default.Info
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebRtcRoomSourceFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = sourceFilter == option,
                            label = option.label,
                            onClick = { onSourceFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_connection_status,
                icon = Icons.Default.Link
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebRtcRoomConnectionFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = connectionFilter == option,
                            label = option.label,
                            onClick = { onConnectionFilterChange(option) }
                        )
                    }
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_pinned_status,
                icon = Icons.Default.PushPin
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebRtcRoomPinFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = pinFilter == option,
                            label = option.label,
                            onClick = { onPinFilterChange(option) }
                        )
                    }
                }
            }
        }
    }
}

private enum class WebRtcRoomConnectionFilter(val label: String) {
    All(AppStrings.ui_all),
    Connected(AppStrings.ui_connected),
    Unconnected(AppStrings.ui_not_connected)
}

private enum class WebRtcRoomPinFilter(val label: String) {
    All(AppStrings.ui_all),
    Pinned(AppStrings.ui_pinned),
    Unpinned(AppStrings.ui_not_pinned)
}

internal enum class WebRtcRoomSourceFilter(val label: String) {
    All(AppStrings.ui_all),
    Other(WebRtcRoomSource.Other.label),
    Official(WebRtcRoomSource.Official.label);

    fun matches(source: WebRtcRoomSource): Boolean = when (this) {
        All -> true
        Other -> source == WebRtcRoomSource.Other
        Official -> source == WebRtcRoomSource.Official
    }
}

internal fun webRtcRoomMatchesSearch(
    name: String,
    wssUrl: String,
    roomId: String,
    source: WebRtcRoomSource,
    keyword: String
): Boolean {
    val normalizedKeyword = keyword.trim()
    return normalizedKeyword.isBlank() || name.contains(normalizedKeyword, ignoreCase = true) ||
            wssUrl.contains(normalizedKeyword, ignoreCase = true) ||
            roomId.contains(normalizedKeyword, ignoreCase = true) ||
            source.label.contains(normalizedKeyword, ignoreCase = true) ||
            source.name.contains(normalizedKeyword, ignoreCase = true)
}

private fun WebRtcRoomProfile.matchesConnectedState(
    activeConfig: WebRtcConfig?,
    connectionStatus: WebRtcConnectionStatus
): Boolean {
    return matchesActiveConfig(activeConfig) && connectionStatus.isBusy()
}
