package com.folderspan.utils

import android.os.ParcelFileDescriptor
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import strings.AppStrings
import com.folderspan.extensions.toFileSimpleInfo
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.privileged.PrivilegedFileAccess
import com.folderspan.privileged.PrivilegedFileBackends
import com.folderspan.privileged.PrivilegedFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

internal const val MAX_IN_MEMORY_READ_BYTES = 4L * 1024 * 1024

private fun ensureInMemoryReadAllowed(size: Long): Result<Unit> {
    if (size <= MAX_IN_MEMORY_READ_BYTES) return Result.success(Unit)
    return Result.failure(Exception(AppStrings.ui_file_too_large_cannot_read_all_at_once_arg0_bytes_please_use_chunked_reading.format(arg0 = (size).toString())))
}

fun localGetFileInfo(path: String): Result<FileInfo> {
    val file = File(path)
    if (!file.exists()) return Result.failure(Exception(AppStrings.ui_not_found_file))
    val simple = file.toFileSimpleInfo().getOrElse { item -> return Result.failure(item) }
    var mode = 0
    var uid = ""
    var gid = ""
    try {
        val stat = android.system.Os.lstat(path)
        mode = stat.st_mode and 0x1FF
        uid = stat.st_uid.toString()
        gid = stat.st_gid.toString()
    } catch (_: Throwable) {
    }
    return Result.success(
        FileInfo(
            name = simple.name,
            description = simple.description,
            isDirectory = simple.isDirectory,
            isHidden = simple.isHidden,
            path = simple.path,
            mineType = simple.mineType,
            size = simple.size,
            permissions = mode,
            user = uid,
            userGroup = gid,
            createdDate = simple.createdDate,
            updatedDate = simple.updatedDate,
            protocol = simple.protocol,
            protocolId = simple.protocolId,
        )
    )
}

fun localGetFile(path: String): Result<FileSimpleInfo> {
    val file = File(path)
    return if (file.exists()) file.toFileSimpleInfo() else Result.failure(Exception(AppStrings.ui_not_found_file))
}

fun localGetFile(path: String, fileName: String): Result<FileSimpleInfo> =
    File(path, fileName).toFileSimpleInfo()

fun localDeleteFile(path: String): Result<Boolean> {
    val file = File(path)
    if (!file.exists()) return Result.failure(Exception(AppStrings.ui_not_found_file))
    return try {
        if (file.delete()) Result.success(true) else Result.failure(Exception(AppStrings.ui_delete_failed))
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.message))
    }
}

fun localTotalSpace(path: String): Long? = try {
    File(path).totalSpace
} catch (t: Throwable) {
    if (t.isPermissionError()) null else throw t
}

fun localFreeSpace(path: String): Long? = try {
    File(path).freeSpace
} catch (t: Throwable) {
    if (t.isPermissionError()) null else throw t
}

fun localCreateFolder(path: String): Result<Boolean> {
    val file = File(path)
    if (file.exists()) return Result.success(true)
    return try {
        Result.success(file.mkdir())
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
    } catch (e: Exception) {
        Result.failure(AuthorityException(e.message))
    }
}

fun localRename(path: String, oldName: String, newName: String): Result<Boolean> {
    val oldFile = File(path, oldName)
    val newFile = File(path, newName)
    if (!oldFile.exists()) return Result.failure(Exception(AppStrings.ui_file_does_not_exist_cannot_rename))
    if (newFile.exists()) return Result.failure(Exception(AppStrings.ui_the_file_name_is_already_present_and_cannot_be_renamed))
    return try {
        if (oldFile.renameTo(newFile)) Result.success(true) else Result.failure(Exception(AppStrings.ui_rename_failed))
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.message))
    }
}

fun localReadFile(path: String): Result<ByteArray> = try {
    NoFollowFileChannels.openRead(path).use { channel ->
        ensureInMemoryReadAllowed(channel.size()).getOrElse { error -> return Result.failure(error) }
        Result.success(channel.asBufferedSource().use { source -> source.readByteArray() })
    }
} catch (_: SecurityException) {
    Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
} catch (e: Exception) {
    Result.failure(Exception(e.message))
}

private fun BufferedSource.skipUpTo(byteCount: Long): Long {
    var remaining = byteCount.coerceAtLeast(0L)
    if (remaining == 0L) return 0L
    val scratch = ByteArray(minOf(remaining, 8_192L).toInt())
    var skipped = 0L
    while (remaining > 0) {
        val bytesRead = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (bytesRead <= 0) break
        skipped += bytesRead
        remaining -= bytesRead
    }
    return skipped
}

