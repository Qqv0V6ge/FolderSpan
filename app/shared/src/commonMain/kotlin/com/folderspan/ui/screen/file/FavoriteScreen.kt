package com.folderspan.ui.screen.file

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.db.FileFavorite
import com.folderspan.extensions.replaceLast
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.dialog.FavoritePickerDialog
import com.folderspan.ui.components.file.FileFavoriteCard
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileFavoriteState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

class FavoriteScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val scope = rememberCoroutineScope()

        val fileFavoriteState = koinInject<FileFavoriteState>()
        LaunchedEffect(Unit) {
            fileFavoriteState.sync()
        }

        val fileState = koinInject<FileState>()
        val deskType by fileState.deskType.collectAsState()
        val currentPath by fileState.path.collectAsState()
        val deviceState = koinInject<DeviceState>()
        val networkState = koinInject<NetworkState>()

        var searchText by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }
        var showAddDialog by remember { mutableStateOf(false) }
        var showFilterSheet by remember { mutableStateOf(false) }
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedIds = remember { mutableStateListOf<Long>() }
        val selectedProtocols = remember { mutableStateListOf<FileProtocol>() }
        var entryTypeFilter by rememberSaveable { mutableStateOf(EntryTypeFilter.All) }
        var pinFilter by rememberSaveable { mutableStateOf(FavoritePinFilter.All) }

        val snackbarHostState = remember { SnackbarHostState() }

        fun resetFilter() {
            selectedProtocols.clear()
            entryTypeFilter = EntryTypeFilter.All
            pinFilter = FavoritePinFilter.All
            searchText = ""
            searchDraft = ""
        }

        fun matchesEntryType(favorite: FileFavorite): Boolean {
            return when (entryTypeFilter) {
                EntryTypeFilter.All -> true
                EntryTypeFilter.FileOnly -> !favorite.isDirectory
                EntryTypeFilter.FolderOnly -> favorite.isDirectory
            }
        }

        fun matchesPin(favorite: FileFavorite): Boolean {
            return when (pinFilter) {
                FavoritePinFilter.All -> true
                FavoritePinFilter.Pinned -> favorite.isFixed
                FavoritePinFilter.Unpinned -> !favorite.isFixed
            }
        }

        val query = searchText.trim()
        val filteredFavorites = fileFavoriteState.favorites
            .asSequence()
            .filter { favorite -> favorite.protocol != FileProtocol.Share }
            .filter { favorite -> selectedProtocols.isEmpty() || favorite.protocol in selectedProtocols }
            .filter(::matchesEntryType)
            .filter(::matchesPin)
            .filter { favorite ->
                query.isEmpty() || favorite.name.contains(query, ignoreCase = true) ||
                        favorite.path.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<FileFavorite> { item -> item.isFixed }
                    .thenByDescending { item -> item.isDirectory }
                    .thenBy { item -> item.name.lowercase() }
            )
            .toList()
        val activeFilterCount =
            (if (selectedProtocols.isEmpty()) 0 else 1) +
                    (if (entryTypeFilter == EntryTypeFilter.All) 0 else 1) +
                    (if (pinFilter == FavoritePinFilter.All) 0 else 1) +
                    (if (query.isEmpty()) 0 else 1)
        val hasActiveFilter = activeFilterCount > 0

        fun clearSelection() {
            selectedIds.clear()
            isSelectionMode = false
        }

        fun toggleSelection(favorite: FileFavorite) {
            if (favorite.id in selectedIds) {
                selectedIds.remove(favorite.id)
                if (selectedIds.isEmpty()) {
                    isSelectionMode = false
                }
            } else {
                selectedIds.add(favorite.id)
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
            }
        }

        fun isSameDesk(target: DiskBase): Boolean {
            return when (val desk = fileState.deskType.value) {
                is Local if target is Local -> true
                is Device if target is Device -> desk.id == target.id
                is NetworkAccess if target is NetworkAccess -> desk.protocolId == target.protocolId
                else -> desk == target
            }
        }

        fun updateDeviceConnectType(deviceId: String, newConnectType: ConnectType) {
            deviceState.devices.removeAll { item -> item.id == deviceId }
            val updatedDevices = deviceState.socketDevices.map { item ->
                if (item.id == deviceId && item.transportType == DeviceTransportType.Session) {
                    val keepClient = newConnectType == ConnectType.Connect || newConnectType == ConnectType.Loading
                    item.withCopy(connectType = newConnectType, httpClient = if (keepClient) item.httpClient else null)
                } else {
                    item
                }
            }
            deviceState.socketDevices.clear()
            deviceState.socketDevices.addAll(updatedDevices)
        }

        suspend fun openEntry(
            protocol: FileProtocol,
            protocolId: String?,
            path: String,
            name: String,
            isDirectory: Boolean
        ) {
            val targetPath = if (isDirectory) {
                path
            } else {
                path.replaceLast(name, "")
            }

            suspend fun openOnDesk(targetDesk: DiskBase) {
                if (isSameDesk(targetDesk)) {
                    fileState.updatePath(targetPath)
                } else {
                    fileState.updateDesk(protocol, targetDesk, pathOverride = targetPath)
                }

                if (!isDirectory) {
                    withContext(Dispatchers.Default) {
                        FileUtils.openFile(FileAccessPermission.Allowed, path)
                    }
                }
                navigator.pop()
            }

            when (protocol) {
                FileProtocol.Local -> {
                    openOnDesk(Local())
                }

                FileProtocol.Device -> {
                    val resolvedId = protocolId.orEmpty()
                    if (resolvedId.isBlank()) {
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    val connected = deviceState.devices.firstOrNull { item -> item.id == resolvedId }
                    if (connected != null) {
                        openOnDesk(connected)
                        return
                    }
                    val socketDevice = deviceState.socketDevices.firstOrNull { item ->
                        item.id == resolvedId && item.transportType == DeviceTransportType.Session
                    }
                    if (socketDevice == null) {
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    updateDeviceConnectType(socketDevice.id, ConnectType.Loading)
                    val target = try {
                        deviceState.connect(socketDevice)
                        deviceState.devices.firstOrNull { item -> item.id == resolvedId }
                    } catch (_: Exception) {
                        updateDeviceConnectType(socketDevice.id, ConnectType.Fail)
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    if (target == null) {
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    openOnDesk(target)
                }

                FileProtocol.Share -> {
                    snackbarHostState.showLatestSnackbar(AppStrings.ui_collection_does_not_support_sharing_sources)
                    return
                }

                FileProtocol.Network -> {
                    val resolvedId = protocolId.orEmpty()
                    if (resolvedId.isBlank()) {
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    networkState.loadPersisted()
                    val connectedEntry =
                        networkState.connectedEntries.firstOrNull { item -> item.network.protocolId == resolvedId }
                    val entry = connectedEntry
                        ?: networkState.entries.firstOrNull { item -> item.network.protocolId == resolvedId }
                    if (entry == null) {
                        snackbarHostState.showLatestSnackbar(AppStrings.ui_target_unavailable)
                        return
                    }
                    if (connectedEntry == null) {
                        networkState.connectEntry(entry)
                    }
                    openOnDesk(entry.network)
                }
            }
        }

        fun performBatchPin() {
            val selectedSet = selectedIds.toSet()
            val selectedFavorites = fileFavoriteState.favorites.filter { item -> item.id in selectedSet }
            if (selectedFavorites.isEmpty()) {
                clearSelection()
                return
            }

            scope.launch {
                var updated = 0
                var skipped = 0
                selectedFavorites.forEach { favorite ->
                    if (!favorite.isFixed) {
                        fileFavoriteState.updateFixed(favorite)
                        updated++
                    } else {
                        skipped++
                    }
                }

                clearSelection()

                val message = when {
                    updated > 0 && skipped > 0 -> AppStrings.ui_arg0_items_pinned_arg1_items_pinned.format(arg0 = (updated).toString(), arg1 = (skipped).toString())
                    updated > 0 -> AppStrings.ui_pinned_arg0_items.format(arg0 = (updated).toString())
                    else -> AppStrings.ui_selected_items_pinned
                }
                snackbarHostState.showLatestSnackbar(message)
            }
        }

        fun confirmDelete(favorite: FileFavorite) {
            scope.launch {
                val result = snackbarHostState.showLatestSnackbar(
                    message = AppStrings.ui_delete_collection_arg0.format(arg0 = favorite.name),
                    actionLabel = AppStrings.ui_delete,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    fileFavoriteState.delete(favorite)
                }
            }
        }

        fun performBatchDelete() {
            val selectedSet = selectedIds.toSet()
            val selectedFavorites = fileFavoriteState.favorites.filter { item -> item.id in selectedSet }
            if (selectedFavorites.isEmpty()) {
                clearSelection()
                return
            }

            scope.launch {
                val result = snackbarHostState.showLatestSnackbar(
                    message = AppStrings.ui_delete_selected_arg0_items.format(arg0 = (selectedFavorites.size).toString()),
                    actionLabel = AppStrings.ui_delete,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )

                if (result != SnackbarResult.ActionPerformed) {
                    return@launch
                }

                selectedFavorites.forEach { favorite ->
                    fileFavoriteState.delete(favorite)
                }
                clearSelection()
            }
        }

        LaunchedEffect(filteredFavorites) {
            val availableIds = filteredFavorites.map { item -> item.id }.toSet()
            val removed = selectedIds.removeAll { item -> item !in availableIds }
            if (removed && selectedIds.isEmpty()) {
                clearSelection()
            }
        }
        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (isSelectionMode) {
                            Text(AppStrings.ui_selected_item_count_arg0.format(arg0 = (selectedIds.size).toString()))
                        } else {
                            Text(AppStrings.ui_collection)
                        }
                    },
                    navigationIcon = {
                        IconButton({
                            if (isSelectionMode) {
                                clearSelection()
                            } else {
                                navigator.pop()
                            }
                        }) {
                            Icon(
                                if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Default.ArrowBack,
                                null
                            )
                        }
                    },
                    actions = {
                        if (!isSelectionMode) {
                            if (hasActiveFilter) {
                                FilledTonalIconButton(onClick = {
                                    searchDraft = searchText
                                    showFilterSheet = true
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = AppStrings.ui_search_filter_collections_current_arg0_conditions.format(arg0 = (activeFilterCount).toString())
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    searchDraft = searchText
                                    showFilterSheet = true
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_filter_collections)
                                }
                            }
                            IconButton(
                                onClick = {
                                    selectedIds.clear()
                                    isSelectionMode = true
                                },
                                enabled = filteredFavorites.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Default.Checklist,
                                    contentDescription = AppStrings.ui_batch_operation,
                                )
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (isSelectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatingActionButton(onClick = { performBatchPin() }) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = AppStrings.ui_batch_pinned_top,
                            )
                        }
                        FloatingActionButton(
                            onClick = { performBatchDelete() },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = AppStrings.ui_batch_delete,
                            )
                        }
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { showAddDialog = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(AppStrings.ui_create_new_action) }
                    )
                }
            }
        ) { item ->
            GridList(
                modifier = Modifier.padding(item),
                isEmpty = filteredFavorites.isEmpty(),
                floatingActionButtonPadding = GridListFabPadding
            ) {
                items(
                    items = filteredFavorites,
                    key = { favorite -> favorite.id }
                ) { favorite ->
                    val isSelected = favorite.id in selectedIds
                    FileFavoriteCard(
                        favorite = favorite,
                        onClick = {
                            if (isSelectionMode) {
                                toggleSelection(favorite)
                            } else {
                                scope.launch {
                                    openEntry(
                                        protocol = favorite.protocol,
                                        protocolId = favorite.protocolId,
                                        path = favorite.path,
                                        name = favorite.name,
                                        isDirectory = favorite.isDirectory
                                    )
                                }
                            }
                        },
                        onFixed = {
                            scope.launch {
                                fileFavoriteState.updateFixed(favorite)
                            }
                        },
                        onRemove = { confirmDelete(favorite) },
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onToggleSelect = { toggleSelection(favorite) },
                    )
                }
            }

            if (showFilterSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false }
                ) {
                    FilterSheetFrame(
                        activeFilterCount = activeFilterCount,
                        searchQuery = searchDraft,
                        onSearchQueryChange = { value -> searchDraft = value },
                        searchPlaceholder = AppStrings.ui_search_name_path,
                        onReset = { resetFilter() },
                        onApply = {
                            searchText = searchDraft.trim()
                            showFilterSheet = false
                        }
                    ) {
                        FilterSectionCard(
                            title = AppStrings.ui_agreement,
                            icon = Icons.Default.Storage
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                supportedFilterProtocols.forEach { protocol ->
                                    FilterOptionChip(
                                        selected = protocol in selectedProtocols,
                                        label = protocol.filterLabel(),
                                        onClick = {
                                            if (protocol in selectedProtocols) {
                                                selectedProtocols.remove(protocol)
                                            } else {
                                                selectedProtocols.add(protocol)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        FilterSectionCard(
                            title = AppStrings.ui_file_type,
                            icon = Icons.Default.Folder
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EntryTypeFilter.entries.forEach { filter ->
                                    FilterOptionChip(
                                        selected = entryTypeFilter == filter,
                                        label = filter.label(),
                                        onClick = { entryTypeFilter = filter }
                                    )
                                }
                            }
                        }

                        FilterSectionCard(
                            title = AppStrings.ui_pinned_status,
                            icon = Icons.Default.PushPin
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FavoritePinFilter.entries.forEach { filter ->
                                    FilterOptionChip(
                                        selected = pinFilter == filter,
                                        label = filter.label(),
                                        onClick = { pinFilter = filter }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showAddDialog) {
                FavoritePickerDialog(
                    deskType = deskType,
                    openPath = currentPath,
                    onDismiss = { showAddDialog = false },
                    onConfirm = { selectedFiles ->
                        showAddDialog = false
                        if (selectedFiles.isEmpty()) return@FavoritePickerDialog
                        scope.launch {
                            fun buildFavoriteKey(
                                protocol: FileProtocol,
                                protocolId: String?,
                                path: String
                            ): String {
                                return "${protocol.name}:${protocolId.orEmpty()}:$path"
                            }

                            val existingKeys = fileFavoriteState.favorites.map { item ->
                                buildFavoriteKey(item.protocol, item.protocolId, item.path)
                            }.toSet()
                            val newFiles = selectedFiles.filter { item ->
                                buildFavoriteKey(item.protocol, item.protocolId, item.path) !in existingKeys
                            }
                            val skippedCount = selectedFiles.size - newFiles.size

                            if (newFiles.isEmpty()) {
                                snackbarHostState.showLatestSnackbar(AppStrings.ui_selected_item_already_your_collection)
                                return@launch
                            }

                            newFiles.forEach { item -> fileFavoriteState.addFavorite(item) }
                            fileFavoriteState.sync()

                            val message = if (skippedCount > 0) {
                                AppStrings.ui_arg0_items_added_arg1_items_already_exist.format(arg0 = (newFiles.size).toString(), arg1 = (skippedCount).toString())
                            } else {
                                AppStrings.ui_added_favorites
                            }
                            snackbarHostState.showLatestSnackbar(message)
                        }
                    }
                )
            }
        }
    }

}
