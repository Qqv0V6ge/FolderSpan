package com.folderspan.ui.screen.file


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "File editor page turn indicator", widthDp = 360, heightDp = 160)
@Composable
private fun FileEditorPageTurnIndicatorPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EditorPageTurnIndicator(
                state = EditorPageTurnIndicatorState(
                    direction = EditorPageTurnDirection.Previous,
                    progress = 0.55f,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            EditorPageTurnIndicator(
                state = EditorPageTurnIndicatorState(
                    direction = EditorPageTurnDirection.Next,
                    progress = 1f,
                    phase = EditorPageTurnPhase.Ready,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
