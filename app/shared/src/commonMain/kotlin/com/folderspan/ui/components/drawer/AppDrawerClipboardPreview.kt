package com.folderspan.ui.components.drawer

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.ui.components.model.ClipboardPathCandidateUiState
import com.folderspan.ui.components.model.ClipboardPathDialogUiState

@Preview
@Composable
private fun ClipboardPathDialogPreview() {
    ClipboardPathDialog(
        uiState = ClipboardPathDialogUiState(
            candidates = listOf(
                ClipboardPathCandidateUiState(
                    index = 0,
                    displayPath = "/Documents/Work"
                ),
                ClipboardPathCandidateUiState(
                    index = 1,
                    displayPath = "/Downloads"
                )
            )
        ),
        onSelect = { _ -> },
        onDismiss = {}
    )
}
