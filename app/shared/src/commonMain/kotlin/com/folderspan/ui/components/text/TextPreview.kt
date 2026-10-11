package com.folderspan.ui.components.text

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun HighlightedTextPreview() {
    HighlightedText(
        text = AppStrings.ui_file_manager_supports_quick_search,
        keyword = AppStrings.ui_search
    )
}
