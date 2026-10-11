package com.folderspan.privileged

import android.os.ParcelFileDescriptor
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo

interface PrivilegedFileClient {
    val backendName: String

    fun list(path: String): Result<List<FileSimpleInfo>>
    fun getFile(path: String): Result<FileSimpleInfo>
    fun getFile(directory: String, fileName: String): Result<FileSimpleInfo>
    fun getFileInfo(path: String): Result<FileInfo>
    fun delete(path: String): Result<Boolean>
    fun createDirectory(path: String): Result<Boolean>
    fun createFile(path: String): Result<Boolean>
    fun rename(path: String, oldName: String, newName: String): Result<Boolean>
    fun totalSpace(path: String): Result<Long>
    fun freeSpace(path: String): Result<Long>
    fun exists(path: String): Result<Boolean>
    fun deleteDirectory(path: String): Result<Boolean>
    fun openFile(path: String, mode: Int): Result<ParcelFileDescriptor>
}

interface PrivilegedFileBackend {
    fun isAuthorized(): Boolean = true

    fun usesBeforeLocalIo(): Boolean = false

    suspend fun awaitReady(): Boolean = isAuthorized()

    fun <T> withClient(operation: (PrivilegedFileClient) -> Result<T>): Result<T>
}
