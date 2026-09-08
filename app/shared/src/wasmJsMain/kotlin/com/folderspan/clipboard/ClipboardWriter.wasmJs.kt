@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.clipboard

import kotlinx.browser.window
import kotlinx.coroutines.await

actual suspend fun writeClipboardText(text: String): Boolean {
    val clipboard = window.navigator.clipboard
    return try {
        clipboard.writeText(text).await<Unit>()
        true
    } catch (_: Throwable) {
        false
    }
}
