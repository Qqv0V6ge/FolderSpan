package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

actual object FileUtils {
    actual suspend fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> = NativeFileUtils.getFile(permission, path)

    actual suspend fun getFile(permission: FileAccessPermission, path: String, fileName: String): Result<FileSimpleInfo> = NativeFileUtils.getFile(permission, path, fileName)

    actual suspend fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> = NativeFileUtils.getFileInfo(permission, path)

    actual suspend fun openFile(permission: FileAccessPermission, file: String): Unit = NativeFileUtils.openFile(permission, file)

    actual suspend fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> = NativeFileUtils.deleteFile(permission, path)

    actual suspend fun totalSpace(permission: FileAccessPermission, path: String): Long = NativeFileUtils.totalSpace(permission, path)

    actual suspend fun freeSpace(permission: FileAccessPermission, path: String): Long = NativeFileUtils.freeSpace(permission, path)

    actual suspend fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> = NativeFileUtils.createFolder(permission, path)

    actual suspend fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean> = NativeFileUtils.rename(permission, path, oldName, newName)

    actual suspend fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> = NativeFileUtils.readFile(permission, path)

    actual suspend fun readFileRange(permission: FileAccessPermission, path: String, start: Long, end: Long): Result<ByteArray> = NativeFileUtils.readFileRange(permission, path, start, end)

    actual fun readFileChunks(
        permission: FileAccessPermission,
        path: String,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> = NativeFileUtils.readFileChunks(permission, path, chunkSize)

    actual fun readFileRangeChunks(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> = NativeFileUtils.readFileRangeChunks(permission, path, start, end, chunkSize)

    actual suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean> = NativeFileUtils.writeBytes(permission, path, fileSize, data, offset)

    actual suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = NativeFileUtils.writeByteRanges(permission, path, fileSize, ranges, onRangeWritten)

    actual suspend fun writeByteStream(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        startOffset: Long,
        expectedBytes: Long,
        bufferSize: Int,
        readNext: suspend (buffer: ByteArray, length: Int) -> Int,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = NativeFileUtils.writeByteStream(permission, path, fileSize, startOffset, expectedBytes, bufferSize, readNext, onBytesWritten)

    actual suspend fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> = NativeFileUtils.createFile(permission, path)

    actual suspend fun readFileLines(permission: FileAccessPermission, path: String): List<String> = NativeFileUtils.readFileLines(permission, path)

    actual suspend fun appendToFile(permission: FileAccessPermission, path: String, content: String): Unit = NativeFileUtils.appendToFile(permission, path, content)
}
