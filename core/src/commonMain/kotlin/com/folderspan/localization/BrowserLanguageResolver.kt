package com.folderspan.localization

fun parseAcceptLanguageHeader(header: String?): List<String> {
    return header
        .orEmpty()
        .split(',')
        .mapIndexedNotNull { index, entry ->
            val parts = entry.trim().split(';')
            val tag = parts.firstOrNull()?.trim().orEmpty()
            if (tag.isBlank() || tag == "*") return@mapIndexedNotNull null
            val quality = parts
                .drop(1)
                .firstOrNull { item -> item.trim().startsWith("q=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.toDoubleOrNull()
                ?: 1.0
            if (quality <= 0.0) null else Triple(tag, quality, index)
        }
        .sortedWith(
            compareByDescending<Triple<String, Double, Int>> { item -> item.second }
                .thenBy { item -> item.third },
        )
        .map { item -> item.first }
}

fun resolveBrowserLanguage(
    acceptLanguageHeader: String?,
): ResolvedAppLanguage =
    resolveSystemLanguage(parseAcceptLanguageHeader(acceptLanguageHeader).firstOrNull())

fun resolveBrowserLanguage(
    preferredLanguageTags: List<String>,
): ResolvedAppLanguage =
    resolveSystemLanguage(preferredLanguageTags.firstOrNull())
