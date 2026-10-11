package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.folderspan.db.Device as DatabaseDevice

@Composable
fun DisconnectDeviceDialog(
    selectedDevice: DatabaseDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_confirm_disconnection) },
        text = {
            Text(
                AppStrings.dialog_disconnect_device.format(
                    deviceName = selectedDevice.name,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_disconnect)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
fun DisconnectAllDevicesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_confirm_disconnect_all_connections) },
        text = { Text(AppStrings.ui_you_sure_you_want_disconnect_all_devices) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_disconnect_all_connections)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
