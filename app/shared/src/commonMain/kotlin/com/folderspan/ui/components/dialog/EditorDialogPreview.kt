package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "File information - phone", widthDp = 360, heightDp = 640)
@Composable
private fun EditorDialogPreview() {
    MaterialTheme {
        EditorDialog(
            title = AppStrings.ui_file_information,
            onDismissRequest = {},
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_close,
                    onClick = {},
                )
            },
        ) {
            EditorDialogSection(title = AppStrings.ui_file) {
                EditorDialogPropertyRow(AppStrings.ui_path, "/Documents/example.txt", monospace = true)
                EditorDialogPropertyRow(AppStrings.ui_size, AppStrings.ui_24_6_kb_25_219_bytes)
            }
            EditorDialogSection(title = AppStrings.ui_current_location) {
                EditorDialogPropertyRow(AppStrings.ui_byte_offset, "4,096")
                EditorDialogPropertyRow(AppStrings.ui_line_number, "128")
            }
        }
    }
}

@Preview(name = "Danger confirmation - narrow", widthDp = 360, heightDp = 480)
@Composable
private fun EditorConfirmationDialogPreview() {
    MaterialTheme {
        EditorDialog(
            title = AppStrings.ui_clear_recent_searches,
            onDismissRequest = {},
            contentScrollable = false,
            forceFullScreen = false,
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_cancel,
                    onClick = {},
                )
                EditorDialogAction(
                    text = AppStrings.ui_clear_all,
                    onClick = {},
                )
            },
        ) {
            Text(AppStrings.ui_it_cannot_restored_after_clearing_but_it_will_not)
        }
    }
}
