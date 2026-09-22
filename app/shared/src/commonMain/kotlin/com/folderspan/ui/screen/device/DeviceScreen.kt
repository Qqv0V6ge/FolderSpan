package com.folderspan.ui.screen.device

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceConnectType.*
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.localization.localizedRoleName
import com.folderspan.extensions.DeviceIcon
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.ConnectType.*
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.dialog.*
import com.folderspan.ui.components.drawer.DeviceConnectNewDialog
import com.folderspan.ui.components.drawer.appDrawerSocketDevicesInDisplayOrder
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.model.buildDeviceConnectOptionsUiState
import com.folderspan.ui.components.model.buildDeviceRoleOptionsUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSettingsState
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Instant
import com.folderspan.db.Device as DbDevice

internal val serverConnectTypeNameMap = mapOf(
    AUTO_CONNECT to AppStrings.ui_automatic_consent,
    PERMANENTLY_BANNED to AppStrings.ui_always_refuse,
    APPROVED to AppStrings.ui_waiting_authorization,
)

internal val clientConnectTypeNameMap = mapOf(
    AUTO_CONNECT to AppStrings.ui_automatically_connect,
    APPROVED to AppStrings.ui_connect_after_asking,
)


private data class AccessStatusUi(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
)

internal suspend fun confirmDeviceDeletion(
    snackbarHostState: SnackbarHostState,
    deviceIds: List<String>,
    message: String = AppStrings.ui_you_sure_you_want_delete_selected_arg0_devices.format(
        arg0 = deviceIds.size.toString()
    ),
    onConfirm: suspend (List<String>) -> Unit,
) {
    val pendingIds = deviceIds.toList()
    if (pendingIds.isEmpty()) return
    val result = snackbarHostState.showLatestSnackbar(
        message = message,
        actionLabel = AppStrings.ui_delete,
        withDismissAction = true,
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) {
        onConfirm(pendingIds)
    }
}

@Immutable
internal data class DeviceDisplayItem(
    val id: String,
    val name: String,
    val type: DeviceType,
    val device: DbDevice?,
    val serverAccess: DeviceJoinDeviceRole?,
    val clientAccess: DeviceJoinDeviceRole?,
    val outgoingConnections: List<SocketDevice>,
    val hasIncomingConnection: Boolean,
    val drawerDeviceSortOrder: Int?,
) {
    val isManageable: Boolean
        get() = device != null || serverAccess != null || clientAccess != null
    val hasActiveOutgoingConnection: Boolean
        get() = outgoingConnections.any { connection -> connection.hasActiveConnection() }
    val isConnected: Boolean
        get() = hasIncomingConnection || hasActiveOutgoingConnection
    val appearsInDrawer: Boolean
        get() = drawerDeviceSortOrder != null
}

class DeviceScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val deviceState = koinInject<DeviceState>()
        val fileState = koinInject<FileState>()
        val database = koinInject<FolderSpanDatabase>()
        val deviceCertificateState = koinInject<DeviceCertificateState>()
        val deviceSettingsState = koinInject<DeviceSettingsState>()
        val deviceRoleState = koinInject<DeviceRoleState>()
        val pendingNewDevice by deviceState.pendingConnectNewDevice.collectAsState()
        LaunchedEffect(deviceRoleState) {
            deviceRoleState.refresh()
        }
        val deviceRoleOptions = buildDeviceRoleOptionsUiState(deviceRoleState.roles)

        val deskType by fileState.deskType.collectAsState()

        var showSearchDialog by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchDraft by remember { mutableStateOf("") }

        var devices by remember { mutableStateOf(emptyList<DbDevice>()) }
        var clientAccessDevices by remember { mutableStateOf(emptyList<DeviceJoinDeviceRole>()) }
        var showEditDialog by remember { mutableStateOf(false) }
        var selectedDevice by remember { mutableStateOf<DbDevice?>(null) }
        var selectedManagedItem by remember { mutableStateOf<DeviceDisplayItem?>(null) }
        var editedName by remember { mutableStateOf("") }
        var deviceType by remember { mutableStateOf<DeviceType?>(null) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedDeviceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showBatchServerAccessDialog by remember { mutableStateOf(false) }
        var showBatchClientAccessDialog by remember { mutableStateOf(false) }
        var incomingConnectedDevices by remember { mutableStateOf(emptyList<DbDevice>()) }

        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val accessDevicesById = deviceSettingsState.devices.associateBy { item -> item.id }
        val displayItems = buildDeviceDisplayItems(
            devices = devices,
            serverAccessById = accessDevicesById,
            clientAccessDevices = clientAccessDevices,
            socketDevices = deviceState.socketDevices,
            incomingConnectedDevices = incomingConnectedDevices,
            searchQuery = searchQuery,
            deviceType = deviceType
        )
        val selectableDeviceIds = displayItems
            .filter { item -> item.isManageable }
            .map { item -> item.id }
            .toSet()
        val selectedDisplayItems = displayItems.filter { item ->
            item.isManageable && item.id in selectedDeviceIds
        }
        val hasAllVisibleSelected = hasAllVisibleDevicesSelected(selectableDeviceIds, selectedDeviceIds)
        val showBatchActions = selectionMode && selectedDeviceIds.isNotEmpty()

        fun updateSocketDevice(device: SocketDevice, connectType: ConnectType) {
            deviceState.updateSocketDeviceConnectType(device, connectType)
        }

        fun connectDevice(device: SocketDevice) {
            updateSocketDevice(device, Loading)
            scope.launch {
                try {
                    deviceState.connect(device)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    updateSocketDevice(device, Fail)
                    fileState.updateDesk(FileProtocol.Local, Local())
                }
            }
        }

        fun disconnectDiscoveredDevice(device: SocketDevice) {
            if (deviceState.disconnectSocketDevice(device)) {
                updateSocketDevice(device, UnConnect)
                deviceState.devices.remove(
                    deviceState.devices.firstOrNull { item -> item.id == device.id }
                )
            }
        }

        fun openConnectedDevice(device: SocketDevice) {
            deviceState.devices.firstOrNull { item -> item.id == device.id }?.let { item ->
                fileState.updateDesk(FileProtocol.Device, item)
            }
        }

        fun disconnectIncomingDevice(deviceId: String) {
            scope.launch {
                deviceCertificateState.removeDeviceToken(deviceId)
                deviceState.remoteDeviceConnections.remove(deviceId)
                incomingConnectedDevices = incomingConnectedDevices.filterNot { item -> item.id == deviceId }
            }
        }

        fun syncEditedDeviceName(deviceId: String, updatedName: String) {
            val indexSocket = deviceState.socketDevices.indexOfFirst { item -> item.id == deviceId }
            if (indexSocket != -1) {
                deviceState.socketDevices[indexSocket] =
                    deviceState.socketDevices[indexSocket].copy(name = updatedName)
            }

            val indexDevice = deviceState.devices.indexOfFirst { item -> item.id == deviceId }
            if (indexDevice != -1) {
                deviceState.devices[indexDevice] = deviceState.devices[indexDevice].copy(name = updatedName)
            }

            val indexShare = deviceState.shares.indexOfFirst { item -> item.id == deviceId }
            if (indexShare != -1) {
                deviceState.shares[indexShare] = deviceState.shares[indexShare].copy(name = updatedName)
            }

            if (deskType is Device) {
                val currentDevice = deskType as Device
                if (currentDevice.id == deviceId) {
                    fileState.updateDesk(
                        FileProtocol.Device,
                        currentDevice.copy(name = updatedName)
                    )
                }
            }

            if (deskType is Share) {
                val currentShare = deskType as Share
                if (currentShare.id == deviceId) {
                    fileState.updateDesk(
                        FileProtocol.Share,
                        currentShare.copy(name = updatedName)
                    )
                }
            }
        }

        suspend fun refreshDevices() {
            val typesToQuery = if (deviceType == null) {
                listOf(DeviceType.JVM, DeviceType.IOS, DeviceType.Android, DeviceType.JS)
            } else {
                listOf(deviceType!!)
            }

            devices = database.deviceQueries.queryByNameAndDeviceTypePaginated(
                searchQuery,
                typesToQuery,
                100L,
                0L
            ).executeAsListAwait()
        }

        suspend fun refreshClientAccessDevices() {
            clientAccessDevices = queryAccessDevices(
                database = database,
                searchQuery = searchQuery,
                category = DeviceCategory.CLIENT
            )
        }

        fun clearSelection(exitSelectionMode: Boolean = false) {
            selectedDeviceIds = emptySet()
            if (exitSelectionMode) {
                selectionMode = false
            }
        }

        fun removeDeviceFromCaches(deviceId: String) {
            deviceState.socketDevices.removeAll { item -> item.id == deviceId }
            deviceState.remoteDeviceConnections.remove(deviceId)
            deviceState.devices.removeAll { item -> item.id == deviceId }
            deviceState.shares.removeAll { item -> item.id == deviceId }
            deviceCertificateState.removeDeviceToken(deviceId)
            TrustedDeviceCertificateStore.remove(deviceId)

            val currentDevice = deskType as? Device
            if (currentDevice?.id == deviceId) {
                fileState.updateDesk(FileProtocol.Local, Local())
            }

            val currentShare = deskType as? Share
            if (currentShare?.id == deviceId) {
                fileState.updateDesk(FileProtocol.Local, Local())
            }
        }

        suspend fun deleteDevices(deviceIds: Collection<String>) {
            deviceIds.distinct().forEach { deviceId ->
                database.deviceQueries.deleteById(deviceId).awaitDatabaseReady()
                database.deviceConnectQueries.deleteById(deviceId).awaitDatabaseReady()
                removeDeviceFromCaches(deviceId)
            }

            refreshDevices()
            refreshClientAccessDevices()
            deviceSettingsState.refresh()
        }

        LaunchedEffect(Unit) {
            refreshDevices()
        }

        LaunchedEffect(searchQuery) {
            deviceSettingsState.updateCategory(DeviceCategory.SERVER)
            deviceSettingsState.updateDeviceName(searchQuery)
            deviceSettingsState.refresh()
            refreshClientAccessDevices()
        }

        LaunchedEffect(deviceState.remoteDeviceConnections.keys.toList()) {
            incomingConnectedDevices = withContext(Dispatchers.Default) {
                val deviceIds = deviceState.remoteDeviceConnections.keys.toList()
                deviceIds.mapNotNull { deviceId ->
                    database.deviceQueries.queryById(deviceId).executeAsOneOrNullAwait()
                }
            }
        }

        LaunchedEffect(selectableDeviceIds, selectionMode) {
            val retained = retainVisibleDeviceSelection(selectedDeviceIds, selectableDeviceIds)
            if (retained != selectedDeviceIds) {
                selectedDeviceIds = retained
            }
            if (selectionMode && selectableDeviceIds.isEmpty()) {
                clearSelection(exitSelectionMode = true)
            }
        }

        if (showEditDialog && selectedDevice != null) {
            DeviceEditDialog(
                selectedDevice = selectedDevice!!,
                editedName = editedName,
                onEditedNameChange = { value -> editedName = value },
                onDismiss = { showEditDialog = false },
                onConfirm = { device ->
                    scope.launch {
                        database.deviceQueries.updateNameAndEnableRemarksById(
                            name = editedName,
                            id = device.id
                        ).awaitDatabaseReady()
                        syncEditedDeviceName(device.id, editedName)
                        refreshDevices()
                        deviceSettingsState.refresh()
                        showEditDialog = false
                    }
                }
            )
        }

        if (showSearchDialog) {
            SearchDialog(
                title = AppStrings.ui_search_device,
                query = searchDraft,
                onQueryChange = { value -> searchDraft = value },
                onConfirm = {
                    clearSelection()
                    searchQuery = searchDraft.trim()
                    scope.launch { refreshDevices() }
                    showSearchDialog = false
                },
                onDismissRequest = { showSearchDialog = false },
                confirmText = AppStrings.ui_search,
                dismissText = AppStrings.ui_reset,
                onDismissButtonClick = {
                    clearSelection()
                    searchQuery = ""
                    searchDraft = ""
                    scope.launch { refreshDevices() }
                    showSearchDialog = false
                },
                label = AppStrings.ui_device_name,
                onClear = { searchDraft = "" }
            )
        }

        selectedManagedItem?.let { item ->
            EditCombinedDeviceDialog(
                deviceName = item.name,
                serverDevice = item.serverAccess,
                clientDevice = item.clientAccess,
                roleOptions = deviceRoleOptions,
                onDismissRequest = { selectedManagedItem = null },
                onSaveChange = { result ->
                    scope.launch {
                        deviceSettingsState.upsertDeviceAccess(
                            deviceId = item.id,
                            deviceName = result.deviceName,
                            category = DeviceCategory.SERVER,
                            connectionType = result.serverConnectionType,
                            roleId = result.serverRoleId,
                            refreshAfter = false
                        )

                        deviceSettingsState.upsertDeviceAccess(
                            deviceId = item.id,
                            deviceName = result.deviceName,
                            category = DeviceCategory.CLIENT,
                            connectionType = result.clientConnectionType,
                            refreshAfter = false
                        )

                        when (result.serverRoleId) {
                            null -> deviceCertificateState.removeDevicePermission(item.id)
                            else -> {
                                if (item.serverAccess?.roleId != result.serverRoleId) {
                                    deviceCertificateState.setDevicePermission(item.id, result.serverRoleId)
                                }
                            }
                        }

                        if (item.name != result.deviceName) {
                            syncEditedDeviceName(item.id, result.deviceName)
                        }

                        refreshDevices()
                        refreshClientAccessDevices()
                        deviceSettingsState.refresh()
                    }
                }
            )
        }

        if (showBatchServerAccessDialog) {
            DeviceBatchServerAccessDialog(
                count = selectedDeviceIds.size,
                eligibleCount = selectedDisplayItems.size,
                connectionOptions = buildDeviceConnectOptionsUiState(serverConnectTypeNameMap),
                roleOptions = deviceRoleOptions,
                onDismissRequest = { showBatchServerAccessDialog = false },
                onConfirm = { result ->
                    scope.launch {
                        selectedDisplayItems.forEach { item ->
                            deviceSettingsState.upsertDeviceAccess(
                                deviceId = item.id,
                                deviceName = item.name,
                                category = DeviceCategory.SERVER,
                                connectionType = result.connectionType,
                                roleId = result.roleId
                                    ?: item.serverAccess?.roleId?.takeIf { roleId -> roleId != -1L },
                                refreshAfter = false
                            )
                            result.roleId?.let { roleId ->
                                deviceCertificateState.setDevicePermission(item.id, roleId)
                            }
                        }
                        deviceSettingsState.refresh()
                        refreshDevices()
                        refreshClientAccessDevices()
                        clearSelection()
                        showBatchServerAccessDialog = false
                    }
                }
            )
        }

        if (showBatchClientAccessDialog) {
            DeviceBatchClientAccessDialog(
                count = selectedDeviceIds.size,
                eligibleCount = selectedDisplayItems.size,
                connectionOptions = buildDeviceConnectOptionsUiState(clientConnectTypeNameMap),
                onDismissRequest = { showBatchClientAccessDialog = false },
                onConfirm = { connectionType ->
                    scope.launch {
                        selectedDisplayItems.forEach { item ->
                            deviceSettingsState.upsertDeviceAccess(
                                deviceId = item.id,
                                deviceName = item.name,
                                category = DeviceCategory.CLIENT,
                                connectionType = connectionType,
                                refreshAfter = false
                            )
                        }
                        deviceSettingsState.refresh()
                        refreshDevices()
                        refreshClientAccessDevices()
                        clearSelection()
                        showBatchClientAccessDialog = false
                    }
                }
            )
        }

        AppScaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (selectionMode) AppStrings.ui_arg0_items_selected.format(arg0 = (selectedDeviceIds.size).toString()) else AppStrings.ui_device_management)
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
                                imageVector = if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        IconToggleButton(
                            checked = searchQuery.isNotBlank(),
                            onCheckedChange = {
                                searchDraft = searchQuery
                                showSearchDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_device)
                        }

                        if (selectionMode && displayItems.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    selectedDeviceIds = toggleAllVisibleDeviceIds(selectableDeviceIds, selectedDeviceIds)
                                }
                            ) {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = if (hasAllVisibleSelected) AppStrings.ui_deselect_all else AppStrings.ui_select_all
                                )
                            }
                        }

                        if (!selectionMode && selectableDeviceIds.isNotEmpty()) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                when {
                    showBatchActions -> {
                        DeviceBatchActionsFab(
                            onBatchServerAccess = { showBatchServerAccessDialog = true },
                            onBatchClientAccess = { showBatchClientAccessDialog = true },
                            onBatchDelete = {
                                val deviceIds = selectedDisplayItems.map { item -> item.id }
                                scope.launch {
                                    confirmDeviceDeletion(snackbarHostState, deviceIds) { pendingIds ->
                                        deleteDevices(pendingIds)
                                        clearSelection()
                                    }
                                }
                            }
                        )
                    }

                    !selectionMode -> {
                        ExtendedFloatingActionButton(
                            onClick = { navigator.push(DeviceRoleScreen()) },
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            text = { Text(AppStrings.ui_role) }
                        )
                    }
                }
            }
        ) { paddingValues ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                floatingActionButtonPadding = if (showBatchActions || !selectionMode) GridListFabPadding else 0.dp,
                verticalSpacing = 12.dp,
                horizontalSpacing = 12.dp
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        defaultDeviceTypeOptions.forEachIndexed { index, (type, label) ->
                            SegmentedButton(
                                selected = deviceType == type,
                                onClick = {
                                    clearSelection()
                                    deviceType = type
                                    scope.launch { refreshDevices() }
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

                if (displayItems.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DeviceSectionPlaceholder(AppStrings.ui_no_equipment_yet)
                    }
                } else {
                    items(displayItems, key = { item -> item.id }) { item ->
                        DeviceListItem(
                            item = item,
                            selectionMode = selectionMode,
                            isSelected = item.isManageable && item.id in selectedDeviceIds,
                            onSelectionToggle = {
                                if (!item.isManageable) return@DeviceListItem
                                selectedDeviceIds = if (item.id in selectedDeviceIds) {
                                    selectedDeviceIds - item.id
                                } else {
                                    selectedDeviceIds + item.id
                                }
                            },
                            onEditClick = {
                                if (item.serverAccess != null || item.clientAccess != null) {
                                    selectedManagedItem = item
                                } else {
                                    item.device?.let { device ->
                                        selectedDevice = device
                                        editedName = device.name
                                        showEditDialog = true
                                    }
                                }
                            },
                            onDeleteClick = {
                                scope.launch {
                                    confirmDeviceDeletion(
                                        snackbarHostState = snackbarHostState,
                                        deviceIds = listOf(item.id),
                                        message = AppStrings.dialog_delete_device.format(deviceName = item.name),
                                        onConfirm = ::deleteDevices,
                                    )
                                }
                            },
                            onForceCloseIncoming = {
                                disconnectIncomingDevice(item.id)
                            },
                            onForceCloseOutgoing = {
                                item.outgoingConnections
                                    .filter { connection -> connection.hasActiveConnection() }
                                    .forEach { connection -> disconnectDiscoveredDevice(connection) }
                            },
                            onQuickUpdateAccess = { category, connectionType ->
                                scope.launch {
                                    val currentAccess = when (category) {
                                        DeviceCategory.SERVER -> item.serverAccess
                                        DeviceCategory.CLIENT -> item.clientAccess
                                    }
                                    deviceSettingsState.upsertDeviceAccess(
                                        deviceId = item.id,
                                        deviceName = item.name,
                                        category = category,
                                        connectionType = connectionType,
                                        roleId = currentAccess?.roleId?.takeIf { roleId ->
                                            category == DeviceCategory.SERVER && roleId != -1L
                                        },
                                        refreshAfter = false
                                    )
                                    refreshDevices()
                                    refreshClientAccessDevices()
                                    deviceSettingsState.refresh()
                                }
                            },
                            onOutgoingPrimaryAction = { connection ->
                                when (connection.connectType) {
                                    UnConnect, Fail, Rejected -> connectDevice(connection)
                                    New -> if (connection.discoveryStatus == DeviceDiscoveryStatus.Unverified) {
                                        connectDevice(connection)
                                    } else {
                                        deviceState.requestConnectNewDevice(connection)
                                    }
                                    Connect -> openConnectedDevice(connection)
                                    Loading -> {
                                        deviceState.disconnectSocketDevice(connection)
                                        updateSocketDevice(connection, UnConnect)
                                    }
                                }
                            },
                            onOutgoingDisconnect = { connection ->
                                disconnectDiscoveredDevice(connection)
                            },
                            onIncomingDisconnect = { disconnectIncomingDevice(item.id) }
                        )
                    }
                }
            }
        }

        if (pendingNewDevice != null) {
            DeviceConnectNewDialog(
                socketDevice = pendingNewDevice!!,
                onConnect = { isAuto ->
                    val socketDevice = pendingNewDevice!!
                    scope.launch {
                        database.deviceConnectQueries.upsert(
                            id = socketDevice.id,
                            connectionType = if (isAuto) {
                                AUTO_CONNECT
                            } else {
                                WAITING
                            },
                            category = DeviceCategory.CLIENT,
                            roleId = -1L,
                        ).awaitDatabaseReady()
                        deviceState.updateSocketDeviceConnectType(socketDevice, Loading)
                        deviceState.connectInBackground(socketDevice)
                        deviceState.consumeConnectNewDevice()
                    }
                },
                onCancel = { deviceState.consumeConnectNewDevice() }
            )
        }
    }

    @Composable
    private fun DeviceListItem(
        item: DeviceDisplayItem,
        selectionMode: Boolean,
        isSelected: Boolean,
        onSelectionToggle: () -> Unit,
        onEditClick: () -> Unit,
        onDeleteClick: () -> Unit,
        onForceCloseIncoming: () -> Unit,
        onForceCloseOutgoing: () -> Unit,
        onQuickUpdateAccess: (DeviceCategory, DeviceConnectType) -> Unit,
        onOutgoingPrimaryAction: (SocketDevice) -> Unit,
        onOutgoingDisconnect: (SocketDevice) -> Unit,
        onIncomingDisconnect: () -> Unit
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isSelected -> colorScheme.secondaryContainer
                    item.hasIncomingConnection -> colorScheme.tertiaryContainer.copy(alpha = 0.42f)
                    item.isConnected -> colorScheme.primaryContainer.copy(alpha = 0.34f)
                    else -> colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 0.dp
            ),
            modifier = if (selectionMode && item.isManageable) {
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelectionToggle)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Column(
                modifier = Modifier.padding(if(!selectionMode && item.hasIncomingConnection) 16.dp else 8.dp),
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
                            item.type.DeviceIcon(modifier = Modifier.size(24.dp))
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.size(4.dp))
                    if (selectionMode && item.isManageable) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onSelectionToggle() }
                        )
                    } else if (item.isManageable) {
                        DeviceListItemMenu(
                            onEditClick = onEditClick,
                            onDeleteClick = onDeleteClick
                        )
                    }
                }

                val visibleConnections = item.outgoingConnections.filter { connection ->
                    !item.isManageable || connection.discoveryStatus == DeviceDiscoveryStatus.Unverified
                }
                if (visibleConnections.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleConnections.forEach { connection ->
                            DeviceOutgoingConnectionBlock(
                                connection = connection,
                                enabled = !selectionMode,
                                onPrimaryAction = { onOutgoingPrimaryAction(connection) },
                                onDisconnect = { onOutgoingDisconnect(connection) }
                            )
                        }

//                        if (item.hasIncomingConnection) {
//                            DeviceIncomingConnectionBlock(
//                                enabled = !selectionMode,
//                                onDisconnect = onIncomingDisconnect
//                            )
//                        }
                    }
                }

                if (item.isManageable) {
                    val identityVerified = item.outgoingConnections.none {
                        it.discoveryStatus == DeviceDiscoveryStatus.Unverified
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceAccessBlock(
                            label = AppStrings.ui_visited,
                            category = DeviceCategory.SERVER,
                            device = item.serverAccess,
                            enabled = !selectionMode && identityVerified,
                            showForceClose = item.hasIncomingConnection,
                            onConnectionTypeChange = { connectionType ->
                                onQuickUpdateAccess(DeviceCategory.SERVER, connectionType)
                            },
                            onForceClose = {
                                onForceCloseIncoming()
                            }
                        )

                        DeviceAccessBlock(
                            label = AppStrings.ui_access_other_devices,
                            category = DeviceCategory.CLIENT,
                            device = item.clientAccess,
                            enabled = !selectionMode && identityVerified,
                            showForceClose = item.hasActiveOutgoingConnection,
                            onConnectionTypeChange = { connectionType ->
                                onQuickUpdateAccess(DeviceCategory.CLIENT, connectionType)
                            },
                            onForceClose = {
                                onForceCloseOutgoing()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSectionPlaceholder(
    text: String
) {
    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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
private fun rememberSocketConnectionStatus(
    connection: SocketDevice
): AccessStatusUi {
    if (connection.discoveryStatus == DeviceDiscoveryStatus.Unverified) {
        return AccessStatusUi(Icons.Default.Warning, AppStrings.ui_device_identity_unverified, colorScheme.tertiary)
    }
    return when (connection.connectType) {
        Connect -> AccessStatusUi(Icons.Default.CheckCircle, AppStrings.ui_connected, colorScheme.primary)
        Fail -> AccessStatusUi(Icons.Default.Cancel, AppStrings.ui_connection_failed, colorScheme.error)
        Rejected -> AccessStatusUi(Icons.Default.Cancel, AppStrings.ui_connection_refused, colorScheme.error)
        Loading -> AccessStatusUi(Icons.Default.Schedule, AppStrings.ui_connecting, colorScheme.tertiary)
        New -> AccessStatusUi(Icons.Default.Schedule, AppStrings.ui_new_device_pending, colorScheme.secondary)
        UnConnect -> AccessStatusUi(Icons.Default.Schedule, AppStrings.ui_not_connected, colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DeviceOutgoingConnectionBlock(
    connection: SocketDevice,
    enabled: Boolean,
    onPrimaryAction: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = rememberSocketConnectionStatus(connection)
    val actionLabel = when {
        connection.connectType == Loading -> AppStrings.ui_cancel
        connection.discoveryStatus == DeviceDiscoveryStatus.Unverified -> AppStrings.ui_device_identity_confirm
        connection.connectType == Connect -> null
        connection.connectType == New -> AppStrings.ui_process
        else -> AppStrings.ui_connect
    }

    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = connection.transportType.name,
                    style = typography.labelLarge,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ConnectionStatusIndicator(status)
            }

            actionLabel?.let { label ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onPrimaryAction,
                        enabled = enabled
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceBatchActionsFab(
    onBatchServerAccess: () -> Unit,
    onBatchClientAccess: () -> Unit,
    onBatchDelete: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp)
    ) {
        FloatingActionButton(onClick = onBatchServerAccess) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppStrings.ui_batch_modification_accessed)
        }
        FloatingActionButton(onClick = onBatchClientAccess) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = AppStrings.ui_modify_access_other_devices_batches)
        }
        ExtendedFloatingActionButton(
            onClick = onBatchDelete,
            containerColor = colorScheme.error,
            icon = { Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete_devices_batches) },
            text = { Text(AppStrings.ui_delete) }
        )
    }
}

@Composable
private fun DeviceListItemMenu(
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
                onClick = {
                    expanded = false
                    onEditClick()
                }
            )

            DropdownMenuItem(
                text = { Text(AppStrings.ui_remove_device) },
                onClick = {
                    expanded = false
                    onDeleteClick()
                }
            )
        }
    }
}