fun BufferedSource.readRangeBytes(start: Long, end: Long): ByteArray {
    val safeStart = start.coerceAtLeast(0L)
    val rangeSize = (end - safeStart).coerceAtLeast(0L)
    if (rangeSize == 0L) return byteArrayOf()
    if (rangeSize > DEVICE_DIRECT_MAX_LENGTH.toLong()) {
        throw IllegalArgumentException(AppStrings.ui_read_range_exceeds_single_block_limit_arg0.format(arg0 = (DEVICE_DIRECT_MAX_LENGTH).toString()))
    }

    val skipped = skipUpTo(safeStart)
    if (skipped < safeStart) {
        return byteArrayOf()
    }

    val expectedSize = rangeSize.toInt()
    val buffer = ByteArray(expectedSize)
    var totalRead = 0
    while (totalRead < expectedSize) {
        val bytesRead = read(buffer, totalRead, expectedSize - totalRead)
        if (bytesRead <= 0) break
        totalRead += bytesRead
    }
    return if (totalRead == expectedSize) buffer else buffer.copyOf(totalRead)
}

fun BufferedSource.readAllLines(): List<String> {
    val lines = mutableListOf<String>()
    while (true) {
        val line = readUtf8Line() ?: break
        lines += line
    }
    return lines
}

fun BufferedSource.readChunkOrNull(chunkSize: Int): ByteArray? {
    if (chunkSize <= 0) return null
    val buffer = ByteArray(chunkSize)
    var totalRead = 0
    while (totalRead < chunkSize) {
        val bytesRead = read(buffer, totalRead, chunkSize - totalRead)
        if (bytesRead <= 0) break
        totalRead += bytesRead
    }
    return when {
        totalRead <= 0 -> null
        totalRead == chunkSize -> buffer
        else -> buffer.copyOf(totalRead)
    }
}

fun BufferedSource.readChunksOrEmpty(chunkSize: Int): Sequence<Pair<Long, ByteArray>> = sequence {
    if (chunkSize <= 0) return@sequence
    var currentIndex = 0L
    while (true) {
        val chunk = readChunkOrNull(chunkSize) ?: break
        yield(currentIndex to chunk)
        currentIndex++
    }
    if (currentIndex == 0L) {
        yield(0L to byteArrayOf())
    }
}

fun localReadFileRange(path: String, start: Long, end: Long): Result<ByteArray> {
    return try {
        NoFollowFileChannels.openRead(path).use { channel ->
            val fileLength = channel.size()
            if (start < 0 || end > fileLength || start > end) {
                Result.failure(Exception(AppStrings.ui_invalid_range))
            } else {
                Result.success(channel.readRangeBytes(start, end))
            }
        }
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.message))
    }
}

fun readFileChunksWithFallback(
    path: String,
    chunkSize: Long
): Flow<Result<Pair<Long, ByteArray>>> = flow {
    if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE) {
        emit(Result.failure(Exception(AppStrings.ui_invalid_block_size)))
        return@flow
    }

    PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
        val openResult = backend.withClient { client ->
            client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (openResult.isFailure) {
            emit(Result.failure(openResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_root_service_is_not_ready)))
            return@flow
        }
        openResult.getOrNull()!!.use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).source().buffer().use { source ->
                source.readChunksOrEmpty(chunkSize.toInt()).forEach { chunk ->
                    emit(Result.success(chunk))
                }
            }
        }
        return@flow
    }

    val opened = try {
        NoFollowFileChannels.openRead(path)
    } catch (error: Exception) {
        if (isSymlinkOpenDenial(error)) {
            emit(Result.failure(error))
            return@flow
        }
        if (error is SecurityException || error is AuthorityException) {
            null
        } else {
            emit(Result.failure(Exception(error.message)))
            return@flow
        }
    }
    if (opened != null) {
        opened.use { channel ->
            if (channel.size() == 0L) {
                emit(Result.success(0L to byteArrayOf()))
                return@flow
            }
            channel.asBufferedSource().use { source ->
                source.readChunksOrEmpty(chunkSize.toInt()).forEach { chunk ->
                    emit(Result.success(chunk))
                }
            }
        }
        return@flow
    }

    val openResult = PrivilegedFileAccess.withFallback(
        primary = { Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_file)) },
        operation = { client -> client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY) }
    )
    if (openResult.isFailure) {
        emit(
            Result.failure(
                openResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_no_permission_to_read_the_file)
            )
        )
        return@flow
    }
    openResult.getOrNull()!!.use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).source().buffer().use { source ->
            source.readChunksOrEmpty(chunkSize.toInt()).forEach { chunk ->
                emit(Result.success(chunk))
            }
        }
    }
}.flowOn(Dispatchers.IO)

