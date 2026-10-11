package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.extensions.DeviceIcon
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.ConnectType.*
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.screen.device.DeviceScreen
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileDropEndpoint
import com.folderspan.ui.state.file.FileDropTargetKind
import com.folderspan.ui.components.file.fileDropTarget
import com.folderspan.ui.components.file.fileDropNavigationColors
import com.folderspan.ui.components.file.expandOnFileDragHover
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NetworkState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal fun appDrawerSocketDevicesInDisplayOrder(devices: List<SocketDevice>): List<SocketDevice> {
    val sortedDevices = devices.sortedWith(
        compareByDescending<SocketDevice> { item -> item.hasActiveConnection() }
            .thenBy { item -> item.transportType != DeviceTransportType.Session }
            .thenBy { item -> item.name.lowercase() }
    )

    return sortedDevices.filter { item -> item.transportType == DeviceTransportType.Session } +
        sortedDevices.filter { item -> item.transportType == DeviceTransportType.WebRtc }
}

internal fun appDrawerDeviceAddressInputPrefixes(addresses: List<String>): List<String> =
    addresses.mapNotNull { address ->
        address.takeIf { item -> item.count { char -> char == '.' } == 3 }
            ?.substringBeforeLast('.')
            ?.plus(".")
    }.distinct()

// 通用的设备状态更新函数
private fun updateDeviceStateAndConnect(
    deviceState: DeviceState,
    device: SocketDevice,
    connectType: ConnectType,
    shouldConnect: Boolean = false
) {
    deviceState.updateSocketDeviceConnectType(device, connectType)

    if (shouldConnect) {
        deviceState.connectInBackground(device)
    }
}

internal data class AppDrawerDeviceUiState(
    val currentDesk: DiskBase,
    val isExpanded: Boolean,
    val socketDeviceCount: Int,
    val httpDevices: List<SocketDevice>,
    val webRtcDevices: List<SocketDevice>,
    val pendingSocketDevice: SocketDevice?,
    val onAddDevice: () -> Unit,
    val onManageDevices: () -> Unit,
    val onToggleExpanded: () -> Unit,
    val onDeviceClick: (SocketDevice) -> Unit,
    val onCancelConnection: (SocketDevice) -> Unit,
    val onDismissNewDeviceDialog: () -> Unit,
    val onConnectNewDevice: (SocketDevice, Boolean) -> Unit,
)

@Composable
internal fun rememberAppDrawerDeviceUiState(): AppDrawerDeviceUiState {
    val mainState = koinInject<MainState>()
    val fileState = koinInject<FileState>()
    val currentDesk by fileState.deskType.collectAsState()

    val drawerState = koinInject<DrawerState>()
    val isExpandDevice by drawerState.isExpandDevice.collectAsState()

    val deviceState = koinInject<DeviceState>()
    val networkState = koinInject<NetworkState>()
    val scope = rememberCoroutineScope()
    LaunchedEffect(networkState) {
        networkState.loadPersisted()
    }

    var pendingSocketDevice by remember {
        mutableStateOf<SocketDevice?>(null)
    }
    val socketDevices = deviceState.socketDevices.toList()
    val groupedDevices = remember(socketDevices) {
        val sortedDevices = appDrawerSocketDevicesInDisplayOrder(socketDevices)
        sortedDevices.filter { item -> item.transportType == DeviceTransportType.Session } to
            sortedDevices.filter { item -> item.transportType == DeviceTransportType.WebRtc }
    }

    return AppDrawerDeviceUiState(
        currentDesk = currentDesk,
        isExpanded = isExpandDevice,
        socketDeviceCount = socketDevices.size,
        httpDevices = groupedDevices.first,
        webRtcDevices = groupedDevices.second,
        pendingSocketDevice = pendingSocketDevice,
        onAddDevice = { deviceState.updateDeviceAdd(true) },
        onManageDevices = { mainState.pushScreen(DeviceScreen()) },
        onToggleExpanded = { drawerState.updateExpandDevice(!isExpandDevice) },
        onDeviceClick = { device ->
            when (device.connectType) {
                UnConnect, Fail, Rejected ->
                    updateDeviceStateAndConnect(deviceState, device, Loading, shouldConnect = true)
                New -> if (device.discoveryStatus == DeviceDiscoveryStatus.Unverified) {
                    updateDeviceStateAndConnect(deviceState, device, Loading, shouldConnect = true)
                } else {
                    pendingSocketDevice = device
                }
                Connect -> deviceState.devices
                    .firstOrNull { item -> item.id == device.id }
                    ?.let { connectedDevice -> fileState.updateDesk(FileProtocol.Device, connectedDevice) }
                Loading -> {
                    deviceState.disconnectSocketDevice(device)
                    updateDeviceStateAndConnect(deviceState, device, UnConnect)
                }
            }
        },
        onCancelConnection = { device ->
            if (deviceState.disconnectSocketDevice(device)) {
                updateDeviceStateAndConnect(deviceState, device, UnConnect)
                deviceState.devices.remove(deviceState.devices.firstOrNull { item -> item.id == device.id })
            }
        },
        onDismissNewDeviceDialog = { pendingSocketDevice = null },
        onConnectNewDevice = { socketDevice, isAuto ->
            scope.launch {
                deviceState.confirmNewDeviceConnection(socketDevice, isAuto)
                pendingSocketDevice = null
            }
        },
    )
}

