package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun DeskNetworkMenuButtonPreview() {
    DeskNetworkMenuButton(
        expanded = true,
        uiState = DiskSwitchMenuUiState(
            title = AppStrings.ui_network,
            items = listOf(
                DiskSwitchMenuItemUiState(
                    id = "network:smb://192.168.0.10",
                    title = "NAS"
                )
            )
        ),
        onSelect = { _ -> },
        onDismissRequest = { _ -> }
    )
}