fun readFileRangeChunksWithFallback(
    path: String,
    start: Long,
    end: Long,
    chunkSize: Long
): Flow<Result<Pair<Long, ByteArray>>> = flow {
    if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE || start < 0L || end < start) {
        emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
        return@flow
    }
    if (start == end) return@flow

    suspend fun emitChunks(channel: FileChannel, fileLength: Long) {
        if (end > fileLength) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
            return
        }
        var offset = start
        while (offset < end) {
            val expectedSize = minOf(chunkSize, end - offset).toInt()
            val data = ByteArray(expectedSize)
            val bytes = channel.readFully(offset, data, expectedSize)
            if (bytes.size != expectedSize) {
                emit(Result.failure(Exception(AppStrings.ui_read_range_length_mismatch)))
                return
            }
            emit(Result.success(offset to bytes))
            offset += bytes.size
        }
    }

    PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
        val openResult = backend.withClient { client ->
            client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (openResult.isFailure) {
            emit(Result.failure(openResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_root_service_is_not_ready)))
            return@flow
        }
        openResult.getOrNull()!!.use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                emitChunks(input.channel, input.channel.size())
            }
        }
        return@flow
    }

    val opened = try {
        NoFollowFileChannels.openRead(path)
    } catch (error: Exception) {
        if (isSymlinkOpenDenial(error)) {
            emit(Result.failure(error))
            return@flow
        }
        if (error is SecurityException || error is AuthorityException) {
            null
        } else {
            emit(Result.failure(Exception(error.message)))
            return@flow
        }
    }
    if (opened != null) {
        opened.use { channel ->
            emitChunks(channel, channel.size())
        }
        return@flow
    }

    val openResult = PrivilegedFileAccess.withFallback(
        primary = { Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_file)) },
        operation = { client -> client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY) }
    )
    if (openResult.isFailure) {
        emit(
            Result.failure(
                openResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_no_permission_to_read_the_file)
            )
        )
        return@flow
    }
    openResult.getOrNull()!!.use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            emitChunks(input.channel, input.channel.size())
        }
    }
}.flowOn(Dispatchers.IO)

fun localWriteBytes(
    path: String,
    fileSize: Long,
    data: ByteArray,
    offset: Long
): Result<Boolean> {
    if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
        return Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
    }
    return try {
        File(path).parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return Result.failure(Exception(AppStrings.ui_unable_to_create_a_parent_directory))
            }
        }
        NoFollowFileChannels.openReadWrite(path, create = true).use { channel ->
            if (fileSize == 0L) {
                channel.truncate(0L)
                return@use Result.success(true)
            }
            if (channel.size() > fileSize) {
                channel.truncate(fileSize)
            }
            channel.writeFully(data, offset)
            Result.success(true)
        }
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.error_local_file_write_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.messageWithType(), e))
    }
}

suspend fun localWriteByteRanges(
    path: String,
    fileSize: Long,
    ranges: Flow<Pair<Long, ByteArray>>,
    onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit
): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        File(path).parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(Exception(AppStrings.ui_unable_to_create_a_parent_directory))
            }
        }
        NoFollowFileChannels.openReadWrite(path, create = true).use { channel ->
            if (fileSize == 0L) {
                channel.truncate(0L)
                return@use Result.success(true)
            }
            channel.ensureSize(fileSize)
            ranges.collect { (offset, data) ->
                if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
                    throw IllegalArgumentException(AppStrings.error_write_range_invalid)
                }
                channel.writeFully(data, offset)
                onRangeWritten(offset, data.size)
            }
            Result.success(true)
        }
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.error_local_file_write_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.messageWithType(), e))
    }
}

fun remoteWriteBytes(
    client: PrivilegedFileClient,
    path: String,
    fileSize: Long,
    data: ByteArray,
    offset: Long
): Result<Boolean> {
    if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
        return Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
    }
    val mode = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
    return client.openFile(path, mode).mapCatching { descriptor ->
        descriptor.use { item ->
            ParcelFileDescriptor.AutoCloseOutputStream(item).use { stream ->
                val channel = stream.channel
                if (fileSize > 0L && channel.size() > fileSize) {
                    channel.truncate(fileSize)
                }
                channel.writeFully(data, offset)
            }
        }
        true
    }
}