@Composable
internal fun DeviceAccessBlock(
    label: String,
    category: DeviceCategory,
    device: DeviceJoinDeviceRole?,
    enabled: Boolean,
    showForceClose: Boolean,
    onConnectionTypeChange: (DeviceConnectType) -> Unit,
    onForceClose: () -> Unit
) {
    val status = rememberAccessStatus(category, device)
    val lastConnectionText = remember(device?.lastConnection) {
        formatAccessTime(device?.lastConnection)
    }
    val directionIcon = when (category) {
        DeviceCategory.SERVER -> Icons.AutoMirrored.Filled.ArrowBack
        DeviceCategory.CLIENT -> Icons.AutoMirrored.Filled.ArrowForward
    }
    val connectionTypeOptions = when (category) {
        DeviceCategory.SERVER -> serverConnectTypeNameMap
        DeviceCategory.CLIENT -> clientConnectTypeNameMap
    }
    val currentConnectionType = device?.connectionType ?: APPROVED
    val density = LocalDensity.current
    val labelStyle = typography.labelLarge
    val iconContainerSize = with(density) { labelStyle.lineHeight.toDp() }
    val iconSize = with(density) { labelStyle.fontSize.toDp() }
    var expanded by remember(device?.id, category, currentConnectionType, enabled) { mutableStateOf(false) }

    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(iconContainerSize),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = directionIcon,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                tint = colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Text(
                        text = label,
                        style = labelStyle,
                        color = colorScheme.onSurface
                    )
                }
                Box {
                    Row(
                        modifier = if (enabled) {
                            Modifier.clickable { expanded = true }
                        } else {
                            Modifier
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
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
                                contentDescription = AppStrings.ui_modify_arg0.format(arg0 = label),
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        connectionTypeOptions
                            .filterKeys { type -> type != currentConnectionType }
                            .forEach { (type, optionLabel) ->
                                DropdownMenuItem(
                                    text = { Text(optionLabel) },
                                    onClick = {
                                        expanded = false
                                        onConnectionTypeChange(type)
                                    }
                                )
                            }
                    }
                }
            }

            if (category == DeviceCategory.SERVER) {
                Text(
                    text = AppStrings.ui_role_arg0.format(arg0 = device?.localizedRoleName?.takeIf { roleName -> roleName.isNotBlank() } ?: AppStrings.ui_not_set),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = AppStrings.ui_last_connection_arg0.format(arg0 = lastConnectionText),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                if (enabled && showForceClose) {
                    Surface(
                        color = colorScheme.errorContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable(onClick = onForceClose)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = AppStrings.ui_close,
                                modifier = Modifier.size(16.dp),
                                tint = colorScheme.onErrorContainer
                            )
                            Text(
                                text = AppStrings.ui_close,
                                style = typography.bodySmall,
                                color = colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    status: AccessStatusUi
) {
    Row(
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
    }
}

@Composable
private fun rememberAccessStatus(
    category: DeviceCategory,
    device: DeviceJoinDeviceRole?
): AccessStatusUi {
    return when (category) {
        DeviceCategory.SERVER -> {
            when (device?.connectionType) {
                AUTO_CONNECT -> AccessStatusUi(Icons.Default.CheckCircle, AppStrings.ui_automatic_consent, colorScheme.primary)
                PERMANENTLY_BANNED -> AccessStatusUi(Icons.Default.Cancel, AppStrings.ui_always_refuse, colorScheme.error)
                else -> AccessStatusUi(Icons.Default.Schedule, AppStrings.ui_waiting_authorization, colorScheme.tertiary)
            }
        }

        DeviceCategory.CLIENT -> {
            when (device?.connectionType) {
                AUTO_CONNECT -> AccessStatusUi(Icons.Default.CheckCircle, AppStrings.ui_automatically_connect, colorScheme.primary)
                else -> AccessStatusUi(Icons.Default.Schedule, AppStrings.ui_connect_after_asking, colorScheme.tertiary)
            }
        }
    }
}

private fun formatAccessTime(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) {
        return AppStrings.ui_not_connected
    }

    return Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .run { "$date $time" }
}

private suspend fun queryAccessDevices(
    database: FolderSpanDatabase,
    searchQuery: String,
    category: DeviceCategory
): List<DeviceJoinDeviceRole> {
    return database.deviceConnectQueries.queryByNameLikeAndCategory(
        "%$searchQuery%",
        category
    ).executeAsListAwait().map { item ->
        DeviceJoinDeviceRole(
            id = item.id,
            name = item.deviceName ?: "",
            type = item.deviceType ?: DeviceType.JVM,
            connectionType = item.connectionType,
            firstConnection = item.firstConnection,
            lastConnection = item.lastConnection,
            category = item.category,
            roleId = item.roleId,
            roleName = item.roleName ?: ""
        )
    }
}

internal fun buildDeviceDisplayItems(
    devices: List<DbDevice>,
    serverAccessById: Map<String, DeviceJoinDeviceRole>,
    clientAccessDevices: List<DeviceJoinDeviceRole>,
    socketDevices: List<SocketDevice>,
    incomingConnectedDevices: List<DbDevice>,
    searchQuery: String,
    deviceType: DeviceType?
): List<DeviceDisplayItem> {
    val devicesById = devices.associateBy { device -> device.id }
    val clientAccessById = clientAccessDevices.associateBy { device -> device.id }
    val socketDevicesById = socketDevices.groupBy { device -> device.id }
    val incomingConnectedById = incomingConnectedDevices.associateBy { device -> device.id }
    val drawerDeviceOrderById = buildDrawerDeviceOrderById(socketDevices)
    val orderedIds = linkedSetOf<String>().apply {
        addAll(devices.map { device -> device.id })
        addAll(socketDevices.map { device -> device.id })
    }

    return orderedIds.mapNotNull { deviceId ->
        val device = devicesById[deviceId]
        val serverAccess = serverAccessById[deviceId]
        val clientAccess = clientAccessById[deviceId]
        val outgoingConnections = socketDevicesById[deviceId]
            .orEmpty()
            .sortedWith(
                compareByDescending<SocketDevice> { connection -> connection.hasActiveConnection() }
                    .thenBy { connection -> connection.transportType.name }
            )
        val incomingConnected = incomingConnectedById.containsKey(deviceId)
        val discovered = outgoingConnections.firstOrNull()
        val resolvedName = device?.name?.takeIf { storedName -> storedName.isNotBlank() }
            ?: discovered?.name?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val resolvedType = device?.type ?: discovered?.type ?: return@mapNotNull null

        if (deviceType != null && resolvedType != deviceType) {
            return@mapNotNull null
        }

        if (searchQuery.isNotBlank() && !resolvedName.contains(searchQuery, ignoreCase = true)) {
            return@mapNotNull null
        }

        DeviceDisplayItem(
            id = deviceId,
            name = resolvedName,
            type = resolvedType,
            device = device,
            serverAccess = serverAccess,
            clientAccess = clientAccess,
            outgoingConnections = outgoingConnections.toList(),
            hasIncomingConnection = incomingConnected,
            drawerDeviceSortOrder = drawerDeviceOrderById[deviceId]
        )
    }.sortedWith(
        compareByDescending<DeviceDisplayItem> { item -> item.appearsInDrawer }
            .thenBy { item -> item.drawerDeviceSortOrder ?: Int.MAX_VALUE }
            .thenByDescending { item -> item.hasIncomingConnection }
            .thenByDescending { item -> item.isConnected }
            .thenBy { item -> item.name.lowercase() }
    )
}

private fun buildDrawerDeviceOrderById(socketDevices: List<SocketDevice>): Map<String, Int> {
    val orderById = mutableMapOf<String, Int>()

    appDrawerSocketDevicesInDisplayOrder(socketDevices).forEachIndexed { index, socketDevice ->
        if (socketDevice.id !in orderById) {
            orderById[socketDevice.id] = index
        }
    }

    return orderById
}
