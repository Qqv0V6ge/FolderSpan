package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.utils.scrollToItemAfterFrame
import com.seiko.imageloader.model.ImageRequest
import com.seiko.imageloader.rememberImagePainter

@Composable
fun FileShareSelectFilesDialog(
    openPath: String,
    initialSelectionUiState: FileSelectionUiState = FileSelectionUiState(),
    onDismiss: () -> Unit,
    onConfirm: (FileSelectionUiState) -> Unit
) {
    val selectedFiles = remember(initialSelectionUiState) {
        mutableStateListOf<FileSimpleInfo>().apply {
            addAll(initialSelectionUiState.files)
        }
    }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_choose_share_files,
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
                onClick = { onConfirm(FileSelectionUiState(selectedFiles.toList())) },
                enabled = selectedFiles.isNotEmpty()
            ) {
                Text(AppStrings.ui_add)
            }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(selectedFiles.toList()),
            onFilesSelected = { selected ->
                selectedFiles.clear()
                selectedFiles.addAll(selected)
            },
        )
    }
}

@Composable
fun FileShareMergeDialog(
    existingCount: Int,
    uniqueIncomingCount: Int,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(AppStrings.ui_update_sharing_list) },
        text = {
            Column {
                Text(AppStrings.ui_new_shared_file_detected)
                Spacer(Modifier.height(8.dp))
                Text(AppStrings.ui_current_list_arg0_items.format(arg0 = (existingCount).toString()))
                Text(AppStrings.ui_new_selection_arg0_items.format(arg0 = (uniqueIncomingCount).toString()))
                Spacer(Modifier.height(8.dp))
                Text(AppStrings.ui_please_choose_how_handle_new_data)
                Spacer(Modifier.height(4.dp))
                Text(AppStrings.ui_replace_list_clear_current_share_list_use_new_selections, style = MaterialTheme.typography.bodySmall)
                Text(AppStrings.ui_append_files_append_only_files_that_do_not_exist, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text(AppStrings.ui_replacement_list)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) {
                    Text(AppStrings.ui_cancel)
                }
                TextButton(onClick = onAppend) {
                    Text(AppStrings.ui_append_file)
                }
            }
        }
    )
}

@Composable
fun FileShareQrCodeDialog(
    data: Pair<String, ImageRequest>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.padding(16.dp),
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = rememberImagePainter(data.second),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(data.first) }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
fun FileShareDeviceLogDialog(
    device: Device,
    pathsUiState: StringListUiState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_access_log) },
        text = {
            Column {
                Text(AppStrings.ui_device_label_arg0.format(arg0 = device.name))
                Spacer(Modifier.height(8.dp))
                Text(AppStrings.ui_device_id_arg0.format(arg0 = device.id))
                Spacer(Modifier.height(8.dp))
                Text(AppStrings.ui_request_path_arg0.format(arg0 = (pathsUiState.items.size).toString()))
                Spacer(Modifier.height(4.dp))
                val listState = rememberLazyListState()
                LaunchedEffect(pathsUiState.items.size) {
                    if (pathsUiState.items.isNotEmpty()) {
                        listState.scrollToItemAfterFrame(pathsUiState.items.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    itemsIndexed(
                        items = pathsUiState.items,
                        key = { index, path -> "$index:$path" }
                    ) { index, path ->
                        Text("${index + 1} | $path", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_ok)
            }
        }
    )
}
