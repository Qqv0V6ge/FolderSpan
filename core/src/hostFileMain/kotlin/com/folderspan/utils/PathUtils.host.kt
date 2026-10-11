package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

actual object PathUtils {
    actual suspend fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> = NativePathUtils.getFileAndFolder(permission, path)

    actual suspend fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult> = NativePathUtils.getFileAndFolderWithIssues(permission, path)

    actual suspend fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean = NativePathUtils.isSymbolicLink(permission, path)

    actual suspend fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean,
    ): Boolean = NativePathUtils.isPathWithinRoot(permission, rootPath, targetPath, allowNonExistentLeaf)

    actual suspend fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean,
    ): String? = NativePathUtils.resolveCanonicalPath(permission, path, allowNonExistentLeaf)

    actual fun getAppPath(): String = NativePathUtils.getAppPath()

    actual fun getHomePath(): String = NativePathUtils.getHomePath()

    actual fun getCachePath(): String = NativePathUtils.getCachePath()

    actual fun getPathSeparator(): String = NativePathUtils.getPathSeparator()

    actual suspend fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>> = NativePathUtils.getRootPaths(permission)

    actual fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>> = NativePathUtils.traverse(permission, path)

    actual suspend fun exists(permission: FileAccessPermission, path: String): Boolean = NativePathUtils.exists(permission, path)

    actual suspend fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String): Unit = NativePathUtils.createDirectoryIfNotExists(permission, path)

    actual suspend fun deleteDirectory(permission: FileAccessPermission, path: String): Unit = NativePathUtils.deleteDirectory(permission, path)
}
