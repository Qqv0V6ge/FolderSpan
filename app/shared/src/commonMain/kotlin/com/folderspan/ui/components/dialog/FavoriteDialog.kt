package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.FileSelectionUiState

@Composable
fun FavoritePickerDialog(
    deskType: DiskBase,
    openPath: String,
    onDismiss: () -> Unit,
    onConfirm: (List<FileSimpleInfo>) -> Unit
) {
    val selectedFiles = remember { mutableStateListOf<FileSimpleInfo>() }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_select_favorites,
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
                onClick = { onConfirm(selectedFiles.toList()) },
                enabled = selectedFiles.isNotEmpty()
            ) {
                Text(AppStrings.ui_add)
            }
        },
    ) {
        FileSelectorEntryRegion(
            deskType = deskType,
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(selectedFiles.toList()),
            onFilesSelected = { files ->
                selectedFiles.clear()
                selectedFiles.addAll(files)
            },
        )
    }
}
