package com.folderspan.utils
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.flow.Flow

internal expect object NativeFileUtils {

    fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo>


    fun getFile(permission: FileAccessPermission, path: String, fileName: String): Result<FileSimpleInfo>


    fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo>


    fun openFile(permission: FileAccessPermission, file: String)


    fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean>


    fun totalSpace(permission: FileAccessPermission, path: String): Long


    fun freeSpace(permission: FileAccessPermission, path: String): Long


    fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean>

    fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean>


    fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray>


    fun readFileRange(permission: FileAccessPermission, path: String, start: Long, end: Long): Result<ByteArray>



    fun readFileChunks(
        permission: FileAccessPermission,
        path: String,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>>


    fun readFileRangeChunks(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>>


    suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean>


    suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean>


    suspend fun writeByteStream(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        startOffset: Long,
        expectedBytes: Long,
        bufferSize: Int,
        readNext: suspend (buffer: ByteArray, length: Int) -> Int,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean>


    fun createFile(permission: FileAccessPermission, path: String): Result<Boolean>


    fun readFileLines(permission: FileAccessPermission, path: String): List<String>


    fun appendToFile(permission: FileAccessPermission, path: String, content: String)
}
