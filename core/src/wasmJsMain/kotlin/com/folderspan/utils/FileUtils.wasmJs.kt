package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import strings.AppStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual object FileUtils {
    actual fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> =
        if (hasFileAccess(permission)) WebInMemoryFileStore.get(path) else deniedFileAccessResult()

    actual fun getFile(
        permission: FileAccessPermission,
        path: String,
        fileName: String,
    ): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val fullPath = if (path.endsWith("/")) "$path$fileName" else "$path/$fileName"
        return WebInMemoryFileStore.get(fullPath)
    }

    actual fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> =
        if (hasFileAccess(permission)) WebInMemoryFileStore.getInfo(path) else deniedFileAccessResult()

    actual fun openFile(permission: FileAccessPermission, file: String) {
        requireFileAccess(permission)
    }

    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val result = WebInMemoryFileStore.delete(path)
        if (result.getOrDefault(false)) {
            WebFileSystemApiStore.delete(path)
        }
        return result
    }

    actual fun totalSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return 0L
    }

    actual fun freeSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return 0L
    }

    actual fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val result = WebInMemoryFileStore.createDirectory(path)
        if (result.getOrDefault(false)) {
            WebFileSystemApiStore.ensureDirectory(path)
        }
        return result
    }

    actual fun rename(
        permission: FileAccessPermission,
        path: String,
        oldName: String,
        newName: String,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val result = WebInMemoryFileStore.rename(path, oldName, newName)
        if (result.getOrDefault(false)) {
            WebFileSystemApiStore.rename(path, oldName, newName)
        }
        return result
    }

    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        WebFileSystemApiStore.requestRead(path)
        WebFileSystemApiStore.readCached(path)?.let { item ->
            return Result.success(item)
        }

        val memoryResult = WebInMemoryFileStore.readFile(path)
        if (memoryResult.isSuccess) {
            val bytes = memoryResult.getOrNull() ?: byteArrayOf()
            WebFileSystemApiStore.cacheAndPersist(path, bytes)
            return Result.success(bytes)
        }

        val message = memoryResult.exceptionOrNull()?.message.orEmpty()
        return if (
            message.contains(AppStrings.ui_only_metadata) ||
            message.contains(AppStrings.ui_only_metadata)
        ) {
            Result.failure(Exception(AppStrings.ui_the_file_is_saved_locally_not_in_memory))
        } else {
            memoryResult
        }
    }

    actual fun readFileRange(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
    ): Result<ByteArray> =
        run {
            if (!hasFileAccess(permission)) return@run deniedFileAccessResult()
            if (start !in 0..end) return@run Result.failure(Exception(AppStrings.ui_invalid_range))
            val bytes = readFile(permission, path).getOrElse { item -> return@run Result.failure(item) }
            if (end > bytes.size.toLong()) return@run Result.failure(Exception(AppStrings.ui_invalid_range))
            Result.success(bytes.copyOfRange(start.toInt(), end.toInt()))
        }

    actual fun readFileChunks(
        permission: FileAccessPermission,
        path: String,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        if (chunkSize <= 0L || chunkSize > Int.MAX_VALUE.toLong()) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_block_size)))
            return@flow
        }
        val info = WebInMemoryFileStore.get(path).getOrNull()
        if (info != null && !info.isDirectory && WebInMemoryFileStore.hasExternalFileSource(path)) {
            if (info.size == 0L) {
                emit(Result.success(0L to byteArrayOf()))
                return@flow
            }
            var index = 0L
            var offset = 0L
            while (offset < info.size) {
                val nextOffset = minOf(offset + chunkSize, info.size)
                val data = WebInMemoryFileStore.readExternalFileRange(path, offset, nextOffset).getOrElse { error ->
                    if (index == 0L) {
                        emit(Result.failure(error))
                        return@flow
                    }
                    throw error
                }
                emit(Result.success(index to data))
                offset = nextOffset
                index++
            }
            return@flow
        }
        val allBytes = readFile(permission, path).getOrElse { item ->
            emit(Result.failure(item))
            return@flow
        }
        if (allBytes.isEmpty()) {
            emit(Result.success(0L to byteArrayOf()))
            return@flow
        }
        val bufferSize = chunkSize.toInt()
        var index = 0L
        var offset = 0
        while (offset < allBytes.size) {
            val end = minOf(offset + bufferSize, allBytes.size)
            emit(Result.success(index to allBytes.copyOfRange(offset, end)))
            offset = end
            index++
        }
    }

    actual fun readFileRangeChunks(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> = flow {
        if (!hasFileAccess(permission)) {
            emit(deniedFileAccessResult())
            return@flow
        }
        if (chunkSize <= 0L || chunkSize > Int.MAX_VALUE.toLong() || start < 0L || end < start) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
            return@flow
        }
        val info = WebInMemoryFileStore.get(path).getOrNull()
        if (info != null && !info.isDirectory && WebInMemoryFileStore.hasExternalFileSource(path)) {
            if (end > info.size) {
                emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
                return@flow
            }
            var offset = start
            while (offset < end) {
                val nextOffset = minOf(offset + chunkSize, end)
                val data = WebInMemoryFileStore.readExternalFileRange(path, offset, nextOffset).getOrElse { error ->
                    if (offset == start) {
                        emit(Result.failure(error))
                        return@flow
                    }
                    throw error
                }
                emit(Result.success(offset to data))
                offset = nextOffset
            }
            return@flow
        }
        val allBytes = readFile(permission, path).getOrElse { item ->
            emit(Result.failure(item))
            return@flow
        }
        if (end > allBytes.size.toLong()) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
            return@flow
        }
        var offset = start
        while (offset < end) {
            val nextOffset = minOf(offset + chunkSize, end)
            emit(Result.success(offset to allBytes.copyOfRange(offset.toInt(), nextOffset.toInt())))
            offset = nextOffset
        }
    }

    actual suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean> =
        run {
            if (!hasFileAccess(permission)) return@run deniedFileAccessResult()
            val result = WebInMemoryFileStore.writeBytesMetadataOnly(path, fileSize, data.size, offset)
            if (result.getOrDefault(false)) {
                WebFileSystemApiStore.persistLocalFile(
                    path = path,
                    fileSize = fileSize,
                    data = data,
                    offset = offset
                )
            }
            result
        }

    actual suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return runCatching {
            ranges.collect { (offset, data) ->
                val result = writeBytes(permission, path, fileSize, data, offset)
                if (result.isFailure || !result.getOrDefault(false)) {
                    throw (result.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
                }
                onRangeWritten(offset, data.size)
            }
            true
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    actual suspend fun writeByteStream(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        startOffset: Long,
        expectedBytes: Long,
        bufferSize: Int,
        readNext: suspend (buffer: ByteArray, length: Int) -> Int,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (!isWriteRangeWithinFile(fileSize, startOffset, expectedBytes)) {
            return Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
        }
        return writeBufferedByteStream(
            startOffset = startOffset,
            expectedBytes = expectedBytes,
            bufferSize = bufferSize,
            readNext = readNext,
            writeChunk = { offset, data ->
                val result = WebInMemoryFileStore.writeBytesMetadataOnly(path, fileSize, data.size, offset)
                if (result.isFailure || !result.getOrDefault(false)) {
                    throw (result.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
                }
                WebFileSystemApiStore.persistLocalFile(
                    path = path,
                    fileSize = fileSize,
                    data = data,
                    offset = offset,
                )
            },
            onBytesWritten = onBytesWritten,
        )
    }

    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val result = WebInMemoryFileStore.createFile(path)
        if (result.getOrDefault(false)) {
            WebFileSystemApiStore.cacheAndPersist(path, byteArrayOf())
        }
        return result
    }

    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> {
        requireFileAccess(permission)
        val bytes = readFile(permission, path).getOrElse { item -> throw item }
        val content = runCatching { bytes.decodeToString() }.getOrElse { item ->
            throw Exception(item.message ?: AppStrings.file_read_failed, item)
        }
        return content.split("\n")
    }

    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        WebInMemoryFileStore.appendToFile(path, content).getOrElse { item -> throw item }
        val bytes = WebInMemoryFileStore.readFile(path).getOrElse { item -> throw item }
        WebFileSystemApiStore.cacheAndPersist(path, bytes)
    }
}
