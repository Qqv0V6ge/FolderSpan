package com.folderspan.service.http.server.linkshare

import com.folderspan.utils.normalizePathForSecurityBoundary

private const val LINK_SHARE_STATIC_PREFIX = "/static/"
private const val LINK_SHARE_BUNDLED_RESOURCE_PREFIX = "files/share-file/"
private const val LINK_SHARE_STATIC_BOUNDARY_ROOT = "/link-share-static"
private const val LINK_SHARE_RESOURCE_SEGMENT_MAX_LENGTH = 255
private const val LINK_SHARE_RESOURCE_PATH_MAX_LENGTH = 1024

internal fun resolveLinkShareStaticRelativePath(requestPath: String): String? {
    val path = requestPath.trim()
    if (!path.startsWith(LINK_SHARE_STATIC_PREFIX)) return null
    val remainder = path.removePrefix(LINK_SHARE_STATIC_PREFIX)
    return normalizeLinkShareRelativeSegments(remainder)?.let { relative ->
        val normalized = normalizePathForSecurityBoundary(
            "$LINK_SHARE_STATIC_BOUNDARY_ROOT/$relative",
            "/",
        ) ?: return null
        val prefix = "$LINK_SHARE_STATIC_BOUNDARY_ROOT/"
        if (!normalized.startsWith(prefix)) return null
        normalized.removePrefix(prefix).takeIf { item -> item.isNotEmpty() }
    }
}

internal fun normalizeLinkShareBundledResourcePath(resourcePath: String): String? {
    val normalized = resourcePath.trim().trimStart('/')
    if (normalized.length > LINK_SHARE_RESOURCE_PATH_MAX_LENGTH) return null
    if ('\\' in normalized || '\u0000' in normalized) return null
    if (!normalized.startsWith(LINK_SHARE_BUNDLED_RESOURCE_PREFIX)) return null
    val remainder = normalized.removePrefix(LINK_SHARE_BUNDLED_RESOURCE_PREFIX)
    val relative = normalizeLinkShareRelativeSegments(remainder) ?: return null
    return LINK_SHARE_BUNDLED_RESOURCE_PREFIX + relative
}

internal fun isSafeLinkShareResourceSegment(segment: String): Boolean {
    if (segment.isEmpty() || segment == "." || segment == "..") return false
    if (segment.length > LINK_SHARE_RESOURCE_SEGMENT_MAX_LENGTH) return false
    return segment.none { character ->
        character == '\u0000' || character == '/' || character == '\\'
    }
}

private fun normalizeLinkShareRelativeSegments(relativePath: String): String? {
    if (relativePath.isEmpty() || relativePath.length > LINK_SHARE_RESOURCE_PATH_MAX_LENGTH) return null
    if ('\\' in relativePath || '\u0000' in relativePath) return null
    val segments = relativePath.split('/')
    if (segments.isEmpty() || segments.any { segment -> !isSafeLinkShareResourceSegment(segment) }) {
        return null
    }
    return segments.joinToString("/")
}
