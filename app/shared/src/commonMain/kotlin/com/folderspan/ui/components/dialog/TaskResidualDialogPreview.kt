package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.ui.state.main.TaskResidualFile
import com.folderspan.ui.state.main.TaskResidualSummary

@Preview(name = "Compact", widthDp = 360, heightDp = 800)
@Preview(name = "Short", widthDp = 640, heightDp = 360)
@Preview(name = "Medium", widthDp = 720, heightDp = 900)
@Preview(name = "Expanded", widthDp = 1024, heightDp = 768)
@Preview(name = "Large", widthDp = 1366, heightDp = 900)
@Preview(name = "Extra large", widthDp = 1600, heightDp = 900)
@Composable
private fun TaskResidualNoticeDialogPreview() {
    MaterialTheme {
        TaskResidualNoticeDialog(
            summary = TaskResidualSummary(
                taskKey = 17,
                files = listOf(TaskResidualFile("/Downloads/.folderspan-18ac4532.part", 2_097_152)),
                recovered = true,
            ),
            onKeep = {},
            onCleanup = {},
            canContinueTask = true,
        )
    }
}
