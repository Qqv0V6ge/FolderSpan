package com.folderspan.utils

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

actual object PathUtils {
    actual fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> =
        if (hasFileAccess(permission)) WebInMemoryFileStore.list(path) else deniedFileAccessResult()

    actual fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult> =
        if (hasFileAccess(permission)) {
            WebInMemoryFileStore.list(path).map { entries -> FileListingResult(entries = entries) }
        } else {
            deniedFileAccessResult()
        }

    actual fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean = false

    actual fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean,
    ): Boolean {
        if (!hasFileAccess(permission)) return false
        val root = normalizePathForSecurityBoundary(rootPath, "/") ?: return false
        val target = normalizePathForSecurityBoundary(targetPath, "/") ?: return false
        if (!isPathInsideRootLexically(root, target, "/") || !WebInMemoryFileStore.exists(root)) return false
        if (WebInMemoryFileStore.exists(target)) return true
        if (!allowNonExistentLeaf || target == root) return false
        val parent = target.substringBeforeLast('/', missingDelimiterValue = "/").ifEmpty { "/" }
        return WebInMemoryFileStore.exists(parent)
    }

    actual fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean,
    ): String? {
        if (!hasFileAccess(permission)) return null
        val normalized = normalizePathForSecurityBoundary(path, "/") ?: return null
        if (WebInMemoryFileStore.exists(normalized)) return normalized
        if (!allowNonExistentLeaf || normalized == "/") return null
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "/").ifEmpty { "/" }
        return normalized.takeIf { WebInMemoryFileStore.exists(parent) }
    }

    actual fun getAppPath(): String = "/"

    actual fun getHomePath(): String = "/"

    actual fun getCachePath(): String = "/tmp"

    actual fun getPathSeparator(): String = "/"

    actual fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> =
        if (hasFileAccess(permission)) Result.success(listOf(PathInfo("/", 0, 0))) else deniedFileAccessResult()

    actual fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>> =
        if (hasFileAccess(permission)) WebInMemoryFileStore.traverse(path) else kotlinx.coroutines.flow.flowOf(deniedFileAccessResult())

    actual fun exists(permission: FileAccessPermission, path: String): Boolean =
        hasFileAccess(permission) && WebInMemoryFileStore.exists(path)

    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        WebInMemoryFileStore.ensureDirectory(path).getOrElse { item ->  throw item }
    }

    actual fun deleteDirectory(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        WebInMemoryFileStore.deleteDirectory(path).getOrElse { item ->  throw item }
    }
}
