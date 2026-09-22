package com.folderspan.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.crash.exitApp
import com.folderspan.data.main.Local
import com.folderspan.extensions.randomString
import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.isValidFileShareAccessKeyValue
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.dialog.DeviceRoleSelectionDialog
import com.folderspan.ui.components.dialog.PathSelectorDialog
import com.folderspan.ui.components.dialog.TextFieldDialog
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.model.buildDeviceRoleOptionsUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings
import com.folderspan.localization.localizedName

private const val GENERATED_FILE_SHARE_ACCESS_KEY_LENGTH = 32

/**
 * 设置 -> 文件共享（二级页面）
 */
class FileShareSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val settingsState = koinInject<SettingsState>()
        val roleState = koinInject<DeviceRoleState>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val fileShareEnabled by settingsState.fileShareEnabled.collectAsState()
        val fileSharePort by settingsState.fileSharePort.collectAsState()
        val fileShareAccessKeyConfig by settingsState.fileShareAccessKeyConfig.collectAsState()
        val fileShareAutoAuthorizeDeviceConnect by settingsState.fileShareAutoAuthorizeDeviceConnect.collectAsState()
        val fileShareAutoAuthorizeRoleId by settingsState.fileShareAutoAuthorizeRoleId.collectAsState()
        val fileShareAccountDeviceAutoConnectEnabled by
            settingsState.fileShareAccountDeviceAutoConnectEnabled.collectAsState()
        val remoteOpenConfirmEnabled by settingsState.remoteOpenConfirmEnabled.collectAsState()
        val remoteOpenDownloadDirectory by settingsState.remoteOpenDownloadDirectory.collectAsState()
        val displayDownloadDirectory = remoteOpenDownloadDirectory.ifBlank { PathUtils.getCachePath() }
        val autoAuthorizeRoleName = roleState.roles
            .firstOrNull { item -> item.id == fileShareAutoAuthorizeRoleId }
            ?.localizedName
            ?: AppStrings.ui_tourists

        val showEditPortDialog = remember { mutableStateOf(false) }

        var showAutoAuthorizeRoleDialog by remember { mutableStateOf(false) }
        var showRemoteOpenDirectorySelector by remember { mutableStateOf(false) }
        var showAccessKeyDialog by remember { mutableStateOf(false) }
        var enableAccessKeyAfterEdit by remember { mutableStateOf(false) }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_file_sharing) },
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
                // 启用文件共享开关
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_enable_file_sharing) },
                        supportingContent = { Text(AppStrings.ui_once_enabled_device_files_can_accessed_through_network) },
                        trailingContent = {
                            Switch(
                                checked = fileShareEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        val result = snackbarHostState.showLatestSnackbar(
                                            message = if (enabled) AppStrings.ui_after_enabling_file_sharing_you_need_restart_application_take
                                                      else AppStrings.ui_after_turning_off_file_sharing_you_need_restart_application,
                                            actionLabel = AppStrings.ui_ok,
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            settingsState.setFileShareEnabled(enabled)
                                        }
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                }

                // 端口设置
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_service_port) },
                        supportingContent = { Text(AppStrings.ui_current_port_arg0.format(arg0 = (fileSharePort).toString())) },
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

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_lan_access_protection,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_enable_access_key_verification) },
                        supportingContent = {
                            Text(AppStrings.ui_matching_access_key_required_for_file_sharing_requests)
                        },
                        trailingContent = {
                            Switch(
                                checked = fileShareAccessKeyConfig.enabled,
                                onCheckedChange = { enabled ->
                                    if (!enabled || fileShareAccessKeyConfig.hasValidValue()) {
                                        settingsState.setFileShareAccessKeyConfig(
                                            fileShareAccessKeyConfig.copy(enabled = enabled)
                                        )
                                    } else {
                                        enableAccessKeyAfterEdit = true
                                        showAccessKeyDialog = true
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_file_sharing_access_key) },
                        supportingContent = {
                            Text(
                                if (fileShareAccessKeyConfig.value.isBlank()) {
                                    AppStrings.ui_access_key_not_set
                                } else {
                                    "••••••••"
                                }
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    enableAccessKeyAfterEdit = false
                                    showAccessKeyDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = AppStrings.ui_edit_access_key
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                enableAccessKeyAfterEdit = false
                                showAccessKeyDialog = true
                            }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_automatic_authorization,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_automatically_grant_device_permission_connect) },
                        supportingContent = { Text(AppStrings.ui_device_connection_automatically_granted_when_turned) },
                        trailingContent = {
                            Switch(
                                checked = fileShareAutoAuthorizeDeviceConnect,
                                onCheckedChange = settingsState::setFileShareAutoAuthorizeDeviceConnect
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_account_device_auto_connect) },
                        supportingContent = { Text(AppStrings.ui_account_device_auto_connect_description) },
                        trailingContent = {
                            Switch(
                                checked = fileShareAccountDeviceAutoConnectEnabled,
                                onCheckedChange = settingsState::setFileShareAccountDeviceAutoConnectEnabled,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_select_role) },
                        supportingContent = { Text(AppStrings.ui_current_role_arg0.format(arg0 = autoAuthorizeRoleName)) },
                        trailingContent = {
                            IconButton(
                                onClick = { showAutoAuthorizeRoleDialog = true },
                                enabled = roleState.roles.isNotEmpty()
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = AppStrings.ui_select_role)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = roleState.roles.isNotEmpty()) {
                                showAutoAuthorizeRoleDialog = true
                            }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = AppStrings.ui_remote_file_open,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_tips_before_opening) },
                        supportingContent = { Text(AppStrings.ui_prompt_download_before_opening_remote_file) },
                        trailingContent = {
                            Switch(
                                checked = remoteOpenConfirmEnabled,
                                onCheckedChange = settingsState::setRemoteOpenConfirmEnabled
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_download_catalog) },
                        supportingContent = { Text(displayDownloadDirectory) },
                        trailingContent = {
                            IconButton(onClick = { showRemoteOpenDirectorySelector = true }) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = AppStrings.ui_select_directory)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { showRemoteOpenDirectorySelector = true }
                    )
                }

            }
        }

        // 端口编辑对话框
        if (showEditPortDialog.value) {
            TextFieldDialog(
                title = AppStrings.ui_modify_service_port,
                label = AppStrings.ui_port_number,
                initText = fileSharePort.toString(),
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
                    scope.launch {
                        settingsState.setFileSharePort(port)
                        val result = snackbarHostState.showLatestSnackbar(
                            message = AppStrings.ui_port_has_been_updated_do_you_want_close_application,
                            actionLabel = AppStrings.ui_close,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            exitApp()
                        }
                    }
                }
            }
        }

        if (showAutoAuthorizeRoleDialog) {
            val initialRoleId = roleState.roles.firstOrNull { item -> item.id == fileShareAutoAuthorizeRoleId }?.id
            val roleOptions = buildDeviceRoleOptionsUiState(roleState.roles)
            var selectedRoleId by remember(fileShareAutoAuthorizeRoleId, roleState.roles.size) {
                mutableStateOf(initialRoleId)
            }
            DeviceRoleSelectionDialog(
                prompt = AppStrings.ui_please_select_role_use_when_automatically_authorizing_devices_connect,
                roleOptions = roleOptions,
                selectedRoleId = selectedRoleId,
                onRoleSelect = { roleId -> selectedRoleId = roleId },
                onConfirm = {
                    val roleId = selectedRoleId ?: return@DeviceRoleSelectionDialog
                    settingsState.setFileShareAutoAuthorizeRoleId(roleId)
                    showAutoAuthorizeRoleDialog = false
                },
                onCancel = { showAutoAuthorizeRoleDialog = false },
                onDismissRequest = {},
                emptyHint = AppStrings.ui_there_currently_no_available_roles_please_go_device_role
            )
        }

        if (showAccessKeyDialog) {
            FileShareAccessKeyDialog(
                initialValue = fileShareAccessKeyConfig.value,
                onDismiss = {
                    showAccessKeyDialog = false
                    enableAccessKeyAfterEdit = false
                },
                onConfirm = { value ->
                    settingsState.setFileShareAccessKeyConfig(
                        FileShareAccessKeyConfig(
                            enabled = fileShareAccessKeyConfig.enabled || enableAccessKeyAfterEdit,
                            value = value,
                        )
                    )
                    showAccessKeyDialog = false
                    enableAccessKeyAfterEdit = false
                }
            )
        }

        if (showRemoteOpenDirectorySelector) {
            PathSelectorDialog(
                deskType = Local(),
                openPath = displayDownloadDirectory,
                onDismiss = { showRemoteOpenDirectorySelector = false },
                onConfirm = { selected ->
                    showRemoteOpenDirectorySelector = false
                    if (selected != null) {
                        settingsState.setRemoteOpenDownloadDirectory(selected.path)
                    }
                }
            )
        }
    }
}

@Composable
private fun FileShareAccessKeyDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val normalizedValue = value.trim()
    val isValid = isValidFileShareAccessKeyValue(normalizedValue)
    val errorMessage = when {
        normalizedValue.isEmpty() -> AppStrings.ui_access_key_cannot_be_empty
        !isValid -> AppStrings.ui_access_key_printable_ascii_limit
        else -> ""
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_set_file_sharing_access_key) },
        text = {
            Column {
                PasswordOutlinedTextField(
                    value = value,
                    onValueChange = { newValue -> value = newValue },
                    label = { Text(AppStrings.ui_access_key_value) },
                    isError = value.isNotEmpty() && !isValid,
                    supportingText = errorMessage.takeIf { value.isNotEmpty() && it.isNotEmpty() }?.let { message ->
                        { Text(message, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        value = GENERATED_FILE_SHARE_ACCESS_KEY_LENGTH.randomString(
                            includeSpecial = false
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(AppStrings.ui_generate_random_keys)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedValue) },
                enabled = isValid,
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}
