package com.folderspan.utils

actual fun callerLocation(stackDepth: Int): String {
    // Kotlin/JS doesn't expose Java-like stack trace element reliably
    return "JS:-1"
}

