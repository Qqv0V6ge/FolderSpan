package com.folderspan.ui.components.dialog

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterType
import com.folderspan.db.DevicePermission
import com.folderspan.localization.localizedComment
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils

@Composable
fun DevicePermissionEditDialog(
    initialPermission: DevicePermission?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    val permissionKey = initialPermission?.id ?: -1L
    var path by rememberSaveable(permissionKey) {
        mutableStateOf(initialPermission?.path ?: PathUtils.getHomePath())
    }
    var comment by rememberSaveable(permissionKey) {
        mutableStateOf(initialPermission?.localizedComment.orEmpty())
    }
    var showFileSelector by rememberSaveable(permissionKey) { mutableStateOf(false) }
    var selectedPath by rememberSaveable(permissionKey) {
        mutableStateOf(initialPermission?.path ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialPermission == null) {
                    AppStrings.ui_add_new_permissions
                } else {
                    AppStrings.ui_edit_permission
                },
            )
        },
        text = {
            Column {
                TextField(
                    value = path,
                    onValueChange = { value -> path = value },
                    singleLine = true,
                    label = { Text(AppStrings.ui_path) },
                    isError = path.isEmpty(),
                    leadingIcon = {
                        IconButton({ showFileSelector = true }) {
                            Icon(Icons.Default.FolderOpen, null)
                        }
                    },
                    trailingIcon = {
                        if (path.isNotEmpty()) {
                            IconButton({ path = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                TextField(
                    value = comment,
                    onValueChange = { value -> comment = value },
                    label = { Text(AppStrings.ui_remarks) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = false,
                    trailingIcon = {
                        if (comment.isNotEmpty()) {
                            IconButton({ comment = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val savedComment = if (
                        initialPermission != null && comment == initialPermission.localizedComment.orEmpty()
                    ) {
                        initialPermission.comment
                    } else {
                        comment.ifEmpty { null }
                    }
                    onSave(path, savedComment)
                    onDismiss()
                },
                enabled = path.isNotEmpty(),
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )

    if (showFileSelector) {
        DevicePermissionPathSelectorDialog(
            selectedPath = selectedPath,
            onConfirm = { selected ->
                path = selected
                showFileSelector = false
            },
            onDismiss = { showFileSelector = false },
            onPathChanged = { value -> selectedPath = value }
        )
    }
}

@Composable
private fun DevicePermissionPathSelectorDialog(
    selectedPath: String,
    onPathChanged: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember(selectedPath) {
        mutableStateOf(selectedPath.ifEmpty { PathUtils.getHomePath() })
    }
    var selectedFolderPath by remember(selectedPath) {
        mutableStateOf(selectedPath.takeIf { it.isNotEmpty() })
    }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_select_path,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = selectedFolderPath ?: currentPath
                    onPathChanged(path)
                    onConfirm(path)
                },
                enabled = selectedFolderPath != null || currentPath.isNotBlank()
            ) {
                Text(AppStrings.ui_complete)
            }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = currentPath,
            initialSelectionUiState = FileSelectionUiState(
                selectedFolderPath
                    ?.takeIf { path -> path.isNotEmpty() }
                    ?.let { path -> FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull()?.let { file -> listOf(file) } }
                    ?: emptyList(),
            ),
            fileFilterTypesUiState = FileFilterTypeListUiState(listOf(FileFilterType.Folder)),
            selectionFilterTypesUiState = FileFilterTypeListUiState(listOf(FileFilterType.Folder)),
            isSingleSelection = true,
            onFilesSelected = { files ->
                selectedFolderPath = files.lastOrNull()?.path
            },
            onPathChanged = { path ->
                currentPath = path
            },
        )
    }
}
