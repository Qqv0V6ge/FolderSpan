package com.folderspan.ui.components.appbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun RenderPathSwitchPreview() {
    RenderPathSwitch(
        name = "Documents",
        dropdownUiState = PathSwitchDropdownUiState(
            items = listOf(
                PathSwitchDropdownItem(
                    name = "Work",
                    path = "/Documents/Work",
                ),
                PathSwitchDropdownItem(
                    name = "Personal",
                    path = "/Documents/Personal",
                )
            )
        ),
        selected = false,
        onClick = {},
        onSelected = { _ -> },
        leadingIcon = { Icon(Icons.Default.Folder, null) }
    )
}
