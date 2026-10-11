package com.folderspan.clipboard

import kotlinx.browser.window
import kotlinx.coroutines.await

actual suspend fun writeClipboardText(text: String): Boolean {
    val clipboard = window.navigator.clipboard
    return runCatching {
        clipboard.writeText(text).await()
        true
    }.getOrDefault(false)
}
