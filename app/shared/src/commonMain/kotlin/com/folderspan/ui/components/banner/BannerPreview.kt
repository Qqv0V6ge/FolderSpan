package com.folderspan.ui.components.banner

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun MaterialBannerPreview() {
    MaterialBanner(
        title = AppStrings.ui_synchronization_completed,
        message = AppStrings.ui_12_files_successfully_synced,
        onActionClick = {},
        onDismiss = {}
    )
}
