package com.folderspan.utils

actual object LocaleStringComparator {
    actual fun compare(a: String, b: String): Int = a.compareTo(b, ignoreCase = true)
}
