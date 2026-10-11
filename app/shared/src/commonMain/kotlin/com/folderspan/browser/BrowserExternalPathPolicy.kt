package com.folderspan.browser

internal fun normalizeBrowserExternalRelativePath(raw: String?): String? {
    val value = raw?.trim()?.replace('\\', '/') ?: return null
    if (value.isBlank() || '\u0000' in value) return null
    val segments = value.trimStart('/').split('/').filter { item -> item.isNotBlank() }
    if (segments.isEmpty() || segments.any { item -> item == "." || item == ".." }) return null
    return segments.joinToString("/")
}
