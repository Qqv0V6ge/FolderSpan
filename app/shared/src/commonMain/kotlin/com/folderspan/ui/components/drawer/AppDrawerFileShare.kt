package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val FILE_SHARE_ADDRESS_REFRESH_INTERVAL_MS = 10_000L

internal data class AppDrawerFileShareUiState(
    val isVisible: Boolean,
    val address: String,
    val port: Int,
    val filesCount: Int,
    val connectedDevicesCount: Int,
    val isStoppingService: Boolean,
    val onOpen: () -> Unit,
    val onStopService: () -> Unit,
)

@Composable
internal fun rememberAppDrawerFileShareUiState(): AppDrawerFileShareUiState {
    val mainState = koinInject<MainState>()
    val fileShareState = koinInject<FileShareState>()
    val settingsState = koinInject<SettingsState>()
    val httpShareFileServer = HttpShareFileServer.getInstance(fileShareState)
    val scope = rememberCoroutineScope()

    val isServerRunning by fileShareState.isHttpServerRunning.collectAsState()
    val fileSharePort by settingsState.easyFileSharePort.collectAsState()
    val isDrawerExpanded by mainState.isExpandDrawer.collectAsState()
    var isStoppingService by remember { mutableStateOf(false) }

    val address by produceState(
        initialValue = "localhost",
        key1 = isDrawerExpanded,
    ) {
        if (!isDrawerExpanded) return@produceState
        while (isActive) {
            value = withContext(Dispatchers.Default) {
                getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP).firstOrNull() ?: "localhost"
            }
            delay(FILE_SHARE_ADDRESS_REFRESH_INTERVAL_MS)
        }
    }
    val connectedDevicesCount = fileShareState.authorizedLinkShareDevices.size
    val filesCount = fileShareState.files.size

    return AppDrawerFileShareUiState(
        isVisible = isServerRunning,
        address = address,
        port = fileSharePort,
        filesCount = filesCount,
        connectedDevicesCount = connectedDevicesCount,
        isStoppingService = isStoppingService,
        onOpen = { mainState.pushScreen(FileShareScreen) },
        onStopService = {
            if (!isStoppingService) {
                scope.launch {
                    isStoppingService = true
                    try {
                        withContext(Dispatchers.Default) {
                            httpShareFileServer.stop()
                        }
                        fileShareState.clearLinkShareRuntimeAuthorizations()
                    } finally {
                        isStoppingService = false
                    }
                }
            }
        },
    )
}

@Composable
internal fun AppDrawerFileShare(uiState: AppDrawerFileShareUiState) {
    if (!uiState.isVisible) return
    var showStopServiceDialog by remember { mutableStateOf(false) }

    AppDrawerItem(AppStrings.ui_link_sharing, actions = {}) {
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Share, null) },
            label = {
                Column {
                    Text("${uiState.address}:${uiState.port}")
                    Text(
                        AppStrings.ui_number_shares_arg0_connections_arg1.format(
                            arg0 = uiState.filesCount.toString(),
                            arg1 = uiState.connectedDevicesCount.toString(),
                        ),
                        style = typography.bodySmall
                    )
                }
            },
            selected = false,
            onClick = uiState.onOpen,
            badge = {
                IconButton(
                    onClick = {
                        showStopServiceDialog = true
                    }
                ) {
                    Icon(
                        Icons.Default.Close,
                        AppStrings.ui_close_service
                    )
                }
            },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }

    // 关闭服务确认对话框
    if (showStopServiceDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isStoppingService) {
                    showStopServiceDialog = false
                }
            },
            title = { Text(AppStrings.ui_confirm_close_service) },
            text = { Text(AppStrings.ui_you_sure_you_want_turn_off_file_sharing_service) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (uiState.isStoppingService) return@TextButton
                        uiState.onStopService()
                    },
                    enabled = !uiState.isStoppingService
                ) {
                    if (uiState.isStoppingService) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(AppStrings.ui_closed)
                        }
                    } else {
                        Text(AppStrings.ui_close_service)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStopServiceDialog = false },
                    enabled = !uiState.isStoppingService
                ) {
                    Text(AppStrings.ui_cancel)
                }
            }
        )
    }

    // 添加分隔线
    HorizontalDivider()
}
