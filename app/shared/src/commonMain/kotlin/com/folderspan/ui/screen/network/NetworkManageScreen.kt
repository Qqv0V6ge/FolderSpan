package com.folderspan.ui.screen.network

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.createSettings
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.data.main.network.NetworkShare
import com.folderspan.ui.components.dialog.*
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.SettingsUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val NetworkEntryIdSetSaver = listSaver<Set<Long>, Long>(
    save = { ids -> ids.toList() },
    restore = { values -> values.toSet() }
)

class NetworkManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val networkState = koinInject<NetworkState>()
        val fileState = koinInject<FileState>()
        val scope = rememberCoroutineScope()
        val colorScheme = MaterialTheme.colorScheme
        val snackbarHostState = remember { SnackbarHostState() }
        val settings = remember { createSettings() }
        val filterSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedEntryIds by rememberSaveable(stateSaver = NetworkEntryIdSetSaver) {
            mutableStateOf(emptySet())
        }
        var filterDialogOpen by rememberSaveable { mutableStateOf(false) }
        var filterQuery by rememberSaveable { mutableStateOf("") }
        var filterQueryDraft by rememberSaveable { mutableStateOf("") }
        var filterByName by rememberSaveable { mutableStateOf(true) }
        var filterByHost by rememberSaveable { mutableStateOf(true) }
        var protocolFilter by rememberSaveable {
            val stored = settings.getString(
                SettingsUtils.KEY_NETWORK_FILTER_PROTOCOL,
                NetworkProtocolFilter.All.name
            )
            mutableStateOf(
                runCatching { NetworkProtocolFilter.valueOf(stored) }
                    .getOrDefault(NetworkProtocolFilter.All)
            )
        }
        var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var sortOption by rememberSaveable { mutableStateOf(NetworkSortOption.Name) }
        var sortAscending by rememberSaveable { mutableStateOf(true) }
        var pendingConnect by remember { mutableStateOf<NetworkEntry?>(null) }
        var pendingDelete by remember { mutableStateOf<NetworkEntry?>(null) }
        var pendingBatchConnect by rememberSaveable { mutableStateOf(false) }
        var pendingBatchDelete by rememberSaveable { mutableStateOf(false) }
        var pendingBatchPin by rememberSaveable { mutableStateOf(false) }
        val normalizedQuery = remember(filterQuery) { filterQuery.trim() }
        val activeFilterCount = remember(normalizedQuery, protocolFilter, filterByName, filterByHost) {
            (if (normalizedQuery.isNotBlank()) 1 else 0) +
                (if (protocolFilter != NetworkProtocolFilter.All) 1 else 0) +
                (if (filterByName && filterByHost) 0 else 1)
        }
        val hasActiveFilter = activeFilterCount > 0
        val filteredEntries by remember {
            derivedStateOf {
                networkState.entries.filter { entry ->
                    val protocolMatch = when (protocolFilter) {
                        NetworkProtocolFilter.All -> true
                        else -> entry.network.protocol == protocolFilter.protocol
                    }
                    if (normalizedQuery.isBlank()) {
                        protocolMatch
                    } else {
                        val nameMatch = filterByName &&
                            entry.network.name.contains(normalizedQuery, ignoreCase = true)
                        val hostMatch = filterByHost &&
                            entry.network.host.contains(normalizedQuery, ignoreCase = true)
                        protocolMatch && (nameMatch || hostMatch)
                    }
                }
            }
        }
        val entries by remember {
            derivedStateOf {
                filteredEntries.sortedWith(
                    compareByDescending<NetworkEntry> { it.network.pinned }.then(
                        if (sortAscending) {
                            when (sortOption) {
                                NetworkSortOption.Name -> compareBy { it.network.name.lowercase() }
                                NetworkSortOption.Host -> compareBy { it.network.host.lowercase() }
                                NetworkSortOption.Protocol -> compareBy { it.network.protocol.lowercase() }
                            }
                        } else {
                            when (sortOption) {
                                NetworkSortOption.Name -> compareByDescending { it.network.name.lowercase() }
                                NetworkSortOption.Host -> compareByDescending { it.network.host.lowercase() }
                                NetworkSortOption.Protocol -> compareByDescending { it.network.protocol.lowercase() }
                            }
                        }
                    )
                )
            }
        }
        LaunchedEffect(entries.size, selectionMode) {
            if (selectionMode && entries.isEmpty()) {
                selectionMode = false
                selectedEntryIds = emptySet()
            }
        }
        val selectedEntries by remember {
            derivedStateOf { entries.filter { item -> item.id in selectedEntryIds } }
        }
        val connectedIds by remember {
            derivedStateOf { networkState.connectedEntries.map { item -> item.id }.toSet() }
        }
        val showBatchActions by remember { derivedStateOf { selectionMode && selectedEntryIds.isNotEmpty() } }
        val showAddAction by remember { derivedStateOf { !selectionMode } }
        fun disconnectEntry(entry: NetworkEntry) {
            val network = entry.network
            network.disconnect()
            networkState.connectedEntries.removeAll { item -> item.id == entry.id }
            val currentDesk = fileState.deskType.value
            if (currentDesk is Network && currentDesk == entry.network) {
                fileState.updateDesk(FileProtocol.Local, Local())
            }
        }
        fun togglePinned(entry: NetworkEntry) {
            val network = entry.network
            val updated = copyNetworkWithPinned(network, !network.pinned)
            scope.launch {
                networkState.updateEntry(entry, updated)
            }
        }
        fun updateProtocolFilter(value: NetworkProtocolFilter) {
            protocolFilter = value
            settings.putString(SettingsUtils.KEY_NETWORK_FILTER_PROTOCOL, value.name)
            SettingsUtils.notifySettingChanged(SettingsUtils.KEY_NETWORK_FILTER_PROTOCOL)
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectionMode) {
                            Text(AppStrings.ui_arg0_items_selected.format(arg0 = (selectedEntryIds.size).toString()))
                        } else {
                            Text(AppStrings.ui_network_management)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedEntryIds = emptySet()
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
                                        contentDescription = AppStrings.ui_search_filter_network_currently_arg0_conditions.format(arg0 = (activeFilterCount).toString())
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    filterQueryDraft = filterQuery
                                    filterDialogOpen = true
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_filter_web)
                                }
                            }
                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) {
                                    Icon(Icons.AutoMirrored.Default.Sort, contentDescription = AppStrings.ui_sort)
                                }
                                DropdownMenu(
                                    expanded = sortMenuExpanded,
                                    onDismissRequest = { sortMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(AppStrings.ui_name) },
                                        onClick = {
                                            val isCurrent = sortOption == NetworkSortOption.Name
                                            sortOption = NetworkSortOption.Name
                                            sortAscending = !isCurrent || !sortAscending
                                        },
                                        trailingIcon = {
                                            if (sortOption == NetworkSortOption.Name) {
                                                val modifier =
                                                    if (sortAscending) Modifier.rotate(270f) else Modifier.rotate(90f)
                                                Icon(Icons.Default.SwitchLeft, null, modifier)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(AppStrings.network_host_label) },
                                        onClick = {
                                            val isCurrent = sortOption == NetworkSortOption.Host
                                            sortOption = NetworkSortOption.Host
                                            sortAscending = !isCurrent || !sortAscending
                                        },
                                        trailingIcon = {
                                            if (sortOption == NetworkSortOption.Host) {
                                                val modifier =
                                                    if (sortAscending) Modifier.rotate(270f) else Modifier.rotate(90f)
                                                Icon(Icons.Default.SwitchLeft, null, modifier)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(AppStrings.ui_agreement) },
                                        onClick = {
                                            val isCurrent = sortOption == NetworkSortOption.Protocol
                                            sortOption = NetworkSortOption.Protocol
                                            sortAscending = !isCurrent || !sortAscending
                                        },
                                        trailingIcon = {
                                            if (sortOption == NetworkSortOption.Protocol) {
                                                val modifier =
                                                    if (sortAscending) Modifier.rotate(270f) else Modifier.rotate(90f)
                                                Icon(Icons.Default.SwitchLeft, null, modifier)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (!selectionMode && entries.isNotEmpty()) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                        if (selectionMode && entries.isNotEmpty()) {
                            TextButton(onClick = {
                                selectedEntryIds = if (selectedEntryIds.size == entries.size) {
                                    emptySet()
                                } else {
                                    entries.map { item -> item.id }.toSet()
                                }
                            }) {
                                Text(
                                    if (selectedEntryIds.size == entries.size) {
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                when {
                    showBatchActions -> BatchActionsFab(
                        colorScheme = colorScheme,
                        onBatchConnect = { pendingBatchConnect = true },
                        onBatchPin = { pendingBatchPin = true },
                        onBatchDelete = { pendingBatchDelete = true }
                    )
                    showAddAction -> AddEntryFab(onClick = { navigator.push(NetworkAddEntryScreen()) })
                }
            }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                isEmpty = entries.isEmpty(),
                floatingActionButtonPadding = if (showBatchActions || showAddAction) GridListFabPadding else 0.dp
            ) {
                items(
                    items = entries,
                    key = { entry -> entry.id }
                ) { entry ->
                    NetworkItemListContent(
                        entry = entry,
                        isSelected = entry.id in selectedEntryIds,
                        selectionMode = selectionMode,
                        onSelectionToggle = {
                            selectedEntryIds = if (entry.id in selectedEntryIds) {
                                selectedEntryIds - entry.id
                            } else {
                                selectedEntryIds + entry.id
                            }
                        },
                        isConnected = entry.id in connectedIds,
                        onEdit = { navigator.push(NetworkEditScreen(entry)) },
                        onDuplicate = {
                            scope.launch {
                                val copied = duplicateNetwork(entry.network)
                                networkState.addNetwork(copied, entry.isPersisted)
                                snackbarHostState.showSnackbar(AppStrings.ui_arg0_copied.format(arg0 = copied.name))
                            }
                        },
                        onDelete = { pendingDelete = entry },
                        onClick = {
                            val isConnected = entry.id in connectedIds
                            if (isConnected) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = AppStrings.ui_connected_arg0.format(arg0 = entry.network.name),
                                        actionLabel = AppStrings.ui_close
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        disconnectEntry(entry)
                                    }
                                }
                            } else {
                                pendingConnect = entry
                            }
                        },
                        onDisconnect = { disconnectEntry(entry) },
                        onTogglePinned = { togglePinned(entry) },
                        onPersist = {
                            if (!entry.isPersisted) {
                                scope.launch {
                                    networkState.updatePersisted(entry, true)
                                    snackbarHostState.showSnackbar(AppStrings.ui_saved_arg0.format(arg0 = entry.network.name))
                                }
                            }
                        }
                    )
                }
            }
        }

        pendingConnect?.let { entry ->
            NetworkConnectDialog(
                name = entry.network.name,
                onDismissRequest = { pendingConnect = null },
                onConfirm = {
                    pendingConnect = null
                    networkState.connectEntry(entry)
                }
            )
        }

        pendingDelete?.let { entry ->
            NetworkDeleteDialog(
                name = entry.network.name,
                onDismissRequest = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    scope.launch {
                        networkState.removeEntry(entry)
                        val currentDesk = fileState.deskType.value
                        if (currentDesk is Network && currentDesk == entry.network) {
                            fileState.updateDesk(FileProtocol.Local, Local())
                        }
                        snackbarHostState.showSnackbar(AppStrings.ui_arg0_deleted.format(arg0 = entry.network.name))
                    }
                }
            )
        }

        if (pendingBatchConnect) {
            NetworkBatchConnectDialog(
                count = selectedEntries.size,
                onDismissRequest = { pendingBatchConnect = false },
                onConfirm = {
                    pendingBatchConnect = false
                    selectedEntries.forEach { entry ->
                        networkState.connectEntry(entry)
                    }
                    selectedEntryIds = emptySet()
                    selectionMode = false
                    navigator.pop()
                }
            )
        }

        if (pendingBatchDelete) {
            NetworkBatchDeleteDialog(
                count = selectedEntries.size,
                onDismissRequest = { pendingBatchDelete = false },
                onConfirm = {
                    pendingBatchDelete = false
                    scope.launch {
                        selectedEntries.forEach { entry ->
                            networkState.removeEntry(entry)
                        }
                        val currentDesk = fileState.deskType.value
                        if (currentDesk is Network) {
                            val isCurrentRemoved = selectedEntries.any { entry -> entry.network == currentDesk }
                            if (isCurrentRemoved) {
                                fileState.updateDesk(FileProtocol.Local, Local())
                            }
                        }
                        selectedEntryIds = emptySet()
                        selectionMode = false
                        snackbarHostState.showSnackbar(AppStrings.ui_arg0_items_deleted.format(arg0 = (selectedEntries.size).toString()))
                    }
                }
            )
        }

        if (pendingBatchPin) {
            NetworkBatchPinDialog(
                count = selectedEntries.size,
                onDismissRequest = { pendingBatchPin = false },
                onConfirm = {
                    pendingBatchPin = false
                    scope.launch {
                        selectedEntries.forEach { entry ->
                            val network = entry.network
                            if (!network.pinned) {
                                val updated = copyNetworkWithPinned(network, true)
                                networkState.updateEntry(entry, updated)
                            }
                        }
                        selectedEntryIds = emptySet()
                        selectionMode = false
                        snackbarHostState.showSnackbar(AppStrings.ui_pinned_arg0_items.format(arg0 = (selectedEntries.size).toString()))
                    }
                }
            )
        }

        if (filterDialogOpen) {
            NetworkFilterSheet(
                sheetState = filterSheetState,
                filterQuery = filterQueryDraft,
                onFilterQueryChange = { filterQueryDraft = it },
                filterByName = filterByName,
                onFilterByNameChange = { filterByName = it },
                filterByHost = filterByHost,
                onFilterByHostChange = { filterByHost = it },
                protocolFilter = protocolFilter,
                onProtocolFilterChange = { updateProtocolFilter(it) },
                activeFilterCount = activeFilterCount,
                onApply = {
                    filterQuery = filterQueryDraft.trim()
                    filterDialogOpen = false
                },
                onDismissRequest = { filterDialogOpen = false },
                onReset = {
                    filterQuery = ""
                    filterQueryDraft = ""
                    filterByName = true
                    filterByHost = true
                    updateProtocolFilter(NetworkProtocolFilter.All)
                }
            )
        }
    }
}

