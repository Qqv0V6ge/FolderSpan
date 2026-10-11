package com.folderspan.clipboard

import kotlinx.browser.window
import kotlinx.coroutines.await

actual suspend fun readClipboardContent(): ClipboardContent? {
    val clipboard = window.navigator.clipboard
    val text = runCatching { clipboard.readText().await() }.getOrNull() ?: return null
    if (text.isBlank()) return null
    return ClipboardContent(texts = listOf(text))
}
