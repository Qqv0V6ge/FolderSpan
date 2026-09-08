package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun AppDrawerHeaderPreview() {
    AppDrawerHeader(title = AppStrings.ui_equipment) {
        TextButton(onClick = {}) { Text(AppStrings.ui_edit) }
    }
}

@Preview
@Composable
private fun AppDrawerItemPreview() {
    AppDrawerItem(
        title = AppStrings.ui_bookmark_label,
        actions = { TextButton(onClick = {}) { Text(AppStrings.ui_add) } },
        content = { Text(AppStrings.ui_sample_content) }
    )
}
