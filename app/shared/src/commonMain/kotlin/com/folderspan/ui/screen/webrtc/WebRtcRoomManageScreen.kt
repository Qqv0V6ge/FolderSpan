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
import com.folderspan.isProAuthenticated
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.toRoomDisplayLabel
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.WebRtcRoomState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val WebRtcRoomKeySetSaver = listSaver<Set<String>, String>(
    save = { keys -> keys.toList() },
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
        var selectedSource by rememberSaveable { mutableStateOf(WebRtcRoomSource.Other) }
        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedRoomKeys by rememberSaveable(stateSaver = WebRtcRoomKeySetSaver) {
            mutableStateOf(emptySet())
        }

        val loggedIn = isProAuthenticated()
        val sourceOptions = webRtcRoomSourceOptions(canUseOfficialGateway = loggedIn)
        val visibleSource = resolveWebRtcRoomSource(
            source = selectedSource,
            canUseOfficialGateway = loggedIn,
        )

        val keyword = searchQuery.trim()
        val activeFilterCount =
            (if (searchQuery.isNotBlank()) 1 else 0) +
                (if (connectionFilter != WebRtcRoomConnectionFilter.All) 1 else 0) +
                (if (visibleSource == WebRtcRoomSource.Other && pinFilter != WebRtcRoomPinFilter.All) 1 else 0)
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
            val matchesPin = visibleSource == WebRtcRoomSource.Official || when (pinFilter) {
                WebRtcRoomPinFilter.All -> true
                WebRtcRoomPinFilter.Pinned -> room.pinned
                WebRtcRoomPinFilter.Unpinned -> !room.pinned
            }
            val matchesSource = webRtcRoomMatchesSelectedSource(room.source, visibleSource)
            matchesSearch && matchesConnection && matchesPin && matchesSource
        }
        val filteredRoomKeys = filteredRooms.map { room -> room.catalogKey }.toSet()
        val selectedRooms = filteredRooms.filter { room -> room.catalogKey in selectedRoomKeys }
        val allVisibleSelected = filteredRoomKeys.isNotEmpty() && selectedRoomKeys.size == filteredRoomKeys.size

        fun openFilterSheet() {
            searchDraft = searchQuery
            connectionFilterDraft = connectionFilter
            pinFilterDraft = pinFilter
            showFilterSheet = true
        }

        fun clearSelection() {
            selectionMode = false
            selectedRoomKeys = emptySet()
        }

        fun toggleSelection(catalogKey: String) {
            val updatedSelection = if (catalogKey in selectedRoomKeys) {
                selectedRoomKeys - catalogKey
            } else {
                selectedRoomKeys + catalogKey
            }
            selectedRoomKeys = updatedSelection
        }

        fun resetFilters() {
            searchQuery = ""
            searchDraft = ""
            connectionFilter = WebRtcRoomConnectionFilter.All
            connectionFilterDraft = WebRtcRoomConnectionFilter.All
            pinFilter = WebRtcRoomPinFilter.All
            pinFilterDraft = WebRtcRoomPinFilter.All
        }

        suspend fun performBatchPinned(pinned: Boolean) {
            val updated = roomState.setPinned(selectedRooms.map { room -> room.id }, pinned)
            clearSelection()
            snackbarHostState.showLatestSnackbar(
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
            val deleted = roomState.deleteRooms(roomsToDelete)
            clearSelection()
            snackbarHostState.showLatestSnackbar(
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
            roomState.deleteRoom(room)
            snackbarHostState.showLatestSnackbar(AppStrings.ui_arg0_deleted.format(arg0 = room.name))
        }

        LaunchedEffect(Unit) {
            roomState.loadPersisted()
        }

        LaunchedEffect(loggedIn, visibleSource) {
            if (!loggedIn && selectedSource == WebRtcRoomSource.Official) {
                selectedSource = WebRtcRoomSource.Other
                return@LaunchedEffect
            }
            if (visibleSource == WebRtcRoomSource.Official) {
                roomState.loadOfficialRooms(reset = true)
            }
        }

        LaunchedEffect(filteredRoomKeys, selectionMode) {
            if (!selectionMode) return@LaunchedEffect
            val previousSelection = selectedRoomKeys
            val updatedSelection = previousSelection.filter { key -> key in filteredRoomKeys }.toSet()
            if (updatedSelection != previousSelection) {
                selectedRoomKeys = updatedSelection
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
                                AppStrings.ui_arg0_items_selected.format(arg0 = (selectedRoomKeys.size).toString())
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
                                        selectedRoomKeys = filteredRoomKeys
                                    }
                                },
                                enabled = filteredRoomKeys.isNotEmpty()
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
                        canPin = visibleSource == WebRtcRoomSource.Other,
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
                                    val snackbarResult = snackbarHostState.showLatestSnackbar(
                                        message = webRtcRoomBatchDeleteConfirmMessage(roomsToDelete.size),
                                        actionLabel = WebRtcRoomDeleteActionLabel,
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Short,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (webRtcRoomShowsSourceSelector(sourceOptions)) {
                    WebRtcRoomSourceSelector(
                        source = visibleSource,
                        sourceOptions = sourceOptions,
                        onSourceChange = { selectedSource = it },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                GridList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    isLoading = visibleSource == WebRtcRoomSource.Official && roomState.officialIsLoading,
                    errorState = if (visibleSource == WebRtcRoomSource.Official && filteredRooms.isEmpty()) {
                        roomState.officialError
                    } else {
                        null
                    },
                    isEmpty = filteredRooms.isEmpty(),
                    refreshState = if (visibleSource == WebRtcRoomSource.Official) {
                        roomState.officialRefreshState
                    } else {
                        PageRefreshState.Idle
                    },
                    appendState = if (visibleSource == WebRtcRoomSource.Official) {
                        roomState.officialAppendState
                    } else {
                        PageAppendState.Idle
                    },
                    onRefresh = if (visibleSource == WebRtcRoomSource.Official) {
                        { scope.launch { roomState.loadOfficialRooms(reset = true) } }
                    } else {
                        null
                    },
                    onRetry = if (visibleSource == WebRtcRoomSource.Official) {
                        { scope.launch { roomState.loadOfficialRooms(reset = true) } }
                    } else {
                        null
                    },
                    onLoadMore = if (visibleSource == WebRtcRoomSource.Official && roomState.officialHasMore) {
                        { scope.launch { roomState.loadOfficialRooms(reset = false) } }
                    } else {
                        null
                    },
                    floatingActionButtonPadding = if (!selectionMode) GridListFabPadding else 0.dp
                ) {
                    items(
                        count = filteredRooms.size,
                        key = { index -> filteredRooms[index].catalogKey }
                    ) { index ->
                    val room = filteredRooms[index]
                    val isActiveRoom = room.matchesActiveConfig(activeConfig)
                    val isConnectedRoom = isActiveRoom && connectionStatus.isBusy()
                    WebRtcRoomListContent(
                        room = room,
                        isSelected = room.catalogKey in selectedRoomKeys,
                        selectionMode = selectionMode,
                        connectionStatus = connectionStatus,
                        isActiveRoom = isActiveRoom,
                        isConnectedRoom = isConnectedRoom,
                        deviceCount = if (isActiveRoom) peerSessionStates.size else 0,
                        lastError = if (isActiveRoom) lastError else null,
                        onSelectionToggle = { toggleSelection(room.catalogKey) },
                        onEdit = { navigator.push(WebRtcRoomEditScreen(room)) },
                        onDelete = {
                            scope.launch {
                                val snackbarResult = snackbarHostState.showLatestSnackbar(
                                    message = webRtcRoomDeleteConfirmMessage(room.name),
                                    actionLabel = WebRtcRoomDeleteActionLabel,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Short,
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
                                toggleSelection(room.catalogKey)
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
                showPinFilter = visibleSource == WebRtcRoomSource.Other,
                activeFilterCount = activeFilterCount,
                onApply = {
                    searchQuery = searchDraft.trim()
                    connectionFilter = connectionFilterDraft
                    pinFilter = pinFilterDraft
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
                        webRtcRoomConnectConfirmMessage(
                            name = room.name,
                            wssUrl = room.wssUrl,
                            roomId = room.roomId,
                            source = room.source,
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = room
                            val shouldClearSelection = selectionMode
                            pendingConnectRoom = null
                            if (shouldClearSelection) {
                                clearSelection()
                            }
                            scope.launch {
                                val liveRoom = if (target.source == WebRtcRoomSource.Official) {
                                    roomState.getOfficialRoom(target.roomId)
                                } else {
                                    target
                                }
                                if (liveRoom == null) {
                                    snackbarHostState.showLatestSnackbar(
                                        AppStrings.ui_failed_load_webrtc_room_arg0.format(
                                            arg0 = AppStrings.ui_operation_failed_please_try_again_later,
                                        )
                                    )
                                } else {
                                    deviceState.connectWebRtcRoom(liveRoom)
                                }
                            }
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
    val supportText = webRtcRoomListSupportText(
        source = room.source,
        wssUrl = room.wssUrl,
        lastError = lastError,
    )

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
        supportingContent = if (supportText.isBlank()) {
            null
        } else {
            {
                Text(
                    text = supportText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = if (!selectionMode) {
            {
                var menuExpanded by remember(room.catalogKey) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (room.source != WebRtcRoomSource.Official) {
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
                            }
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
    canPin: Boolean,
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
        if (canPin) {
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
        }
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
    showPinFilter: Boolean,
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

            if (showPinFilter) {
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

internal fun webRtcRoomMatchesSelectedSource(
    roomSource: WebRtcRoomSource,
    selectedSource: WebRtcRoomSource,
): Boolean = roomSource == selectedSource

internal fun webRtcRoomMatchesSearch(
    name: String,
    wssUrl: String,
    roomId: String,
    source: WebRtcRoomSource,
    keyword: String
): Boolean {
    val normalizedKeyword = keyword.trim()
    if (normalizedKeyword.isBlank()) return true
    val matchesConnectionAddress = webRtcRoomShowsConnectionFields(source) &&
        wssUrl.contains(normalizedKeyword, ignoreCase = true)
    return name.contains(normalizedKeyword, ignoreCase = true) ||
        matchesConnectionAddress ||
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
