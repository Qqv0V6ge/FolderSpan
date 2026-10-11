package com.folderspan.utils

actual object LocaleStringComparator {
    private val collator: dynamic = js("new Intl.Collator(undefined, { sensitivity: 'accent' })")

    actual fun compare(a: String, b: String): Int {
        val comparison = collator.compare(a, b) as Double
        return when {
            comparison < 0 -> -1
            comparison > 0 -> 1
            else -> 0
        }
    }
}
