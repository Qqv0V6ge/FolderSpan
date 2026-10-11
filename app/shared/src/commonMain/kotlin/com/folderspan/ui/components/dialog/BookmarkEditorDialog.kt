package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.displayName
import com.folderspan.utils.PathUtils
import kotlin.enums.enumEntries

// 书签编辑弹窗（支持选择路径与类型）
@Composable
fun BookmarkEditorDialog(
    deskType: DiskBase,
    title: String,
    confirmText: String,
    initialName: String = "",
    initialPath: String = "",
    initialType: DrawerBookmarkType = DrawerBookmarkType.Custom,
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String, type: DrawerBookmarkType) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    var selectedType by remember(initialType) { mutableStateOf(initialType) }
    var nameError by remember { mutableStateOf(false) }
    var pathError by remember { mutableStateOf(false) }
    var showFileSelector by remember { mutableStateOf(false) }

    val types = remember { enumEntries<DrawerBookmarkType>() }
    val typeLabels = remember { types.associateWith { item ->  item.displayName() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = path,
                    onValueChange = { item ->
                        path = item
                        if (pathError) pathError = false
                    },
                    label = { Text(AppStrings.ui_path) },
                    isError = pathError,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showFileSelector = true }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = AppStrings.ui_select_path)
                        }
                    }
                )
                if (pathError) {
                    Text(
                        AppStrings.ui_please_enter_valid_path,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextField(
                    value = name,
                    onValueChange = { item ->
                        name = item
                        if (nameError) nameError = false
                    },
                    label = { Text(AppStrings.ui_name) },
                    isError = nameError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text(
                        AppStrings.ui_please_enter_bookmark_name,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                EditableExposedDropdownMenu(
                    optionsUiState = StringListUiState(types.map { item ->  typeLabels[item] ?: item.name }),
                    value = typeLabels[selectedType] ?: selectedType.name,
                    onValueChange = { label ->
                        types.firstOrNull { item ->  typeLabels[item] == label }?.let { item ->  selectedType = item }
                    },
                    label = { Text(AppStrings.ui_type) },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                nameError = name.isBlank()
                pathError = path.isBlank()
                if (!nameError && !pathError) {
                    onConfirm(name.trim(), path.trim(), selectedType)
                }
            }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )

    if (showFileSelector) {
        PathSelectorDialog(
            deskType = deskType,
            openPath = path.ifBlank { PathUtils.getHomePath() },
            onDismiss = { showFileSelector = false },
            onConfirm = { file ->
                showFileSelector = false
                file?.let { item ->
                    path = item.path
                    pathError = false
                    if (name.isBlank()) {
                        name = item.name.ifBlank { item.path }
                        nameError = false
                    }
                }
            }
        )
    }
}

// 路径选择对话框：用于复用的目录选择弹窗
@Composable
fun PathSelectorDialog(
    deskType: DiskBase,
    openPath: String,
    onDismiss: () -> Unit,
    onConfirm: (FileSimpleInfo?) -> Unit
) {
    var selected by remember { mutableStateOf<FileSimpleInfo?>(null) }

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
            TextButton(onClick = { onConfirm(selected) }, enabled = selected != null) {
                Text(AppStrings.ui_ok)
            }
        },
    ) {
        FileSelectorEntryRegion(
            deskType = deskType,
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(
                selected?.let { item -> listOf(item) } ?: emptyList(),
            ),
            fileFilterTypesUiState = FileFilterTypeListUiState(listOf(FileFilterType.Folder)),
            onFilesSelected = { choices ->
                selected = choices.lastOrNull()
            },
        )
    }
}
