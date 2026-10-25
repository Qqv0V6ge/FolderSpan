package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

internal actual object PrivateFileStorage {
    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> = NativeFileUtils.deleteFile(permission, path)

    actual fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean> = NativeFileUtils.rename(permission, path, oldName, newName)

    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> = NativeFileUtils.readFile(permission, path)

    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> = NativeFileUtils.createFile(permission, path)

    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> = NativeFileUtils.readFileLines(permission, path)

    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String): Unit = NativeFileUtils.appendToFile(permission, path, content)

    actual fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> = NativePathUtils.getFileAndFolder(permission, path)

    actual fun getAppPath(): String = NativePathUtils.getApplicationDataPath()

    actual fun getCachePath(): String = NativePathUtils.getCachePath()

    actual fun getPathSeparator(): String = NativePathUtils.getPathSeparator()

    actual fun exists(permission: FileAccessPermission, path: String): Boolean = NativePathUtils.exists(permission, path)

    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String): Unit = NativePathUtils.createDirectoryIfNotExists(permission, path)

    actual fun deleteDirectory(permission: FileAccessPermission, path: String): Unit = NativePathUtils.deleteDirectory(permission, path)
}
