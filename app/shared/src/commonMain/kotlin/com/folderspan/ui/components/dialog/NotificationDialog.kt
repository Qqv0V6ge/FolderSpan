package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.DeviceRoleOptionsUiState
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.utils.PathUtils

@Composable
fun DeviceShareSavePathDialog(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit = {},
    openPath: String = PathUtils.getHomePath(),
    title: String = AppStrings.ui_please_select_directory_save
) {
    var selectedFolder by remember { mutableStateOf<FileSimpleInfo?>(null) }
    var currentPath by remember(openPath) { mutableStateOf(openPath) }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(AppStrings.ui_cancel)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = selectedFolder?.path ?: currentPath.ifBlank { return@TextButton }
                    onConfirm(path)
                },
                enabled = selectedFolder != null || currentPath.isNotBlank()
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
    ) {
        FileSelectorEntryRegion(
            deskType = Local(),
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(
                selectedFolder?.let { folder -> listOf(folder) } ?: emptyList(),
            ),
            isSingleSelection = true,
            fileFilterTypesUiState = FileFilterTypeListUiState(listOf(FileFilterType.Folder)),
            onFilesSelected = { files ->
                selectedFolder = files.lastOrNull()
            },
            onPathChanged = { path ->
                currentPath = path
            },
        )
    }
}

@Composable
fun DeviceRoleSelectionDialog(
    title: String = AppStrings.ui_select_device_role,
    prompt: String,
    roleOptions: DeviceRoleOptionsUiState,
    selectedRoleId: Long?,
    onRoleSelect: (Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit = {},
    emptyHint: String? = null,
    confirmText: String = AppStrings.ui_confirm,
    cancelText: String = AppStrings.ui_cancel
) {
    AlertDialog(
        title = { Text(title) },
        text = {
            Column {
                Text(prompt)
                Spacer(modifier = Modifier.height(16.dp))

                roleOptions.roles.forEach { role ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRoleSelect(role.id) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRoleId == role.id,
                            onClick = { onRoleSelect(role.id) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(role.name)
                    }
                }

                if (roleOptions.roles.isEmpty() && !emptyHint.isNullOrBlank()) {
                    Text(emptyHint)
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedRoleId != null
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(cancelText)
            }
        }
    )
}
