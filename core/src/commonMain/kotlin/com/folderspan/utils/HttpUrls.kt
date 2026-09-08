package com.folderspan.utils

fun httpOrHttpsUrlOrNull(value: String): String? {
    val normalized = value.trim()
    if (
        normalized.startsWith("https://", ignoreCase = true) ||
        normalized.startsWith("http://", ignoreCase = true)
    ) {
        return normalized
    }
    return null
}
