package com.folderspan.ui.screen.settings

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.permission.PermissionAction
import com.folderspan.permission.PermissionIds
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermission
import com.folderspan.permission.PlatformPermissionProvider
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.effects.OnAppResumeEffect
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.folderspan.ui.state.settings.SettingsState

/**
 * 权限设置页面
 */
class PermissionSettingsScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val permissions = remember { PlatformPermissionProvider.permissions() }
        val statusMap = remember { mutableStateMapOf<String, PermissionStatus>() }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val settingsState = koinInject<SettingsState>()
        val rootStartupRequestEnabled by settingsState.rootStartupRequestEnabled.collectAsState()

        suspend fun refreshStatuses() {
            permissions.forEach { permission ->
                statusMap[permission.id] = PlatformPermissionProvider.status(permission)
            }
        }

        LaunchedEffect(permissions) {
            refreshStatuses()
        }

        OnAppResumeEffect {
            scope.launch { refreshStatuses() }
        }

        AppScaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_permissions) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    }
                )
            }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                isEmpty = permissions.isEmpty(),
            ) {
                items(permissions, key = { item ->  item.id }) { permission ->
                    val status = statusMap[permission.id] ?: PermissionStatus.NotDetermined
                    PermissionListItem(
                        permission = permission,
                        status = status,
                        onRequest = {
                            PlatformPermissionProvider.request(permission) { result ->
                                statusMap[permission.id] = result
                                permissionRequestMessage(permission, result)?.let { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            }
                        },
                        onOpenSettings = { PlatformPermissionProvider.openSettings(permission) },
                        rootStartupRequestEnabled = rootStartupRequestEnabled,
                        onRootStartupRequestChange = settingsState::setRootStartupRequestEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionListItem(
    permission: PlatformPermission,
    status: PermissionStatus,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    rootStartupRequestEnabled: Boolean,
    onRootStartupRequestChange: (Boolean) -> Unit
) {
    val title = permission.title.ifBlank { permission.id }
    val description = permission.description.ifBlank { AppStrings.ui_unknown_permissions }
    val action = permission.action
    val actionConfig = permissionActionConfig(
        action = action,
        status = status,
        onRequest = onRequest,
        onOpenSettings = onOpenSettings
    )

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = AppStrings.ui_status_arg0.format(arg0 = permissionStatusLabel(status)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (permission.id == PermissionIds.Root) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppStrings.ui_request_root_startup_arg0.format(arg0 = if (rootStartupRequestEnabled) AppStrings.ui_already_turned else AppStrings.ui_status_closed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            Column {
                actionConfig?.let { config ->
                    TextButton(onClick = config.onClick, enabled = config.enabled) {
                        Text(config.label)
                    }
                }
                if (permission.id == PermissionIds.Root) {
                    Switch(
                        checked = rootStartupRequestEnabled,
                        onCheckedChange = onRootStartupRequestChange
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private data class PermissionActionConfig(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit
)

private fun permissionActionConfig(
    action: PermissionAction,
    status: PermissionStatus,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
): PermissionActionConfig? {
    if (action == PermissionAction.None) {
        return null
    }
    return when {
        status == PermissionStatus.Unsupported -> PermissionActionConfig(
            label = AppStrings.ui_not_available,
            enabled = false,
            onClick = {}
        )
        status == PermissionStatus.Granted -> PermissionActionConfig(
            label = AppStrings.ui_close,
            enabled = true,
            onClick = onOpenSettings
        )
        action == PermissionAction.OpenSettings -> PermissionActionConfig(
            label = AppStrings.ui_go_settings,
            enabled = true,
            onClick = onOpenSettings
        )
        else -> PermissionActionConfig(
            label = AppStrings.ui_request,
            enabled = true,
            onClick = onRequest
        )
    }
}

private fun permissionRequestMessage(permission: PlatformPermission, status: PermissionStatus): String? {
    val title = permission.title.ifBlank { permission.id }
    return when (status) {
        PermissionStatus.Granted -> AppStrings.ui_authorized_arg0.format(arg0 = title)
        PermissionStatus.Denied -> AppStrings.ui_arg0_authorization_denied.format(arg0 = title)
        PermissionStatus.Unsupported -> AppStrings.ui_arg0_not_available_current_platform_runtime.format(arg0 = title)
        PermissionStatus.NotDetermined -> AppStrings.ui_arg0_still_not_authorized.format(arg0 = title)
    }
}

private fun permissionStatusLabel(status: PermissionStatus): String = when (status) {
    PermissionStatus.Granted -> AppStrings.ui_authorized
    PermissionStatus.Denied -> AppStrings.ui_rejected
    PermissionStatus.NotDetermined -> AppStrings.ui_not_requested
    PermissionStatus.Unsupported -> AppStrings.ui_not_supported
}