@Composable
internal fun NetworkItemListContent(
    entry: NetworkEntry,
    isSelected: Boolean,
    selectionMode: Boolean,
    onSelectionToggle: () -> Unit,
    isConnected: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
    onTogglePinned: () -> Unit,
    onPersist: () -> Unit
) {
    val network = entry.network
    val protocolLabel = formatProtocolLabel(network)
    val hostLine = if (network.username.isBlank()) {
        network.host
    } else {
        "${network.username}@${network.host}"
    }
    val colorScheme = MaterialTheme.colorScheme

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = when {
                isSelected || network.pinned -> colorScheme.secondaryContainer
                else -> colorScheme.surface
            },
            headlineColor = when {
                isSelected || network.pinned -> colorScheme.onSecondaryContainer
                else -> colorScheme.onSurface
            },
            supportingColor = when {
                isSelected || network.pinned -> colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                else -> colorScheme.outline
            }
        ),
        leadingContent = if (selectionMode) {
            {
                IconButton(onClick = onSelectionToggle) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_network_device
                    )
                }
            }
        } else {
            null
        },
        overlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = protocolLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall
                )
                if (network.pinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = AppStrings.ui_pin_top,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (!entry.isPersisted) {
                    Text(
                        text = AppStrings.ui_not_saved,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.tertiary
                    )
                }
            }
        },
        headlineContent = { Text(network.name) },
        supportingContent = {
            Text(
                text = hostLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = if (!selectionMode) {
            {
                var menuExpanded by remember(entry.id) { mutableStateOf(false) }
                Row {
                    if (isConnected) {
                        IconButton(
                            onClick = onDisconnect,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = AppStrings.ui_disconnect)
                        }
                    }
                    if (!entry.isPersisted) {
                        IconButton(
                            onClick = onPersist,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Save, contentDescription = AppStrings.ui_save)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (network.pinned) AppStrings.ui_unpin else AppStrings.ui_pin_top) },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePinned()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.PushPin, contentDescription = null)
                                }
                            )
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
                                text = { Text(AppStrings.ui_copy) },
                                onClick = {
                                    menuExpanded = false
                                    onDuplicate()
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FileCopy, contentDescription = null)
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
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onSelectionToggle else onClick)
    )
}

