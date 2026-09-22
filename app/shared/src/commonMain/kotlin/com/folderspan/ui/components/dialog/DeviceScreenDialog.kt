package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folderspan.db.Device as DbDevice

@Composable
fun DeviceEditDialog(
    selectedDevice: DbDevice,
    editedName: String,
    onEditedNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (DbDevice) -> Unit
) {
    var isNameError by remember { mutableStateOf(editedName.isBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_edit_device_name) },
        text = {
            TextField(
                value = editedName,
                onValueChange = { value ->
                    onEditedNameChange(value)
                    isNameError = value.isBlank()
                },
                label = { Text(AppStrings.ui_device_name) },
                singleLine = true,
                isError = isNameError,
                supportingText = {
                    if (isNameError) {
                        Text(AppStrings.ui_device_name_cannot_empty)
                    }
                },
                trailingIcon = {
                    if (editedName.isNotEmpty()) {
                        IconButton(onClick = { onEditedNameChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = AppStrings.ui_clear
                            )
                        }
                    }
                }
            )
        },
        confirmButton = {
            TextButton(
                enabled = editedName.isNotEmpty() && selectedDevice.name != editedName,
                onClick = { onConfirm(selectedDevice) }
            ) {
                Text(AppStrings.ui_ok)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
