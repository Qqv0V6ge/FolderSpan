package com.folderspan.utils

import com.folderspan.AppBuildConfig

data class SemanticAppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticAppVersion> {
    override fun compareTo(other: SemanticAppVersion): Int =
        compareValuesBy(this, other, { item -> item.major }, { item -> item.minor }, { item -> item.patch })
}

fun currentAppVersion(): String = AppBuildConfig.APP_VERSION

fun parseSemanticAppVersion(raw: String): SemanticAppVersion? {
    val match = SEMANTIC_APP_VERSION.matchEntire(raw.trim()) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    return SemanticAppVersion(major, minor, patch)
}

fun isNewerAppVersion(latest: String, current: String): Boolean {
    val latestVersion = parseSemanticAppVersion(latest) ?: return false
    val currentVersion = parseSemanticAppVersion(current) ?: return false
    return latestVersion > currentVersion
}

private val SEMANTIC_APP_VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
