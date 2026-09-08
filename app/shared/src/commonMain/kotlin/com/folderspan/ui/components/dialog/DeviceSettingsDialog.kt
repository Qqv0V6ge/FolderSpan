package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceConnectType.*
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.model.DeviceRoleOptionsUiState
import com.folderspan.ui.components.model.buildDeviceConnectOptionsUiState

private val combinedServerConnectTypeNameMap = mapOf(
    AUTO_CONNECT to AppStrings.ui_automatic_consent,
    PERMANENTLY_BANNED to AppStrings.ui_always_refuse,
    APPROVED to AppStrings.ui_waiting_authorization,
)
private val combinedServerConnectOptions = buildDeviceConnectOptionsUiState(combinedServerConnectTypeNameMap)

private val combinedClientConnectTypeNameMap = mapOf(
    AUTO_CONNECT to AppStrings.ui_automatically_connect,
    APPROVED to AppStrings.ui_connect_after_asking,
)
private val combinedClientConnectOptions = buildDeviceConnectOptionsUiState(combinedClientConnectTypeNameMap)

data class CombinedDeviceEditResult(
    val deviceName: String,
    val serverConnectionType: DeviceConnectType,
    val serverRoleId: Long?,
    val clientConnectionType: DeviceConnectType,
)

private fun resolveCombinedConnectionLabel(device: DeviceJoinDeviceRole): String {
    return when (device.category) {
        DeviceCategory.SERVER -> {
            when (device.connectionType) {
                AUTO_CONNECT -> combinedServerConnectTypeNameMap[AUTO_CONNECT]
                PERMANENTLY_BANNED -> combinedServerConnectTypeNameMap[PERMANENTLY_BANNED]
                else -> combinedServerConnectTypeNameMap[APPROVED]
            }
        }

        DeviceCategory.CLIENT -> {
            when (device.connectionType) {
                AUTO_CONNECT -> combinedClientConnectTypeNameMap[AUTO_CONNECT]
                else -> combinedClientConnectTypeNameMap[APPROVED]
            }
        }
    }.orEmpty()
}

@Composable
fun EditCombinedDeviceDialog(
    deviceName: String,
    serverDevice: DeviceJoinDeviceRole?,
    clientDevice: DeviceJoinDeviceRole?,
    roleOptions: DeviceRoleOptionsUiState,
    onDismissRequest: () -> Unit,
    onSaveChange: (CombinedDeviceEditResult) -> Unit
) {
    val roleNames = roleOptions.names
    val defaultServerConnectType = combinedServerConnectTypeNameMap[APPROVED].orEmpty()
    val defaultClientConnectType = combinedClientConnectTypeNameMap[APPROVED].orEmpty()
    val combinedDeviceKey = serverDevice?.id ?: clientDevice?.id ?: deviceName

    var editedDeviceName by rememberSaveable(combinedDeviceKey) { mutableStateOf(deviceName) }
    var serverConnectType by rememberSaveable(combinedDeviceKey) {
        mutableStateOf(
            serverDevice?.let(::resolveCombinedConnectionLabel) ?: defaultServerConnectType
        )
    }
    var clientConnectType by rememberSaveable(combinedDeviceKey) {
        mutableStateOf(
            clientDevice?.let(::resolveCombinedConnectionLabel) ?: defaultClientConnectType
        )
    }
    var deviceRole by rememberSaveable(combinedDeviceKey, roleNames) {
        mutableStateOf(
            serverDevice?.roleName?.takeIf { roleName -> roleName.isNotBlank() }
                ?: roleNames.firstOrNull().orEmpty()
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = editedDeviceName.isNotEmpty()
                    && serverConnectType.isNotEmpty()
                    && clientConnectType.isNotEmpty()
                    && (roleNames.isEmpty() || deviceRole.isNotEmpty()),
                onClick = {
                    val resolvedServerConnectionType = combinedServerConnectTypeNameMap.entries
                        .firstOrNull { entry -> entry.value == serverConnectType }
                        ?.key ?: serverDevice?.connectionType ?: APPROVED
                    val resolvedClientConnectionType = combinedClientConnectTypeNameMap.entries
                        .firstOrNull { entry -> entry.value == clientConnectType }
                        ?.key ?: clientDevice?.connectionType ?: APPROVED
                    val resolvedRoleId = roleOptions.idForName(deviceRole)
                        ?: serverDevice?.roleId?.takeIf { roleId -> roleId != -1L }

                    onSaveChange(
                        CombinedDeviceEditResult(
                            deviceName = editedDeviceName,
                            serverConnectionType = resolvedServerConnectionType,
                            serverRoleId = resolvedRoleId,
                            clientConnectionType = resolvedClientConnectionType
                        )
                    )
                    onDismissRequest()
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_cancel)
            }
        },
        title = { Text(AppStrings.ui_edit_device) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = editedDeviceName,
                    onValueChange = { value -> editedDeviceName = value },
                    label = { Text(AppStrings.ui_device_name) },
                    isError = editedDeviceName.isEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )

                DeviceAccessDirectionSection(
                    icon = Icons.AutoMirrored.Filled.CallReceived,
                    title = AppStrings.ui_visited,
                    subtitle = AppStrings.ui_when_other_devices_connect_this_device,
                    iconTint = MaterialTheme.colorScheme.primary
                ) {
                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(combinedServerConnectOptions.labels),
                        value = serverConnectType,
                        isError = serverConnectType.isEmpty(),
                        onValueChange = { value -> serverConnectType = value },
                        label = { Text(AppStrings.ui_authorization_method) },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(roleNames),
                        value = deviceRole,
                        isError = roleNames.isNotEmpty() && deviceRole.isEmpty(),
                        onValueChange = { value -> deviceRole = value },
                        label = { Text(AppStrings.ui_access_role) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                DeviceAccessDirectionSection(
                    icon = Icons.AutoMirrored.Filled.CallMade,
                    title = AppStrings.ui_access_other_devices,
                    subtitle = AppStrings.ui_when_this_device_actively_connects_other_devices,
                    iconTint = MaterialTheme.colorScheme.tertiary
                ) {
                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(combinedClientConnectOptions.labels),
                        value = clientConnectType,
                        isError = clientConnectType.isEmpty(),
                        onValueChange = { value -> clientConnectType = value },
                        label = { Text(AppStrings.ui_connection_method) },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@Composable
private fun DeviceAccessDirectionSection(
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
