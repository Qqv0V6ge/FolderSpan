package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.file.FilePropertyScanIssue

@Preview
@Composable
private fun FileInfoDialogPreview() {
    FileInfoDialog(
        uiState = FileInfoDialogUiState(
            fileInfo = FileSimpleInfo.nullFileSimpleInfo(),
            totalSize = 0,
            freeSpace = 0,
            totalSpace = 0,
            fileCount = 0,
            folderCount = 0,
            isTraversing = false,
            errorText = "",
            fullInfo = null,
            fullInfoError = "",
            fileTypeText = AppStrings.ui_file,
            skippedItemCount = 62,
            scanIssues = listOf(
                FilePropertyScanIssue("/home/webb/private", AppStrings.message_task_permission_denied),
            ),
        ),
        onCancel = {}
    )
}

@Preview
@Composable
private fun PropertyScanIssuesDialogPreview() {
    PropertyScanIssuesDialog(
        failureCount = 62,
        skippedItemCount = 62,
        errorText = "",
        scanIssues = listOf(
            FilePropertyScanIssue("/home/webb/private", AppStrings.message_task_permission_denied),
            FilePropertyScanIssue("/home/webb/broken-link", AppStrings.ui_unable_to_read_the_directory),
        ),
        onDismissRequest = {},
    )
}

@Preview
@Composable
private fun RemoteOpenConfirmDialogPreview() {
    RemoteOpenConfirmDialog(
        onConfirm = { _ -> },
        onCancel = { _ -> }
    )
}

@Preview
@Composable
private fun TextFieldDialogPreview() {
    TextFieldDialog(
        title = AppStrings.ui_enter_name,
        label = AppStrings.ui_name,
        initText = AppStrings.ui_create_new_folder,
        onCancel = { _ -> }
    )
}

@Preview
@Composable
private fun TextFieldWithTypeDialogPreview() {
    TextFieldWithTypeDialog(
        title = AppStrings.ui_dialog_preview_new,
        label = AppStrings.ui_name,
        initText = AppStrings.ui_example,
        onConfirm = { _, _ -> },
        onCancel = {}
    )
}

@Preview
@Composable
private fun FileWarningDialogItemPreview() {
    FileWarningDialogItem(
        FileSimpleInfo(
            name = "photo.png",
            isDirectory = false,
            isHidden = false,
            path = "/Pictures/photo.png",
            mineType = ".png",
            size = 2048,
            createdDate = 0,
            updatedDate = 0
        )
    )
}
