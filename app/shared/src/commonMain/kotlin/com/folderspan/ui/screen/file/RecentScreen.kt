package com.folderspan.ui.screen.file

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.toIcon
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.db.FileRecent
import com.folderspan.extensions.replaceLast
import com.folderspan.extensions.timestampToYearMonthDay
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.ui.components.filter.FilterOptionChip
import com.folderspan.ui.components.filter.FilterSectionCard
import com.folderspan.ui.components.filter.FilterSheetFrame
import com.folderspan.ui.components.combinedClickableWithContextClick
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.components.file.FileIcon
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.state.file.FileRecentState
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
import kotlin.time.Clock

class RecentScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        val fileState = koinInject<FileState>()
        val deviceState = koinInject<DeviceState>()
        val networkState = koinInject<NetworkState>()
        val fileRecentState = koinInject<FileRecentState>()
        val recents by fileRecentState.recents.collectAsState()

        val selectedIds = remember { mutableStateListOf<Long>() }
        var showFilterSheet by remember { mutableStateOf(false) }
        val selectedProtocols = remember { mutableStateListOf<FileProtocol>() }
        var entryTypeFilter by rememberSaveable { mutableStateOf(EntryTypeFilter.All) }
        var timeFilter by rememberSaveable { mutableStateOf(RecentTimeFilter.All) }
        var searchText by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }

        fun resetFilter() {
            selectedProtocols.clear()
            entryTypeFilter = EntryTypeFilter.All
            timeFilter = RecentTimeFilter.All
            searchText = ""
            searchDraft = ""
        }

        fun matchesEntryType(recent: FileRecent): Boolean {
            return when (entryTypeFilter) {
                EntryTypeFilter.All -> true
                EntryTypeFilter.FileOnly -> !recent.isDirectory
                EntryTypeFilter.FolderOnly -> recent.isDirectory
            }
        }

        fun matchesTimeFilter(recent: FileRecent, nowMs: Long): Boolean {
            return when (timeFilter) {
                RecentTimeFilter.All -> true
                RecentTimeFilter.Today -> {
                    recent.lastAccessed.timestampToYearMonthDay() == nowMs.timestampToYearMonthDay()
                }

                RecentTimeFilter.Last7Days -> {
                    recent.lastAccessed >= nowMs - 7L * 24 * 60 * 60 * 1000
                }

                RecentTimeFilter.Last30Days -> {
                    recent.lastAccessed >= nowMs - 30L * 24 * 60 * 60 * 1000
                }
            }
        }

        val query = searchText.trim()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val filteredRecents = recents
            .asSequence()
            .filter { recent -> recent.protocol != FileProtocol.Share }
            .filter { recent -> selectedProtocols.isEmpty() || recent.protocol in selectedProtocols }
            .filter(::matchesEntryType)
            .filter { recent -> matchesTimeFilter(recent, nowMs) }
            .filter { recent ->
                query.isEmpty() || recent.name.contains(query, ignoreCase = true) ||
                        recent.path.contains(query, ignoreCase = true)
            }
            .toList()
        val activeFilterCount =
            (if (selectedProtocols.isEmpty()) 0 else 1) +
                    (if (entryTypeFilter == EntryTypeFilter.All) 0 else 1) +
                    (if (timeFilter == RecentTimeFilter.All) 0 else 1) +
                    (if (query.isEmpty()) 0 else 1)
        val hasActiveFilter = activeFilterCount > 0

        LaunchedEffect(Unit) {
            fileRecentState.loadAll()
        }

        LaunchedEffect(filteredRecents) {
            val availableIds = filteredRecents.map { item -> item.id }.toSet()
            selectedIds.removeAll { item ->  item !in availableIds }
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

        fun clearSelection() {
            selectedIds.clear()
        }

        fun toggleSelection(recent: FileRecent) {
            if (recent.id in selectedIds) {
                selectedIds.remove(recent.id)
            } else {
                selectedIds.add(recent.id)
            }
        }

        fun confirmClearAll() {
            if (recents.isEmpty()) return
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = AppStrings.ui_clear_all_recent_records,
                    actionLabel = AppStrings.ui_clear,
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) {
                    fileRecentState.clear()
                    clearSelection()
                }
            }
        }

        fun confirmDeleteSelected() {
            if (selectedIds.isEmpty()) return
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = AppStrings.ui_delete_selected_arg0_records.format(arg0 = (selectedIds.size).toString()),
                    actionLabel = AppStrings.ui_delete,
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) {
                    fileRecentState.deleteByIds(selectedIds.toList())
                    clearSelection()
                }
            }
        }

        fun openRecent(recent: FileRecent) {
            scope.launch {
                val targetPath = if (recent.isDirectory) {
                    recent.path
                } else {
                    recent.path.replaceLast(recent.name, "")
                }

                suspend fun openOnDesk(targetDesk: DiskBase) {
                    if (isSameDesk(targetDesk)) {
                        fileState.updatePath(targetPath)
                    } else {
                        fileState.updateDesk(recent.protocol, targetDesk, pathOverride = targetPath)
                    }

                    if (!recent.isDirectory) {
                        withContext(Dispatchers.Default) {
                            FileUtils.openFile(FileAccessPermission.Allowed, recent.path)
                        }
                    }
                    navigator.pop()
                }

                when (recent.protocol) {
                    FileProtocol.Local -> {
                        openOnDesk(Local())
                    }

                    FileProtocol.Device -> {
                        val resolvedId = recent.protocolId
                        if (resolvedId.isBlank()) {
                            snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                        }
                        val connected = deviceState.devices.firstOrNull { item -> item.id == resolvedId }
                        if (connected != null) {
                            openOnDesk(connected)
                            return@launch
                        }
                        val socketDevice = deviceState.socketDevices.firstOrNull { item ->
                            item.id == resolvedId && item.transportType == DeviceTransportType.Session
                        }
                        if (socketDevice == null) {
                            snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                        }
                        updateDeviceConnectType(socketDevice.id, ConnectType.Loading)
                        val target = try {
                            deviceState.connect(socketDevice)
                            deviceState.devices.firstOrNull { item -> item.id == resolvedId }
                        } catch (_: Exception) {
                            updateDeviceConnectType(socketDevice.id, ConnectType.Fail)
                            snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                        }
                        if (target == null) {
                            snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                    }
                    openOnDesk(target)
                }

                FileProtocol.Share -> {
                    snackbarHostState.showSnackbar(AppStrings.ui_recent_records_do_not_support_sharing_sources)
                    return@launch
                }

                FileProtocol.Network -> {
                    val resolvedId = recent.protocolId
                    if (resolvedId.isBlank()) {
                        snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                        }
                        networkState.loadPersisted()
                        val connectedEntry =
                            networkState.connectedEntries.firstOrNull { item -> item.network.protocolId == resolvedId }
                        val entry = connectedEntry
                            ?: networkState.entries.firstOrNull { item -> item.network.protocolId == resolvedId }
                        if (entry == null) {
                            snackbarHostState.showSnackbar(AppStrings.ui_target_unavailable)
                            return@launch
                        }
                        if (connectedEntry == null) {
                            networkState.connectEntry(entry)
                        }
                        openOnDesk(entry.network)
                    }
                }
            }
        }

        AppScaffold(
            topBar = {
                val isSelectionMode = selectedIds.isNotEmpty()
                TopAppBar(
                    title = {
                        if (isSelectionMode) {
                            Text(AppStrings.ui_selected_item_count_arg0.format(arg0 = (selectedIds.size).toString()))
                        } else {
                            Text(AppStrings.ui_recently)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
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
                        if (isSelectionMode) {
                            IconButton(
                                onClick = { confirmDeleteSelected() },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete_record)
                            }
                        } else {
                            if (hasActiveFilter) {
                                FilledTonalIconButton(onClick = {
                                    searchDraft = searchText
                                    showFilterSheet = true
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = AppStrings.ui_search_filter_current_arg0_conditions.format(arg0 = (activeFilterCount).toString())
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    searchDraft = searchText
                                    showFilterSheet = true
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_filter)
                                }
                            }
                            if (recents.isNotEmpty()) {
                                IconButton(onClick = { confirmClearAll() }) {
                                    Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_clear_record)
                                }
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            val isSelectionMode = selectedIds.isNotEmpty()
            GridList(
                modifier = Modifier.padding(paddingValues),
                isEmpty = filteredRecents.isEmpty(),
            ) {
                if (filteredRecents.isEmpty()) return@GridList
                items(filteredRecents, key = { item ->  item.id }) { recent ->
                    val file = remember(recent) { recent.toFileSimpleInfo() }
                    val isSelected = recent.id in selectedIds
                    val containerColor =
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    val headlineColor =
                        if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    val supportingColor =
                        if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.outline

                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = containerColor,
                            headlineColor = headlineColor,
                            supportingColor = supportingColor
                        ),
                        leadingContent = { FileIcon(file) },
                        headlineContent = { Text(file.name) },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                file.protocol.toIcon()
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    color = supportingColor
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { toggleSelection(recent) }) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_recent
                                )
                            }
                        },
                        modifier = Modifier
                            .combinedClickableWithContextClick(
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleSelection(recent)
                                    } else {
                                        openRecent(recent)
                                    }
                                },
                                onLongClick = { toggleSelection(recent) }
                            )
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
                            title = AppStrings.ui_time_range,
                            icon = Icons.Default.Schedule
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RecentTimeFilter.entries.forEach { filter ->
                                    FilterOptionChip(
                                        selected = timeFilter == filter,
                                        label = filter.label(),
                                        onClick = { timeFilter = filter }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FileRecent.toFileSimpleInfo(): FileSimpleInfo {
    return FileSimpleInfo(
        name = name,
        description = "",
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = mineType,
        size = size,
        createdDate = createdDate,
        updatedDate = updatedDate,
        protocol = protocol,
        protocolId = protocolId,
    )
}