internal fun LazyListScope.appDrawerLocalDropTarget(path: String, hovered: Boolean) {
    item(key = "drawer_local_drop_target", contentType = "drawer_local_drop_target") {
        LocalFileDropRow(path = path, hovered = hovered)
    }
}

@Composable
internal fun LocalFileDropRow(path: String, hovered: Boolean, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(AppStrings.ui_local) },
        supportingContent = {
            Text(if (hovered) AppStrings.ui_drop_copy_received.format(arg0 = path) else AppStrings.ui_drop_copy_local_hint)
        },
        leadingContent = { Icon(Icons.Default.Computer, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = if (hovered) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier.fileDropTarget(FileDropEndpoint(FileDropTargetKind.Local)),
    )
}

internal fun LazyListScope.appDrawerDevice(uiState: AppDrawerDeviceUiState, onHoverExpand: () -> Unit = {}) {
    item(
        key = "drawer_device_header",
        contentType = "drawer_group_header",
    ) {
        AppDrawerHeader(
            title = AppStrings.ui_device_arg0.format(arg0 = (uiState.socketDeviceCount).toString()),
            actions = { AppDrawerDeviceActions(uiState) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
                .expandOnFileDragHover(!uiState.isExpanded, onHoverExpand),
        )
    }

    if (uiState.isExpanded && uiState.httpDevices.isNotEmpty()) {
        item(
            key = "drawer_device_http_label",
            contentType = "drawer_device_section_label",
        ) {
            DeviceTransportSectionLabel(AppStrings.ui_lan_devices)
        }
        items(
            items = uiState.httpDevices,
            key = { device -> "drawer_device_http_${device.id}" },
            contentType = { "drawer_device_row" },
        ) { device ->
            DeviceDrawerStateListItem(
                device = device,
                selected = isSelectedDevice(uiState.currentDesk, device),
                onClick = { uiState.onDeviceClick(device) },
                onCancelConnectionClick = { uiState.onCancelConnection(device) },
            )
        }
    }

    if (uiState.isExpanded && uiState.webRtcDevices.isNotEmpty()) {
        item(
            key = "drawer_device_webrtc_label",
            contentType = "drawer_device_section_label",
        ) {
            DeviceTransportSectionLabel(AppStrings.webrtc_label)
        }
        items(
            items = uiState.webRtcDevices,
            key = { device -> "drawer_device_webrtc_${device.id}" },
            contentType = { "drawer_device_row" },
        ) { device ->
            DeviceDrawerStateListItem(
                device = device,
                selected = isSelectedDevice(uiState.currentDesk, device),
                onClick = { uiState.onDeviceClick(device) },
                onCancelConnectionClick = { uiState.onCancelConnection(device) },
            )
        }
    }

    item(
        key = "drawer_device_footer",
        contentType = "drawer_group_spacing",
    ) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppDrawerDeviceActions(uiState: AppDrawerDeviceUiState) {
    Row {
        Icon(
            Icons.Default.Add,
            null,
            Modifier.clip(RoundedCornerShape(25.dp)).clickable(onClick = uiState.onAddDevice)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Settings,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onManageDevices)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (uiState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = uiState.onToggleExpanded)
        )
    }
}

@Composable
internal fun AppDrawerDeviceDialog(uiState: AppDrawerDeviceUiState) {
    uiState.pendingSocketDevice?.let { socketDevice ->
        DeviceConnectNewDialog(
            socketDevice = socketDevice,
            onConnect = { isAuto -> uiState.onConnectNewDevice(socketDevice, isAuto) },
            onCancel = uiState.onDismissNewDeviceDialog,
        )
    }
}

@Composable
private fun DeviceTransportSectionLabel(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
internal fun DeviceDrawerListItem(
    device: SocketDevice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelConnectionClick: () -> Unit = {},
) {
    NavigationDrawerItem(
        icon = {
            if (device.connectType == Loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                return@NavigationDrawerItem
            }

            device.type.DeviceIcon()
        },
        badge = {
            if (device.discoveryStatus == DeviceDiscoveryStatus.Unverified) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(AppStrings.ui_device_identity_unverified)
                }
                return@NavigationDrawerItem
            }
            when (device.connectType) {
                Connect -> Icon(
                    Icons.Default.Close,
                    AppStrings.ui_cancel_connection,
                    Modifier
                        .clip(RoundedCornerShape(25.dp))
                        .clickable { onCancelConnectionClick() }
                )

                Fail -> Badge { Text(AppStrings.ui_connection_failed) }
                UnConnect -> Badge {
                    Text(if (device.discoveryStatus == DeviceDiscoveryStatus.Trusted) {
                        AppStrings.ui_device_identity_trusted
                    } else AppStrings.ui_not_connected)
                }
                Loading -> Badge(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) { Text(AppStrings.ui_connecting) }

                New -> Badge { Text(AppStrings.ui_new_not_connected) }
                Rejected -> Badge { Text(AppStrings.ui_connection_refused) }
            }
        },
        label = { Text(device.name) },
        selected = selected,
        colors = fileDropNavigationColors(FileDropEndpoint(FileDropTargetKind.Device, device.id)),
        onClick = onClick,
        modifier = modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun DeviceDrawerStateListItem(
    device: SocketDevice,
    selected: Boolean,
    onClick: () -> Unit,
    onCancelConnectionClick: () -> Unit,
) {
    DeviceDrawerListItem(
        device = device,
        selected = selected,
        onClick = onClick,
        onCancelConnectionClick = onCancelConnectionClick,
        modifier = Modifier.fileDropTarget(FileDropEndpoint(FileDropTargetKind.Device, device.id), excludeActions = true),
    )
}

internal fun isSelectedDevice(
    currentDesk: DiskBase,
    device: SocketDevice
): Boolean {
    val currentDevice = currentDesk as? Device ?: return false
    return currentDevice.id == device.id && currentDevice.transportType == device.transportType
}

@Composable
fun DeviceConnectNewDialog(
    socketDevice: SocketDevice,
    onConnect: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onCancel() },
        title = {
            Text(text = AppStrings.ui_connect_new_devices)
        },
        text = {
            Text(text = AppStrings.ui_new_device_arg0_was_found_do_you_want_connect.format(arg0 = socketDevice.name))
        },
        confirmButton = {
            TextButton(onClick = { onConnect(false) }) {
                Text(AppStrings.ui_connect)
            }
            TextButton(onClick = { onConnect(true) }) {
                Text(AppStrings.ui_automatically_connect)
            }
        },
        dismissButton = {
            TextButton(onClick = { onCancel() }) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}