suspend fun remoteWriteByteRanges(
    client: PrivilegedFileClient,
    path: String,
    fileSize: Long,
    ranges: Flow<Pair<Long, ByteArray>>,
    onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit
): Result<Boolean> = withContext(Dispatchers.IO) {
    val mode = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
    val openResult = client.openFile(path, mode)
    if (openResult.isFailure) {
        return@withContext Result.failure(
            openResult.exceptionOrNull() ?: AuthorityException(AppStrings.error_local_file_write_permission_denied)
        )
    }
    try {
        val descriptor = openResult.getOrThrow()
        descriptor.use { item ->
            ParcelFileDescriptor.AutoCloseOutputStream(item).use { stream ->
                val channel = stream.channel
                if (fileSize > 0L) {
                    channel.ensureSize(fileSize)
                }
                ranges.collect { (offset, data) ->
                    if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
                        throw IllegalArgumentException(AppStrings.error_write_range_invalid)
                    }
                    channel.writeFully(data, offset)
                    onRangeWritten(offset, data.size)
                }
            }
        }
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(Exception(e.messageWithType(), e))
    }
}

suspend fun localWriteByteStream(
    path: String,
    fileSize: Long,
    startOffset: Long,
    expectedBytes: Long,
    bufferSize: Int,
    readNext: suspend (buffer: ByteArray, length: Int) -> Int,
    onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit
): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
        if (!isWriteRangeWithinFile(fileSize, startOffset, expectedBytes)) {
            return@withContext Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
        }
        File(path).parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(Exception(AppStrings.ui_unable_to_create_a_parent_directory))
            }
        }
        NoFollowFileChannels.openReadWrite(path, create = true).use { channel ->
            if (fileSize == 0L) {
                channel.truncate(0L)
                return@use Result.success(true)
            }
            channel.ensureSize(fileSize)
            val buffer = ByteArray(bufferSize.coerceAtLeast(1))
            var remaining = expectedBytes
            var offset = startOffset
            while (remaining > 0L) {
                val read = readNext(buffer, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    throw IllegalStateException(AppStrings.ui_file_stream_ends_before_the_file_is_read)
                }
                if (read == 0) continue
                channel.writeFully(buffer, 0, read, offset)
                onBytesWritten(offset, read)
                offset += read
                remaining -= read
            }
            Result.success(true)
        }
    } catch (_: SecurityException) {
        Result.failure(AuthorityException(AppStrings.error_local_file_write_permission_denied))
    } catch (e: Exception) {
        Result.failure(Exception(e.messageWithType(), e))
    }
}

suspend fun remoteWriteByteStream(
    client: PrivilegedFileClient,
    path: String,
    fileSize: Long,
    startOffset: Long,
    expectedBytes: Long,
    bufferSize: Int,
    readNext: suspend (buffer: ByteArray, length: Int) -> Int,
    onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit
): Result<Boolean> = withContext(Dispatchers.IO) {
    val mode = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
    val openResult = client.openFile(path, mode)
    if (openResult.isFailure) {
        return@withContext Result.failure(
            openResult.exceptionOrNull() ?: AuthorityException(AppStrings.error_local_file_write_permission_denied)
        )
    }
    try {
        if (!isWriteRangeWithinFile(fileSize, startOffset, expectedBytes)) {
            return@withContext Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
        }
        val descriptor = openResult.getOrThrow()
        descriptor.use { item ->
            ParcelFileDescriptor.AutoCloseOutputStream(item).use { stream ->
                val channel = stream.channel
                if (fileSize > 0L) {
                    channel.ensureSize(fileSize)
                }
                val buffer = ByteArray(bufferSize.coerceAtLeast(1))
                var remaining = expectedBytes
                var offset = startOffset
                while (remaining > 0L) {
                    val read = readNext(buffer, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) {
                        throw IllegalStateException(AppStrings.ui_file_stream_ends_before_the_file_is_read)
                    }
                    if (read == 0) continue
                    channel.writeFully(buffer, 0, read, offset)
                    onBytesWritten(offset, read)
                    offset += read
                    remaining -= read
                }
            }
        }
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(Exception(e.messageWithType(), e))
    }
}

fun localCreateFile(path: String): Result<Boolean> = try {
    File(path).parentFile?.let { parent ->
        if (!parent.exists()) {
            parent.mkdirs()
        }
    }
    NoFollowFileChannels.openReadWrite(path, create = true).use { }
    Result.success(true)
} catch (e: Exception) {
    Result.failure(Exception(e.message))
}

