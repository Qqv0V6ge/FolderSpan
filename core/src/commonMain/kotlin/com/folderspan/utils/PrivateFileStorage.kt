package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

/** Application-owned records. Never accesses the HTTP host from a browser. */
internal expect object PrivateFileStorage {
    fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean>

    fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean>

    fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray>

    fun createFile(permission: FileAccessPermission, path: String): Result<Boolean>

    fun readFileLines(permission: FileAccessPermission, path: String): List<String>

    fun appendToFile(permission: FileAccessPermission, path: String, content: String): Unit

    fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>>

    fun getAppPath(): String

    fun getCachePath(): String

    fun getPathSeparator(): String

    fun exists(permission: FileAccessPermission, path: String): Boolean

    fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String): Unit

    fun deleteDirectory(permission: FileAccessPermission, path: String): Unit
}
