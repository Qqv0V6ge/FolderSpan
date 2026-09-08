package com.folderspan.utils

import java.text.Collator
import java.util.Locale

actual object LocaleStringComparator {
    private var cachedLocale: Locale? = null
    private var cachedCollator: Collator? = null

    actual fun compare(a: String, b: String): Int {
        return obtainCollator().compare(a, b)
    }

    private fun obtainCollator(): Collator {
        val currentLocale = Locale.getDefault()
        val current = cachedCollator
        if (current != null && currentLocale == cachedLocale) {
            return current
        }

        val collator = Collator.getInstance(currentLocale).apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        cachedLocale = currentLocale
        cachedCollator = collator
        return collator
    }
}