private fun formatProtocolLabel(network: Network): String {
    return if (network is NetworkShare) AppStrings.ui_link_sharing else network.protocol
}

private fun duplicateNetwork(network: Network): Network {
    val newName = AppStrings.ui_arg0_copy.format(arg0 = network.name)
    val separator = network.pathSeparator.ifBlank {
        if (network.protocol == NetworkProtocol.SMB.name) "\\" else "/"
    }
    return if (network is NetworkShare) {
        NetworkShare(
            name = newName,
            baseUrl = network.host,
            password = network.password,
            pathSeparator = separator,
            username = network.username,
            pinned = network.pinned
        )
    } else {
        Network(
            name = newName,
            pathSeparator = separator,
            protocol = network.protocol,
            host = network.host,
            username = network.username,
            password = network.password,
            pinned = network.pinned,
            extras = network.extras
        )
    }
}

private fun copyNetworkWithPinned(network: Network, pinned: Boolean): Network {
    val separator = network.pathSeparator.ifBlank {
        if (network.protocol == NetworkProtocol.SMB.name) "\\" else "/"
    }
    return if (network is NetworkShare) {
        NetworkShare(
            name = network.name,
            baseUrl = network.host,
            password = network.password,
            pathSeparator = separator,
            username = network.username,
            pinned = pinned
        )
    } else {
        Network(
            name = network.name,
            pathSeparator = separator,
            protocol = network.protocol,
            host = network.host,
            username = network.username,
            password = network.password,
            pinned = pinned,
            extras = network.extras
        )
    }
}

