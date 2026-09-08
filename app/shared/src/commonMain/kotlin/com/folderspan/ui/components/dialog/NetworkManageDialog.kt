package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun NetworkConnectDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_connect_network_devices) },
        text = {
            Text(
                AppStrings.dialog_connect_network_device.format(
                    deviceName = name,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_connect)
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
fun NetworkDeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_confirm_deletion) },
        text = {
            Text(
                AppStrings.dialog_delete_device.format(
                    deviceName = name,
                ),
            )
        },
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

@Composable
fun NetworkBatchConnectDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_batch_connection) },
        text = {
            Text(
                AppStrings.dialog_connect_selected_network_devices.format(
                    count = count.toString(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_connect)
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
fun NetworkBatchDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_batch_delete) },
        text = {
            Text(
                AppStrings.dialog_delete_selected_network_devices.format(
                    count = count.toString(),
                ),
            )
        },
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

@Composable
fun NetworkBatchPinDialog(
    count: Int,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(AppStrings.ui_batch_pinned_top) },
        text = {
            Text(
                AppStrings.dialog_pin_selected_network_devices.format(
                    count = count.toString(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_pin_top)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
