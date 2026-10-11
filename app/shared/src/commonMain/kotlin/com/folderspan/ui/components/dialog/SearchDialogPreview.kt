package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun SearchDialogPreview() {
    SearchDialog(
        title = AppStrings.ui_search,
        query = "report",
        onQueryChange = { _ -> },
        onConfirm = {},
        onDismissRequest = {},
        label = AppStrings.ui_keywords,
        placeholder = AppStrings.ui_enter_file_name
    )
}
