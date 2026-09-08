package com.folderspan.ui.tray

import strings.AppStrings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.ApplicationScope
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.extensions.randomString
import com.folderspan.openUrl
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.ConnectType.*
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.screen.settings.SettingsScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.state.settings.ThemeMode
import com.folderspan.ui.theme.CustomColorScheme
import com.folderspan.ui.theme.getDefaultColorScheme
import com.folderspan.ui.theme.parseHexColor
import com.folderspan.ui.theme.withCustomColors
import com.folderspan.utils.SettingsUtils
import com.kdroid.composetray.tray.api.Tray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun ApplicationScope.FolderSpanTray(
    isTraySupported: Boolean,
    onOpenApp: () -> Unit,
    onRequestNewDevice: (SocketDevice) -> Unit,
    onExit: () -> Unit
) {
    if (!isTraySupported) return

    val mainState = koinInject<MainState>()
    val deviceState = koinInject<DeviceState>()
    val fileState = koinInject<FileState>()
    val fileShareState = koinInject<FileShareState>()
    val settingsState = koinInject<SettingsState>()
    val themeMode by settingsState.themeMode.collectAsState()
    val customColorEnabled by settingsState.customColorEnabled.collectAsState()
    val customSeedHex by settingsState.customSeedColor.collectAsState()
    val remoteColorScheme by mainState.remoteColorScheme.collectAsState()
    val systemDarkTheme = isSystemInDarkTheme()
    val currentDarkTheme = when (themeMode) {
        ThemeMode.System -> systemDarkTheme
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val localTrayIconColor = remember(currentDarkTheme, customColorEnabled, customSeedHex) {
        val baseColorScheme = getDefaultColorScheme(currentDarkTheme)
        val customSeedColor = parseHexColor(customSeedHex)
        if (customColorEnabled && customSeedColor != null) {
            baseColorScheme.withCustomColors(
                customColors = CustomColorScheme(seed = customSeedColor),
                darkTheme = currentDarkTheme,
            ).primary
        } else {
            baseColorScheme.primary
        }
    }
    val trayIconColor = remoteColorScheme?.primary ?: localTrayIconColor
    val trayIconFiles = remember(trayIconColor) { renderFolderSpanTrayIconFiles(trayIconColor) }
    val httpShareFileServer = HttpShareFileServer.getInstance(fileShareState)
    val isServerRunning by fileShareState.isHttpServerRunning.collectAsState()
    val autoApprove by fileShareState.autoApprove.collectAsState()
    val connectPassword by fileShareState.connectPassword.collectAsState()
    var isStoppingService by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun clearDeviceAccessLog(device: Device) {
        val log = fileShareState.deviceRequestLog.remove(device.id) ?: return
        scope.launch(Dispatchers.Default) {
            log.clear()
        }
    }

    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    fun buildLinkShareUrl(address: String): String {
        return "http://$address:${SettingsUtils.easyFileShare.getPort()}/"
    }

    fun enableLinkSharePasswordAccess(clearRuntimeAuthorizations: Boolean) {
        if (autoApprove) {
            fileShareState.updateAutoApprove(false)
        }
        fileShareState.updateConnectPassword(
            value = 6.randomString(includeSpecial = false),
            clearRuntimeAuthorizations = clearRuntimeAuthorizations
        )
    }

    fun approvePendingLinkDevice(device: Device) {
        fileShareState.approveLinkShareDevice(device.id)
    }

    fun rejectPendingLinkDevice(device: Device) {
        fileShareState.rejectLinkShareDevice(device.id)
    }

    fun rejectAuthorizedLinkDevice(device: Device) {
        val removedAuthorized = fileShareState.rejectAuthorizedLinkShareDevice(device)
        if (removedAuthorized) {
            clearDeviceAccessLog(device)
        }
    }

     fun enableUploadForLinkDevice(device: Device) {
        fileShareState.approveLinkShareUploadDevice(device.id)
    }

    fun disableUploadForLinkDevice(device: Device) {
        fileShareState.updateAuthorizedLinkShareUpload(device.id, allowUpload = false)
    }

    fun rejectUploadForLinkDevice(device: Device) {
        fileShareState.rejectLinkShareUploadDevice(device.id)
    }

    fun deleteRejectedLinkDevice(device: Device) {
        fileShareState.rejectedLinkShareDevices.remove(device)
    }

    fun deleteRejectedUploadLinkDevice(device: Device) {
        fileShareState.removeRejectedLinkShareUploadDevice(device.id)
    }

    fun updateDeviceStateAndConnect(
        device: SocketDevice,
        connectType: ConnectType,
        shouldConnect: Boolean = false
    ) {
        fun updateDeviceConnectType(deviceId: String, newConnectType: ConnectType) {
            deviceState.devices.removeAll { item ->  item.id == deviceId }
            val updatedDevices = deviceState.socketDevices.map { item ->
                if (item.id == deviceId) item.withCopy(connectType = newConnectType) else item
            }
            deviceState.socketDevices.clear()
            deviceState.socketDevices.addAll(updatedDevices)
        }

        updateDeviceConnectType(device.id, connectType)

        if (shouldConnect) {
            scope.launch {
                try {
                    deviceState.connect(device)
                } catch (e: Exception) {
                    updateDeviceConnectType(device.id, Fail)
                    fileState.updateDesk(FileProtocol.Local, Local())
                }
            }
        }
    }

    // File-path overload avoids ComposeNativeTray's older Skia renderer; icon paths must be file URIs.
    @Suppress("DEPRECATION")
    Tray(
        iconPath = trayIconFiles.iconPath,
        windowsIconPath = trayIconFiles.windowsIconPath,
        tooltip = "FolderSpan",
        primaryAction = { onOpenApp() }
    ) {
        SubMenu(label = AppStrings.ui_device_arg0.format(arg0 = (deviceState.socketDevices.size).toString())) {
            val connectedDevices = deviceState.socketDevices.filter { item ->  item.connectType == Connect }
            SubMenu(label = AppStrings.ui_connected_device_arg0.format(arg0 = (connectedDevices.size).toString())) {
                if (connectedDevices.isEmpty()) {
                    Item(label = AppStrings.ui_no_connected_devices_yet, isEnabled = false)
                } else {
                    connectedDevices.forEach { device ->
                        SubMenu(label = device.name) {
                            Item(label = AppStrings.ui_open) {
                                onOpenApp()
                                deviceState.devices.firstOrNull { item ->  item.id == device.id }?.let { item ->
                                    fileState.updateDesk(FileProtocol.Device, item)
                                }
                            }
                            Item(label = AppStrings.ui_disconnect) {
                                if (device.httpClient?.disconnect() == true) {
                                    updateDeviceStateAndConnect(device, UnConnect)
                                    deviceState.devices.remove(
                                        deviceState.devices.firstOrNull { item ->  item.id == device.id }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val unconnectedDevices = deviceState.socketDevices.filter { item ->  item.connectType != Connect }
            SubMenu(label = AppStrings.ui_device_not_connected_arg0.format(arg0 = (unconnectedDevices.size).toString())) {
                if (unconnectedDevices.isEmpty()) {
                    Item(label = AppStrings.ui_no_unconnected_devices_yet, isEnabled = false)
                } else {
                    unconnectedDevices.forEach { device ->
                        val statusLabel = when (device.connectType) {
                            UnConnect -> AppStrings.ui_not_connected
                            Fail -> AppStrings.ui_connection_failed
                            Loading -> AppStrings.ui_connecting
                            New -> AppStrings.ui_new_not_connected
                            Rejected -> AppStrings.ui_connection_refused
                            Connect -> AppStrings.ui_connected
                        }
                        Item(label = "${device.name} ($statusLabel)") {
                            when (device.connectType) {
                                UnConnect, Fail, Rejected -> {
                                    updateDeviceStateAndConnect(
                                        device,
                                        Loading,
                                        shouldConnect = true
                                    )
                                }

                                New -> {
                                    onOpenApp()
                                    onRequestNewDevice(device)
                                }

                                Loading -> {
                                    device.httpClient?.disconnect()
                                    updateDeviceStateAndConnect(device, UnConnect)
                                }

                                Connect -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (!isServerRunning) {
            Item(label = AppStrings.ui_simple_sharing_not_turned) {
                onOpenApp()
                mainState.requestOpenScreen(FileShareScreen)
            }
        } else {
            SubMenu(label = AppStrings.ui_easy_sharing_enabled) {
                Item(label = AppStrings.ui_open_page) {
                    onOpenApp()
                    mainState.requestOpenScreen(FileShareScreen)
                }
                val addresses = getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP)
                SubMenu(label = AppStrings.ui_access_address) {
                    if (addresses.isEmpty()) {
                        Item(label = AppStrings.ui_no_available_address_yet, isEnabled = false)
                    } else {
                        addresses.forEach { address ->
                            val url = buildLinkShareUrl(address)
                            SubMenu(label = address) {
                                Item(label = AppStrings.ui_open_browser) {
                                    openUrl(url)
                                }
                                Item(label = AppStrings.ui_copy_clipboard) {
                                    copyToClipboard(url)
                                }
                            }
                        }
                    }
                }
                SubMenu(label = AppStrings.ui_licensing_restrictions) {
                    CheckableItem(
                        label = AppStrings.ui_automatically_allow,
                        checked = autoApprove,
                        onCheckedChange = { newValue ->
                            if (newValue && connectPassword.isNotEmpty()) {
                                fileShareState.updateConnectPassword("")
                            }
                            fileShareState.updateAutoApprove(newValue)
                        }
                    )
                    if (connectPassword.isNotEmpty()) {
                        CheckableItem(
                            label = AppStrings.ui_password_access,
                            checked = true,
                            onCheckedChange = { newValue ->
                                if (!newValue) {
                                    fileShareState.updateConnectPassword("")
                                }
                            }
                        )
                    } else {
                        SubMenu(label = AppStrings.ui_password_access) {
                            Item(label = AppStrings.ui_enable_clean_old_authorizations) {
                                enableLinkSharePasswordAccess(clearRuntimeAuthorizations = true)
                            }
                            Item(label = AppStrings.ui_enable_but_keep_old_authorization) {
                                enableLinkSharePasswordAccess(clearRuntimeAuthorizations = false)
                            }
                        }
                    }
                }
                SubMenu(label = AppStrings.ui_authorized_device) {
                    val pendingDevices = fileShareState.pendingLinkShareDevices.toList()
                    val authorizedDevices = fileShareState.authorizedLinkShareDevices.keys.toList()
                    val rejectedDevices = fileShareState.rejectedLinkShareDevices.toList()
                    val pendingUploadDevices = fileShareState.pendingLinkShareUploadDevices.toList()
                    val rejectedUploadDevices = fileShareState.rejectedLinkShareUploadDevices.toList()

                    SubMenu(label = AppStrings.ui_wait_arg0.format(arg0 = (pendingDevices.size).toString())) {
                        if (pendingDevices.isEmpty()) {
                            Item(label = AppStrings.ui_no_equipment_yet, isEnabled = false)
                        } else {
                            pendingDevices.forEach { device ->
                                SubMenu(label = "${device.name} (${device.id})") {
                                    Item(label = AppStrings.ui_allow) { approvePendingLinkDevice(device) }
                                    Item(label = AppStrings.android_action_reject) { rejectPendingLinkDevice(device) }
                                }
                            }
                        }
                    }
                    SubMenu(label = AppStrings.ui_allow_arg0.format(arg0 = (authorizedDevices.size).toString())) {
                        if (authorizedDevices.isEmpty()) {
                            Item(label = AppStrings.ui_no_equipment_yet, isEnabled = false)
                        } else {
                            authorizedDevices.forEach { device ->
                                SubMenu(label = "${device.name} (${device.id})") {
                                    val access = fileShareState.getAuthorizedLinkShareDevice(device.id)
                                    if (access?.allowUpload == true) {
                                        Item(label = AppStrings.ui_upload_prohibited) { disableUploadForLinkDevice(device) }
                                    } else {
                                        Item(label = AppStrings.ui_allow_upload) { enableUploadForLinkDevice(device) }
                                    }
                                    Item(label = AppStrings.android_action_reject) { rejectAuthorizedLinkDevice(device) }
                                }
                            }
                        }
                    }
                    SubMenu(label = AppStrings.ui_upload_request_arg0.format(arg0 = (pendingUploadDevices.size).toString())) {
                        if (pendingUploadDevices.isEmpty()) {
                            Item(label = AppStrings.ui_no_equipment_yet, isEnabled = false)
                        } else {
                            pendingUploadDevices.forEach { device ->
                                SubMenu(label = "${device.name} (${device.id})") {
                                    Item(label = AppStrings.ui_allow_upload) { enableUploadForLinkDevice(device) }
                                    Item(label = AppStrings.ui_refuse_upload) { rejectUploadForLinkDevice(device) }
                                }
                            }
                        }
                    }
                    SubMenu(label = AppStrings.ui_upload_rejected_arg0.format(arg0 = (rejectedUploadDevices.size).toString())) {
                        if (rejectedUploadDevices.isEmpty()) {
                            Item(label = AppStrings.ui_no_equipment_yet, isEnabled = false)
                        } else {
                            rejectedUploadDevices.forEach { device ->
                                SubMenu(label = "${device.name} (${device.id})") {
                                    Item(label = AppStrings.android_action_delete) { deleteRejectedUploadLinkDevice(device) }
                                }
                            }
                        }
                    }
                    SubMenu(label = AppStrings.ui_reject_arg0.format(arg0 = (rejectedDevices.size).toString())) {
                        if (rejectedDevices.isEmpty()) {
                            Item(label = AppStrings.ui_no_equipment_yet, isEnabled = false)
                        } else {
                            rejectedDevices.forEach { device ->
                                SubMenu(label = "${device.name} (${device.id})") {
                                    Item(label = AppStrings.android_action_delete) { deleteRejectedLinkDevice(device) }
                                }
                            }
                        }
                    }
                }
                Divider()
                SubMenu(label = if (isStoppingService) AppStrings.ui_close_service_closed else AppStrings.ui_close_service) {
                    Item(label = if (isStoppingService) AppStrings.ui_closed else AppStrings.ui_confirm_close, isEnabled = !isStoppingService) {
                        if (isStoppingService) return@Item
                        scope.launch(Dispatchers.Default) {
                            isStoppingService = true
                            httpShareFileServer.stop()
                            fileShareState.clearLinkShareRuntimeAuthorizations()
                            isStoppingService = false
                        }
                    }
                }
            }
        }
        Divider()
        Item(label = AppStrings.settings_title) {
            onOpenApp()
            val isSettingsContext = mainState.currentRoute?.let { route ->
                val routeName = route::class.qualifiedName.orEmpty()
                routeName.startsWith("com.folderspan.ui.screen.settings.") ||
                    route::class.simpleName.orEmpty().endsWith("SettingsScreen")
            } ?: false
            if (!isSettingsContext) {
                mainState.requestOpenScreen(SettingsScreen())
            }
        }
        Divider()
        Item(label = AppStrings.ui_exit) {
            dispose()
            onExit()
        }
    }
}
