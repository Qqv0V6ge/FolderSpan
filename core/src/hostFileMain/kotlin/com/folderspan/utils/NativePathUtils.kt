package com.folderspan.utils
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

internal expect object NativePathUtils {

    fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>>


    fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult>


    fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean


    fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean = false,
    ): Boolean


    fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean = false,
    ): String?


    fun getAppPath(): String


    fun getApplicationDataPath(): String


    fun getHomePath(): String


    fun getCachePath(): String


    fun getPathSeparator(): String


    fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>>


    fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>>


    fun exists(permission: FileAccessPermission, path: String): Boolean


    fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String)


    fun deleteDirectory(permission: FileAccessPermission, path: String)
}
