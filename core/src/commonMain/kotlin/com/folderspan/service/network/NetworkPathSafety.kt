package com.folderspan.service.network

import com.folderspan.exception.AuthorityException
import strings.AppStrings

internal fun isUnsafeNetworkPathSegment(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return true
    if ('/' in trimmed || '\\' in trimmed || trimmed.any { it.code == 0 }) return true
    return false
}

internal fun containsUnsafeNetworkPathSegment(path: String): Boolean {
    val normalized = path.replace('\\', '/').trim('/')
    return normalized.isNotEmpty() && normalized.split('/').any(::isUnsafeNetworkPathSegment)
}

internal fun isUnsafeRemoteListEntry(name: String, path: String): Boolean {
    return isUnsafeNetworkPathSegment(name) || containsUnsafeNetworkPathSegment(path)
}

internal fun requireSafeNetworkWritePath(path: String) {
    require(!containsUnsafeNetworkPathSegment(path)) { AppStrings.ui_remote_path_invalid }
}

internal fun unsafeNetworkWritePathError(path: String): Throwable? {
    if (containsUnsafeNetworkPathSegment(path)) {
        return AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    return null
}

internal fun isRemotePathWithinRoot(
    destRoot: String,
    resolved: String,
    separator: String,
): Boolean {
    val sep = separator.ifBlank { "/" }
    val sepChar = sep.first()
    fun canonical(path: String): String {
        val unified = path.replace('\\', sepChar).replace('/', sepChar)
        if (unified.isEmpty() || unified == sep) return sep
        return unified.trimEnd(sepChar)
    }
    val root = canonical(destRoot)
    val path = canonical(resolved)
    if (containsUnsafeNetworkPathSegment(path) || containsUnsafeNetworkPathSegment(root)) {
        return false
    }
    if (path == root) return true
    val prefix = if (root.endsWith(sep)) root else root + sep
    return path.startsWith(prefix)
}
