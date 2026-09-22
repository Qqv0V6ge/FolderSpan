package com.folderspan.ui.screen.settings

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.toServerStartFailureMessage
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.dialog.EasyFileSharePathSelectorDialog
import com.folderspan.ui.components.dialog.TextFieldDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 设置 -> 快捷分享（二级页面）
 */
class EasyFileShareSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val settingsState = koinInject<SettingsState>()
        val fileShareState = koinInject<FileShareState>()
        val httpShareFileServer = remember(fileShareState) { HttpShareFileServer.getInstance(fileShareState) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val easyFileSharePort by settingsState.easyFileSharePort.collectAsState()
        val easyFileShareAutoStart by settingsState.easyFileShareAutoStart.collectAsState()
        val easyFileShareAutoStartOnOpen by settingsState.easyFileShareAutoStartOnOpen.collectAsState()
        val easyFileShareSharePaths by settingsState.easyFileShareSharePaths.collectAsState()
        val easyFileShareAutoApprove by settingsState.easyFileShareAutoApprove.collectAsState()
        val easyFileSharePasswordAccess by settingsState.easyFileSharePasswordAccess.collectAsState()
        val easyFileShareEncryption by settingsState.easyFileShareEncryption.collectAsState()
        val easyFileShareHideFile by settingsState.easyFileShareHideFile.collectAsState()
        val easyFileShareAutoStopOnExit by settingsState.easyFileShareAutoStopOnExit.collectAsState()
        val easyFileShareTapToSend by settingsState.easyFileShareTapToSend.collectAsState()
        val easyFileShareDeviceHideFile by settingsState.easyFileShareDeviceHideFile.collectAsState()
        val easyFileShareAllowDeviceShare by settingsState.easyFileShareAllowDeviceShare.collectAsState()
        val easyFileShareAllowUpload by settingsState.easyFileShareAllowUpload.collectAsState()
        val easyFileShareAutoUpdateLinkShareFiles by
            settingsState.easyFileShareAutoUpdateLinkShareFiles.collectAsState()
        val easyFileShareAutoUpdateDeviceShareFiles by
            settingsState.easyFileShareAutoUpdateDeviceShareFiles.collectAsState()

        val showEditPortDialog = remember { mutableStateOf(false) }
        var showSharePathSelector by remember { mutableStateOf(false) }
        val shouldShowSharePaths = easyFileShareAutoStart || easyFileShareAutoStartOnOpen

        LaunchedEffect(shouldShowSharePaths) {
            if (!shouldShowSharePaths) {
                showSharePathSelector = false
            }
        }

        fun showSnackbar(
            message: String,
            duration: SnackbarDuration = SnackbarDuration.Short
        ) {
            scope.launch {
                snackbarHostState.showLatestSnackbar(
                    message = message,
                    duration = duration
                )
            }
        }

        fun showAutoStartRestartRequired(enabled: Boolean) {
            showSnackbar(
                message = if (enabled) {
                    AppStrings.ui_automatic_startup_enabled_will_take_effect_after_restarting_application
                } else {
                    AppStrings.ui_automatic_startup_turned_off_will_take_effect_after_restarting
                }
            )
        }

        fun showPortUpdateFeedback(port: Int) {
            if (!httpShareFileServer.isRunning()) return

            scope.launch {
                val result = snackbarHostState.showLatestSnackbar(
                    message = AppStrings.ui_port_has_been_updated_service_currently_running_do_you,
                    actionLabel = AppStrings.ui_restart,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long
                )
                if (result != SnackbarResult.ActionPerformed) return@launch

                val restartResult = runCatching {
                    withContext(Dispatchers.Default) {
                        httpShareFileServer.stop()
                        httpShareFileServer.start(port)
                    }
                }
                snackbarHostState.showLatestSnackbar(
                    message = restartResult.fold(
                        onSuccess = { AppStrings.ui_service_has_been_restarted_using_new_port },
                        onFailure = { throwable ->
                            AppStrings.ui_service_restart_failed_arg0.format(arg0 = throwable.toServerStartFailureMessage(port))
                        }
                    ),
                    duration = if (restartResult.isSuccess) SnackbarDuration.Short else SnackbarDuration.Long
                )
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_quick_sharing) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 链接方式分享 - 子标题头部
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_share_link,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 端口设置
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_service_port) },
                        supportingContent = { Text(AppStrings.ui_current_port_arg0.format(arg0 = (easyFileSharePort).toString())) },
                        trailingContent = {
                            IconButton(onClick = { showEditPortDialog.value = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = AppStrings.ui_edit_port)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 自动启动开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_automatic_start) },
                        supportingContent = { Text(AppStrings.ui_automatically_start_quick_sharing_service_when_application_starts) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoStart,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAutoStart(enabled)
                                    showAutoStartRestartRequired(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                if (shouldShowSharePaths) {
                    // 默认分享路径
                    item {
                        ListItem(
                            headlineContent = { Text(AppStrings.ui_default_sharing_path) },
                            supportingContent = {
                                if (easyFileShareSharePaths.isEmpty()) {
                                    Text(AppStrings.ui_no_default_sharing_path_selected_automatic_startup_will_fail)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            AppStrings.ui_modification_only_affects_new_link_sharing_connections,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        easyFileShareSharePaths.forEach { path ->
                                            Text(path, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { showSharePathSelector = true }) {
                                    Icon(Icons.Filled.Edit, contentDescription = AppStrings.ui_edit_default_sharing_path)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { showSharePathSelector = true }
                        )
                    }
                }

                // 打开页面自动启动
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_automatically_start_when_opening_page) },
                        supportingContent = { Text(AppStrings.ui_start_the_service_when_opening_quick_share_mutually_exclusive_with_start) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoStartOnOpen,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAutoStartOnOpen(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 离开页面自动关闭服务
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_close_page_automatically_close_service) },
                        supportingContent = { Text(AppStrings.ui_automatically_stop_quick_sharing_service_when_leaving_sharing_page) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoStopOnExit,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAutoStopOnExit(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 默认自动允许开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_automatically_allowed_default) },
                        supportingContent = { Text(AppStrings.ui_set_auto_allow_default_state_new_connections_mutually_exclusive) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoApprove,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (easyFileSharePasswordAccess) {
                                            // 如果要启用自动允许，先关闭密码访问
                                            settingsState.setEasyFileSharePasswordAccess(false)
                                        }
                                    }
                                    settingsState.setEasyFileShareAutoApprove(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 默认密码访问开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_default_password_access) },
                        supportingContent = { Text(AppStrings.ui_set_password_access_default_state_new_connections_mutually_exclusive) },
                        trailingContent = {
                            Switch(
                                checked = easyFileSharePasswordAccess,
                                onCheckedChange = { enabled ->
                                    if (enabled && easyFileShareAutoApprove) {
                                        // 如果要启用密码访问，先关闭自动允许
                                        settingsState.setEasyFileShareAutoApprove(false)
                                    }
                                    settingsState.setEasyFileSharePasswordAccess(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 默认加密开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_encryption_enabled_default) },
                        supportingContent = { Text(AppStrings.ui_set_default_state_encryption_option) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareEncryption,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareEncryption(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 默认隐藏文件开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_allow_access_hidden_files_default) },
                        supportingContent = { Text(AppStrings.ui_set_whether_allow_access_hidden_files_folders_default) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareHideFile,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareHideFile(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 允许上传开关
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_allow_upload) },
                        supportingContent = { Text(AppStrings.ui_allow_authorized_link_visitors_upload_files_folders_current_browsing) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAllowUpload,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAllowUpload(enabled)
                                    fileShareState.updateAllowUpload(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 自动更新已授权链接分享设备的文件列表
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_auto_update_link_share_files) },
                        supportingContent = {
                            Text(AppStrings.ui_auto_update_link_share_files_description)
                        },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoUpdateLinkShareFiles,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAutoUpdateLinkShareFiles(enabled)
                                    if (enabled) {
                                        fileShareState.syncAuthorizedLinkShareFiles(
                                            fileShareState.files.toList()
                                        )
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 分享到其他设备 - 子标题头部
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_share_other_devices,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 允许其他设备分享给我
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_allow_other_devices_share_me) },
                        supportingContent = { Text(AppStrings.ui_when_turned_off_sharing_requests_unsaved_devices_will_automatically) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAllowDeviceShare,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAllowDeviceShare(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 点击设备即发送
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_click_device_send) },
                        supportingContent = { Text(AppStrings.ui_after_turning_it_clicking_target_device_will_immediately_initiate) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareTapToSend,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareTapToSend(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 自动更新已分享设备的文件列表
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_auto_update_device_share_files) },
                        supportingContent = {
                            Text(AppStrings.ui_auto_update_device_share_files_description)
                        },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareAutoUpdateDeviceShareFiles,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareAutoUpdateDeviceShareFiles(enabled)
                                    if (enabled) {
                                        fileShareState.syncShareToDeviceFiles(
                                            fileShareState.files.toList()
                                        )
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // 默认隐藏文件开关（分享到其他设备）
                item {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_device_allows_hidden_files_default) },
                        supportingContent = { Text(AppStrings.ui_whether_allow_access_hidden_files_folders_when_sharing_other) },
                        trailingContent = {
                            Switch(
                                checked = easyFileShareDeviceHideFile,
                                onCheckedChange = { enabled ->
                                    settingsState.setEasyFileShareDeviceHideFile(enabled)
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        if (shouldShowSharePaths && showSharePathSelector) {
            val presetShareFiles = remember(easyFileShareSharePaths) {
                easyFileShareSharePaths.mapNotNull { item ->  FileUtils.getFile(FileAccessPermission.Allowed, item).getOrNull() }
            }
            EasyFileSharePathSelectorDialog(
                openPath = PathUtils.getHomePath(),
                initialSelectionUiState = FileSelectionUiState(presetShareFiles),
                onConfirm = { selectionUiState ->
                    settingsState.setEasyFileShareSharePaths(selectionUiState.files.map { item -> item.path })
                    showSharePathSelector = false
                    showSnackbar(AppStrings.ui_default_sharing_path_has_been_updated_only_affecting_new)
                },
                onClear = {
                    settingsState.setEasyFileShareSharePaths(emptyList())
                    showSharePathSelector = false
                    showSnackbar(AppStrings.ui_default_sharing_path_has_been_cleared_only_affecting_new)
                },
                onDismiss = { showSharePathSelector = false }
            )
        }

        // 端口编辑对话框
        if (showEditPortDialog.value) {
            TextFieldDialog(
                title = AppStrings.ui_modify_service_port,
                label = AppStrings.ui_port_number,
                initText = easyFileSharePort.toString(),
                verifyFun = { text ->
                    val port = text.toIntOrNull()
                    when {
                        text.isBlank() -> Pair(true, AppStrings.ui_port_number_cannot_empty)
                        port == null -> Pair(true, AppStrings.ui_invalid_port)
                        port !in 1024..65535 -> Pair(true, AppStrings.ui_port_number_range_1024_65535)
                        else -> Pair(false, "")
                    }
                }
            ) { text ->
                showEditPortDialog.value = false
                val port = text.toIntOrNull()
                if (port != null && port in 1024..65535) {
                    val oldPort = easyFileSharePort
                    settingsState.setEasyFileSharePort(port)
                    if (port != oldPort) {
                        showPortUpdateFeedback(port)
                    }
                }
            }
        }
    }
}