private enum class NetworkProtocolFilter(val label: String, val protocol: String?) {
    All(AppStrings.ui_all, null),
    FTP("FTP", NetworkProtocol.FTP.name),
    SFTP("SFTP", NetworkProtocol.SFTP.name),
    SMB("SMB", NetworkProtocol.SMB.name),
    S3("S3", NetworkProtocol.S3.name),
    WebDav("WebDav", NetworkProtocol.WebDav.name),
    LinkShare("LinkShare", NetworkShare.PROTOCOL)
}

private enum class NetworkSortOption {
    Name,
    Host,
    Protocol
}

@Composable
private fun BatchActionsFab(
    colorScheme: ColorScheme,
    onBatchConnect: () -> Unit,
    onBatchPin: () -> Unit,
    onBatchDelete: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(8.dp)
    ) {
        FloatingActionButton(
            onClick = onBatchConnect,
            containerColor = colorScheme.primary
        ) {
            Icon(Icons.Default.Link, AppStrings.ui_batch_connection)
        }
        FloatingActionButton(
            onClick = onBatchPin,
            containerColor = colorScheme.secondary
        ) {
            Icon(Icons.Default.PushPin, AppStrings.ui_batch_pinned_top)
        }
        ExtendedFloatingActionButton(
            onClick = onBatchDelete,
            containerColor = colorScheme.error,
            icon = { Icon(Icons.Default.Delete, AppStrings.ui_batch_delete) },
            text = { Text(AppStrings.ui_batch_delete) }
        )
    }
}

