package com.folderspan.ui.components.fileshare

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun FileShareHistoryListItemPreview() {
    FileShareHistoryListItem(
        history = sampleFileShareHistory(),
        onDelete = {}
    )
}

@Preview
@Composable
private fun FileShareHistoryDetailDialogPreview() {
    FileShareHistoryDetailDialog(
        history = sampleFileShareHistory(),
        onDismiss = {}
    )
}

@Preview
@Composable
private fun FileShareHistoryDetailSectionPreview() {
    FileShareHistoryDetailSection(
        title = AppStrings.ui_file_information,
        icon = Icons.Default.Description
    ) {
        FileShareHistoryLabeledText(AppStrings.ui_file_name, "report.pdf")
        FileShareHistoryLabeledText(AppStrings.ui_size, "2.0 MB")
    }
}

@Preview
@Composable
private fun FileShareHistoryLabeledTextPreview() {
    FileShareHistoryLabeledText(AppStrings.ui_path, "/Documents/report.pdf")
}
