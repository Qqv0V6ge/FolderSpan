package com.folderspan.clipboard

import platform.UIKit.UIPasteboard

actual suspend fun writeClipboardText(text: String): Boolean {
    UIPasteboard.generalPasteboard.string = text
    return true
}
