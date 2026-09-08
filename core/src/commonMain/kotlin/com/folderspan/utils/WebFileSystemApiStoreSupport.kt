package com.folderspan.utils

private const val WEB_TASK_RUNTIME_DIR_NAME = "task-runtime"

internal fun shouldPersistWebFileSystemApiPath(
    path: String,
    cachePath: String,
): Boolean {
    val normalizedPath = normalizeWebFileSystemApiPath(path)
    val normalizedCachePath = normalizeWebFileSystemApiPath(cachePath)
    val runtimePrefix = if (normalizedCachePath == "/") {
        "/$WEB_TASK_RUNTIME_DIR_NAME/"
    } else {
        "$normalizedCachePath/$WEB_TASK_RUNTIME_DIR_NAME/"
    }
    return !(normalizedPath.startsWith(runtimePrefix) && normalizedPath.endsWith(".tmp"))
}

private fun normalizeWebFileSystemApiPath(path: String): String {
    val trimmed = path.trim().replace('\\', '/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    var normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    normalized = normalized.replace(Regex("/{2,}"), "/")
    if (normalized.length > 1 && normalized.endsWith("/")) {
        normalized = normalized.dropLast(1)
    }
    return normalized
}
