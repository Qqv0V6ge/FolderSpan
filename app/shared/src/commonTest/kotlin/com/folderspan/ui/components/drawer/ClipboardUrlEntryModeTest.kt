package com.folderspan.ui.components.drawer

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardUrlEntryModeTest {
    @Test
    fun openFromClipboardAddsUrlToShareWithoutOpeningDownloadSettings() {
        assertEquals(
            ClipboardUrlAction.AddToShare,
            clipboardUrlAction(ClipboardTextEntryMode.Open),
        )
    }

    @Test
    fun explicitPasteOpensDownloadSettings() {
        assertEquals(
            ClipboardUrlAction.ConfigureDownload,
            clipboardUrlAction(ClipboardTextEntryMode.Paste),
        )
    }
}