fun localReadFileLines(path: String): List<String> {
    return NoFollowFileChannels.openRead(path).use { channel ->
        channel.asBufferedSource().use { source -> source.readAllLines() }
    }
}

fun localAppendToFile(path: String, content: String) {
    NoFollowFileChannels.openAppend(path).use { channel ->
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written < 0) throw IllegalStateException(AppStrings.ui_writing_file_failed)
            if (written == 0) Thread.yield()
        }
    }
}

fun remoteReadFile(
    client: PrivilegedFileClient,
    path: String
): Result<ByteArray> = client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY).mapCatching { descriptor ->
    descriptor.use { item ->
        ParcelFileDescriptor.AutoCloseInputStream(item).use { input ->
            ensureInMemoryReadAllowed(input.channel.size()).getOrElse { error -> throw error }
            input.source().buffer().use { source ->
                source.readByteArray()
            }
        }
    }
}

fun remoteReadFileRange(
    client: PrivilegedFileClient,
    path: String,
    start: Long,
    end: Long
): Result<ByteArray> = client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY).mapCatching { descriptor ->
    descriptor.use { item ->
        if (end < start) throw IllegalArgumentException(AppStrings.ui_invalid_range)
        ParcelFileDescriptor.AutoCloseInputStream(item).use { input ->
            input.channel.readRangeBytes(start, end)
        }
    }
}

private fun FileChannel.asBufferedSource(): BufferedSource =
    Channels.newInputStream(this).source().buffer()

private fun FileChannel.readRangeBytes(start: Long, end: Long): ByteArray {
    val rangeSize = (end - start).coerceAtLeast(0L)
    if (rangeSize == 0L) return byteArrayOf()
    if (rangeSize > Int.MAX_VALUE) {
        throw IllegalArgumentException(AppStrings.ui_read_the_range_too_large)
    }

    val expectedSize = rangeSize.toInt()
    val data = ByteArray(expectedSize)
    return readFully(start, data, expectedSize)
}

private fun FileChannel.readFully(start: Long, data: ByteArray, expectedSize: Int): ByteArray {
    val buffer = ByteBuffer.wrap(data)
    var position = start
    while (buffer.hasRemaining()) {
        val read = read(buffer, position)
        if (read < 0) break
        if (read == 0) {
            Thread.yield()
            continue
        }
        position += read
    }
    val totalRead = buffer.position()
    return if (totalRead == expectedSize) data else data.copyOf(totalRead)
}

private fun FileChannel.ensureSize(fileSize: Long) {
    val current = size()
    when {
        current > fileSize -> truncate(fileSize)
        current < fileSize -> {
            position(fileSize - 1L)
            write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }
}

private fun FileChannel.writeFully(data: ByteArray, offset: Long) {
    writeFully(data, 0, data.size, offset)
}

private fun FileChannel.writeFully(data: ByteArray, dataOffset: Int, length: Int, offset: Long) {
    val buffer = ByteBuffer.wrap(data, dataOffset, length)
    var position = offset
    while (buffer.hasRemaining()) {
        val written = write(buffer, position)
        if (written < 0) {
            throw IllegalStateException(AppStrings.ui_writing_file_failed)
        }
        if (written == 0) {
            Thread.yield()
            continue
        }
        position += written
    }
}


fun Throwable.messageWithType(): String {
    val rawMessage = message?.trim().orEmpty()
    val typeName = this::class.simpleName ?: "Exception"
    return when {
        rawMessage.isEmpty() -> typeName
        rawMessage.all { it == '-' || it.isDigit() } -> "$typeName: $rawMessage"
        else -> rawMessage
    }
}

fun <T> withPrivilegedFallback(
    primary: () -> Result<T>,
    fallback: (PrivilegedFileClient) -> Result<T>
): Result<T> {
    return PrivilegedFileAccess.withFallback(primary, fallback)
}

fun Throwable?.isPermissionError(): Boolean {
    return when (this) {
        is AuthorityException -> !isSymlinkOpenDenial(this)
        is SecurityException -> true
        else -> {
            val message = this?.message ?: return false
            if (isSymlinkOpenDenial(this)) return false
            message.contains(AppStrings.message_task_permission_denied) ||
                    message.contains("permission", ignoreCase = true) ||
                    message.contains("Permission", ignoreCase = true)
        }
    }
}

internal fun isSymlinkOpenDenial(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    val lower = message.lowercase()
    return AppStrings.legacy_symbolic_link_marker in message ||
        AppStrings.legacy_symbolic_link_marker in message ||
        "symlink" in lower ||
        "symbolic link" in lower
}
