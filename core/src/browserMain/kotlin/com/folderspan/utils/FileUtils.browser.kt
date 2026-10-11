package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.webui.*
import com.folderspan.service.webrtc.download.WebRtcBrowserDownloadRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual object FileUtils {
    actual suspend fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.info(path)
        else WebUiPlatformClient.get("/files/info?path=${encodeWebUiQuery(path)}")
    }

    actual suspend fun getFile(permission: FileAccessPermission, path: String, fileName: String): Result<FileSimpleInfo> =
        getFile(permission, joinWebUiPath(path, fileName))

    actual suspend fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) {
            val info = PrivateFileStorage.info(path)
            FileInfo(info.name, isDirectory = info.isDirectory, isHidden = info.isHidden, path = info.path,
                mineType = info.mineType, size = info.size, permissions = 0, user = "", userGroup = "", createdDate = 0, updatedDate = 0)
        } else WebUiPlatformClient.get("/files/details?path=${encodeWebUiQuery(path)}")
    }

    actual suspend fun openFile(permission: FileAccessPermission, file: String) {
        requireFileAccess(permission)
        val info = getFile(permission, file).getOrThrow()
        val writer = checkNotNull(WebRtcBrowserDownloadRegistry.createFileWriter(info.name, info.mineType)) { "Browser download service unavailable" }
        try {
            check(writer.prepare(file, info.size).getOrThrow())
            readFileRangeChunks(permission, file, 0, info.size, WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong()).collect { item ->
                val (offset, bytes) = item.getOrThrow()
                check(writer.writeChunk(file, info.size, bytes, offset).getOrThrow())
            }
            check(writer.commit(file, info.size).getOrThrow())
        } finally { writer.cleanup(file) }
    }

    actual suspend fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.deleteFile(permission, path).getOrThrow()
        else WebUiPlatformClient.json.decodeFromString<Boolean>(WebUiPlatformClient.exchange("DELETE", "$WEB_UI_PLATFORM_PATH/files?path=${encodeWebUiQuery(path)}").body.decodeToString())
    }

    actual suspend fun totalSpace(permission: FileAccessPermission, path: String): Long = space(permission, path)[0]
    actual suspend fun freeSpace(permission: FileAccessPermission, path: String): Long = space(permission, path)[1]
    private suspend fun space(permission: FileAccessPermission, path: String): List<Long> {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) return listOf(-1L, -1L) // Browser storage has no portable filesystem capacity.
        return WebUiPlatformClient.get("/files/space?path=${encodeWebUiQuery(path)}")
    }

    actual suspend fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) { PrivateFileStorage.createDirectoryIfNotExists(permission, path); true }
        else WebUiPlatformClient.post<WebUiPathRequest, Boolean>("/files/mkdir", WebUiPathRequest(path))
    }

    actual suspend fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.rename(permission, path, oldName, newName).getOrThrow()
        else WebUiPlatformClient.post<WebUiRenameRequest, Boolean>("/files/rename", WebUiRenameRequest(path, oldName, newName))
    }

    actual suspend fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> = platformResult {
        readFileRange(permission, path, 0, getFile(permission, path).getOrThrow().size).getOrThrow()
    }

    actual suspend fun readFileRange(permission: FileAccessPermission, path: String, start: Long, end: Long): Result<ByteArray> = platformResult {
        requireFileAccess(permission)
        require(start >= 0 && end >= start && end - start <= Int.MAX_VALUE) { "invalid byte array range; use streaming for larger files" }
        val result = ByteArray((end - start).toInt())
        readFileRangeChunks(permission, path, start, end, WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong()).collect { item ->
            val (offset, bytes) = item.getOrThrow()
            bytes.copyInto(result, (offset - start).toInt())
        }
        result
    }

    actual fun readFileChunks(permission: FileAccessPermission, path: String, chunkSize: Long): Flow<Result<Pair<Long, ByteArray>>> = flow {
        requireFileAccess(permission)
        require(chunkSize in 1..Int.MAX_VALUE.toLong())
        val size = getFile(permission, path).getOrThrow().size
        var offset = 0L
        var index = 0L
        while (offset < size) {
            val end = minOf(size - offset, chunkSize) + offset
            emit(Result.success(index++ to readFileRange(permission, path, offset, end).getOrThrow()))
            offset = end
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
    }

    actual fun readFileRangeChunks(permission: FileAccessPermission, path: String, start: Long, end: Long, chunkSize: Long): Flow<Result<Pair<Long, ByteArray>>> = flow {
        requireFileAccess(permission)
        require(start >= 0 && end >= start && chunkSize > 0)
        val local = if (PrivateFileStorage.owns(path)) PrivateFileStorage.readFile(permission, path).getOrThrow() else null
        var offset = start
        while (offset < end) {
            val count = minOf(chunkSize, end - offset, WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong()).toInt()
            val bytes = if (local != null) {
                require(end <= local.size)
                local.copyOfRange(offset.toInt(), offset.toInt() + count)
            } else WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=${encodeWebUiQuery(path)}&offset=$offset&length=$count").body
            check(bytes.size == count) { "file changed or unexpected end of file" }
            emit(Result.success(offset to bytes))
            offset += bytes.size
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
    }

    actual suspend fun writeBytes(permission: FileAccessPermission, path: String, fileSize: Long, data: ByteArray, offset: Long): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        require(isWriteRangeWithinFile(fileSize, offset, data.size.toLong()))
        if (PrivateFileStorage.owns(path)) {
            require(fileSize <= Int.MAX_VALUE)
            val bytes = if (PrivateFileStorage.exists(permission, path)) PrivateFileStorage.readFile(permission, path).getOrThrow().copyOf(fileSize.toInt()) else ByteArray(fileSize.toInt())
            data.copyInto(bytes, offset.toInt())
            PrivateFileStorage.write(path, bytes)
        } else {
            var position = 0
            do {
                val count = minOf(data.size - position, WEB_UI_FILE_STREAM_CHUNK_BYTES)
                WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=${encodeWebUiQuery(path)}&size=$fileSize&offset=${offset + position}", data.copyOfRange(position, position + count), "application/octet-stream")
                position += count
            } while (position < data.size)
        }
        true
    }

    actual suspend fun writeByteRanges(permission: FileAccessPermission, path: String, fileSize: Long, ranges: Flow<Pair<Long, ByteArray>>, onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        require(fileSize >= 0)
        var wrote = false
        ranges.collect { (offset, bytes) ->
            wrote = true
            writeBytes(permission, path, fileSize, bytes, offset).getOrThrow()
            onRangeWritten(offset, bytes.size)
        }
        if (!wrote) writeBytes(permission, path, fileSize, byteArrayOf(), 0).getOrThrow()
        true
    }

    actual suspend fun writeByteStream(permission: FileAccessPermission, path: String, fileSize: Long, startOffset: Long, expectedBytes: Long, bufferSize: Int, readNext: suspend (buffer: ByteArray, length: Int) -> Int, onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        require(bufferSize > 0 && isWriteRangeWithinFile(fileSize, startOffset, expectedBytes))
        val buffer = ByteArray(minOf(bufferSize, WEB_UI_FILE_STREAM_CHUNK_BYTES))
        var done = 0L
        while (done < expectedBytes) {
            val length = minOf(buffer.size.toLong(), expectedBytes - done).toInt()
            val count = readNext(buffer, length)
            check(count in 1..length) { "unexpected end of input" }
            writeBytes(permission, path, fileSize, buffer.copyOf(count), startOffset + done).getOrThrow()
            onBytesWritten(startOffset + done, count)
            done += count
        }
        if (fileSize == 0L) writeBytes(permission, path, 0, byteArrayOf(), 0).getOrThrow()
        true
    }

    actual suspend fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> = platformResult {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) PrivateFileStorage.createFile(permission, path).getOrThrow()
        else WebUiPlatformClient.post<WebUiPathRequest, Boolean>("/files/create", WebUiPathRequest(path))
    }

    actual suspend fun readFileLines(permission: FileAccessPermission, path: String): List<String> = readFile(permission, path).getOrThrow().decodeToString().lines()
    actual suspend fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        if (PrivateFileStorage.owns(path)) { PrivateFileStorage.appendToFile(permission, path, content); return }
        val bytes = content.encodeToByteArray()
        var offset = 0
        do {
            val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
            WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/files/append?path=${encodeWebUiQuery(path)}", bytes.copyOfRange(offset, end), "application/octet-stream")
            offset = end
        } while (offset < bytes.size)
    }
}
