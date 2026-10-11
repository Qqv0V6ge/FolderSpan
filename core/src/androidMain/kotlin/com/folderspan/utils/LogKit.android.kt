package com.folderspan.utils

actual fun callerLocation(stackDepth: Int): String {
    val ste = Throwable().stackTrace.getOrNull(stackDepth)
    val file = ste?.fileName ?: "Unknown"
    val line = ste?.lineNumber ?: -1
    return "$file:$line"
}

