package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.FileSelectionUiState

@Composable
fun EasyFileSharePathSelectorDialog(
    openPath: String,
    initialSelectionUiState: FileSelectionUiState,
    onConfirm: (FileSelectionUiState) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFiles by remember(initialSelectionUiState) {
        mutableStateOf(initialSelectionUiState.files)
    }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_select_default_sharing_path,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text(AppStrings.ui_clear)
            }
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(FileSelectionUiState(selectedFiles)) }) {
                Text(AppStrings.ui_complete)
            }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(selectedFiles),
            onFilesSelected = { files ->
                selectedFiles = files
            },
        )
    }
}
