package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.folderspan.data.main.DiskBase
import com.folderspan.ui.components.file.FileSelectorEntryRegion

@Composable
fun SyncPathSelectorDialog(
    title: String,
    deskType: DiskBase,
    selectedPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember(selectedPath, deskType.pathSeparator) {
        mutableStateOf(selectedPath.ifBlank { deskType.pathSeparator })
    }
    var selectedItemPath by remember(selectedPath, deskType.pathSeparator) {
        mutableStateOf<String?>(null)
    }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
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
                onClick = { onConfirm(selectedItemPath ?: currentPath) },
                enabled = selectedItemPath != null || currentPath.isNotBlank(),
            ) {
                Text(AppStrings.ui_complete)
            }
        },
    ) {
        FileSelectorEntryRegion(
            deskType = deskType,
            openPath = currentPath,
            isSingleSelection = true,
            onFilesSelected = { files ->
                selectedItemPath = files.lastOrNull()?.path
            },
            onPathChanged = { path ->
                currentPath = path
            },
        )
    }
}
