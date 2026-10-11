package com.folderspan.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual suspend fun writeClipboardText(text: String): Boolean {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
    return runCatching {
        clipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)
}
