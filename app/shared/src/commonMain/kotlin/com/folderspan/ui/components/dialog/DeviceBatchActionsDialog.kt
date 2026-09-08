package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.DeviceConnectOptionsUiState
import com.folderspan.ui.components.model.DeviceRoleOptionsUiState
import com.folderspan.ui.components.model.StringListUiState

data class DeviceBatchServerAccessResult(
    val connectionType: DeviceConnectType,
    val roleId: Long?,
)

@Composable
fun DeviceBatchServerAccessDialog(
    count: Int,
    eligibleCount: Int,
    connectionOptions: DeviceConnectOptionsUiState,
    roleOptions: DeviceRoleOptionsUiState,
    onDismissRequest: () -> Unit,
    onConfirm: (DeviceBatchServerAccessResult) -> Unit
) {
    val roleNames = roleOptions.names
    var selectedConnection by remember(connectionOptions) {
        mutableStateOf(connectionOptions.labels.firstOrNull().orEmpty())
    }
    var selectedRoleName by remember(roleNames) {
        mutableStateOf(roleNames.firstOrNull().orEmpty())
    }
    val selectedConnectionType = connectionOptions.typeFor(selectedConnection)
    val selectedRoleId = roleOptions.idForName(selectedRoleName)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_batch_modification_accessed) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (eligibleCount == count) {
                        AppStrings.ui_accessed_settings_selected_arg0_devices_will_modified.format(arg0 = (count).toString())
                    } else {
                        AppStrings.ui_accessed_settings_arg0_arg1_selected_devices_will_modified.format(arg0 = (eligibleCount).toString(), arg1 = (count).toString())
                    }
                )

                DeviceBatchConnectionSection(
                    icon = Icons.AutoMirrored.Filled.CallReceived,
                    title = AppStrings.ui_visited,
                    subtitle = AppStrings.ui_when_other_devices_connect_these_devices,
                    iconTint = MaterialTheme.colorScheme.primary
                ) {
                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(connectionOptions.labels),
                        value = selectedConnection,
                        onValueChange = { value -> selectedConnection = value },
                        label = { Text(AppStrings.ui_authorization_method) },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        isError = selectedConnection.isEmpty()
                    )

                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(roleNames),
                        value = selectedRoleName,
                        onValueChange = { value -> selectedRoleName = value },
                        label = { Text(AppStrings.ui_access_role) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = roleNames.isNotEmpty() && selectedRoleName.isEmpty()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = eligibleCount > 0
                    && selectedConnectionType != null
                    && (roleNames.isEmpty() || selectedRoleId != null),
                onClick = {
                    val connectionType = selectedConnectionType ?: return@TextButton
                    onConfirm(
                        DeviceBatchServerAccessResult(
                            connectionType = connectionType,
                            roleId = selectedRoleId
                        )
                    )
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
fun DeviceBatchClientAccessDialog(
    count: Int,
    eligibleCount: Int,
    connectionOptions: DeviceConnectOptionsUiState,
    onDismissRequest: () -> Unit,
    onConfirm: (DeviceConnectType) -> Unit
) {
    var selectedConnection by remember(connectionOptions) {
        mutableStateOf(connectionOptions.labels.firstOrNull().orEmpty())
    }
    val selectedConnectionType = connectionOptions.typeFor(selectedConnection)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_modify_access_other_devices_batches) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (eligibleCount == count) {
                        AppStrings.ui_connection_method_selected_arg0_devices_when_accessing_other_devices.format(arg0 = (count).toString())
                    } else {
                        AppStrings.ui_connection_method_arg0_arg1_selected_devices_when_accessing_other.format(arg0 = (eligibleCount).toString(), arg1 = (count).toString())
                    }
                )

                DeviceBatchConnectionSection(
                    icon = Icons.AutoMirrored.Filled.CallMade,
                    title = AppStrings.ui_access_other_devices,
                    subtitle = AppStrings.ui_when_these_devices_actively_connect_other_devices,
                    iconTint = MaterialTheme.colorScheme.tertiary
                ) {
                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(connectionOptions.labels),
                        value = selectedConnection,
                        onValueChange = { value -> selectedConnection = value },
                        label = { Text(AppStrings.ui_connection_method) },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        isError = selectedConnection.isEmpty()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = eligibleCount > 0 && selectedConnectionType != null,
                onClick = {
                    val connectionType = selectedConnectionType ?: return@TextButton
                    onConfirm(connectionType)
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
private fun DeviceBatchConnectionSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun DeviceBatchDeleteDialog(
    count: Int,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_delete_devices_batches) },
        text = { Text(AppStrings.ui_you_sure_you_want_delete_selected_arg0_devices.format(arg0 = (count).toString())) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_delete)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