@Composable
private fun AddEntryFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(AppStrings.ui_create_new_action) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkFilterSheet(
    sheetState: SheetState,
    filterQuery: String,
    onFilterQueryChange: (String) -> Unit,
    filterByName: Boolean,
    onFilterByNameChange: (Boolean) -> Unit,
    filterByHost: Boolean,
    onFilterByHostChange: (Boolean) -> Unit,
    protocolFilter: NetworkProtocolFilter,
    onProtocolFilterChange: (NetworkProtocolFilter) -> Unit,
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
            searchQuery = filterQuery,
            onSearchQueryChange = onFilterQueryChange,
            searchPlaceholder = AppStrings.ui_search_name_host,
            onReset = onReset,
            onApply = onApply
        ) {
            FilterSectionCard(
                title = AppStrings.ui_match_field,
                icon = Icons.Default.Search
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterOptionChip(
                        selected = filterByName,
                        label = AppStrings.ui_name,
                        onClick = {
                            if (filterByName && !filterByHost) return@FilterOptionChip
                            onFilterByNameChange(!filterByName)
                        }
                    )
                    FilterOptionChip(
                        selected = filterByHost,
                        label = AppStrings.network_host_label,
                        onClick = {
                            if (filterByHost && !filterByName) return@FilterOptionChip
                            onFilterByHostChange(!filterByHost)
                        }
                    )
                }
            }

            FilterSectionCard(
                title = AppStrings.ui_agreement,
                icon = Icons.Default.Language
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NetworkProtocolFilter.entries.forEach { option ->
                        FilterOptionChip(
                            selected = protocolFilter == option,
                            label = option.label,
                            onClick = { onProtocolFilterChange(option) }
                        )
                    }
                }
            }
        }
    }
}
