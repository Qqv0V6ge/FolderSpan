package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.state.main.*

@Composable
private fun PreviewSample() {
    val preview = SyncRunPreview("sample", 1, "Photos", SyncMode.Mirror,
        linkedMapOf("source" to "Photos", "target-0" to "Backup", "target-1" to "Archive"),
        linkedMapOf(SyncPreviewAction.Overwrite to SyncPreviewCount(1, 2_048_000), SyncPreviewAction.Recycle to SyncPreviewCount(1, 1_024_000)),
        2, 0, true, emptyList(), false, recycleEnabled = true,
        participantPaths = mapOf("source" to listOf("/Pictures"), "target-0" to listOf("/Backup/Photos"), "target-1" to listOf("/Archive/Photos")))
    MaterialTheme {
        SyncRunPreviewPage(preview, PageViewState.Content, false,
            listOf(SyncPreviewItem(SyncPreviewAction.Overwrite, "target-0", "2026/photo.jpg", "source", size = 2_048_000),
                SyncPreviewItem(SyncPreviewAction.Recycle, "target-0", "2026/photo.jpg", size = 1_024_000)),
            PageViewState.Content, PageAppendState.End, null, false, null,
            { _, _ -> }, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Compact", widthDp = 390, heightDp = 800)
@Composable
private fun CompactPreview() = PreviewSample()

@Preview(name = "Medium", widthDp = 720, heightDp = 900)
@Composable
private fun MediumPreview() = PreviewSample()

@Preview(name = "Expanded", widthDp = 1024, heightDp = 768)
@Composable
private fun ExpandedPreview() = PreviewSample()

@Preview(name = "Large", widthDp = 1366, heightDp = 900)
@Composable
private fun LargePreview() = PreviewSample()

@Preview(name = "Extra large", widthDp = 1700, heightDp = 900)
@Composable
private fun ExtraLargePreview() = PreviewSample()

@Preview(name = "Short window", widthDp = 680, heightDp = 360)
@Composable
private fun ShortPreview() = PreviewSample()
