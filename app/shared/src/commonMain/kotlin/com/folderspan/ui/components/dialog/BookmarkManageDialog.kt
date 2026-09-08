package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun BookmarkBatchDeleteDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_delete_bookmarks_batches) },
        text = { Text(AppStrings.ui_you_sure_you_want_delete_selected_arg0_bookmarks.format(arg0 = (selectedCount).toString())) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_delete)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
