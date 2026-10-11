package com.folderspan.utils

actual object LocaleStringComparator {
    actual fun compare(a: String, b: String): Int {
        return a.lowercase().compareTo(b.lowercase())
    }
}
