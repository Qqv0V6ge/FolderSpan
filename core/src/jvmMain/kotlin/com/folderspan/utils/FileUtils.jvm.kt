package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import strings.AppStrings
import com.folderspan.extensions.toFileSimpleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.source
import java.awt.Desktop
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission.*

actual object FileUtils {
    private const val MAX_IN_MEMORY_READ_BYTES = 64L * 1024 * 1024

    actual fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) return Result.failure(Exception(AppStrings.ui_not_found_file))

        val simple = file.toFileSimpleInfo().getOrElse { item -> return Result.failure(item) }

        var permissions = 0
        var user = ""
        var userGroup = ""
        try {
            val p = Paths.get(file.absolutePath)
            val attrs = Files.readAttributes(p, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val perms = attrs.permissions()
            if (OWNER_READ in perms) permissions = 0 or (1 shl 8)
            if (OWNER_WRITE in perms) permissions = permissions or (1 shl 7)
            if (OWNER_EXECUTE in perms) permissions = permissions or (1 shl 6)
            if (GROUP_READ in perms) permissions = permissions or (1 shl 5)
            if (GROUP_WRITE in perms) permissions = permissions or (1 shl 4)
            if (GROUP_EXECUTE in perms) permissions = permissions or (1 shl 3)
            if (OTHERS_READ in perms) permissions = permissions or (1 shl 2)
            if (OTHERS_WRITE in perms) permissions = permissions or (1 shl 1)
            if (OTHERS_EXECUTE in perms) permissions = permissions or (1 shl 0)
            user = attrs.owner().name
            userGroup = attrs.group().name
        } catch (_: Throwable) {
            try {
                user = Files.getOwner(Paths.get(file.absolutePath)).name
            } catch (_: Throwable) {
            }
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
                permissions = permissions,
                user = user,
                userGroup = userGroup,
                createdDate = simple.createdDate,
                updatedDate = simple.updatedDate,
                protocol = simple.protocol,
                protocolId = simple.protocolId,
            )
        )
    }

    actual fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val file = File(path)
        if (file.exists() || file.isSymbolicLinkNoFollow()) {
            return File(path).toFileSimpleInfo()
        }
        return Result.failure(Exception(AppStrings.ui_not_found_file))
    }

    actual fun getFile(
        permission: FileAccessPermission,
        path: String,
        fileName: String,
    ): Result<FileSimpleInfo> =
        if (hasFileAccess(permission)) File(path, fileName).toFileSimpleInfo() else deniedFileAccessResult()

    actual fun openFile(permission: FileAccessPermission, file: String) {
        requireFileAccess(permission)
        val target = File(file)
        if (target.isSymbolicLinkNoFollow()) {
            throw AuthorityException(AppStrings.ui_not_allowed_to_read_write_symbolic_links_arg0.format(arg0 = (file)))
        }
        Desktop.getDesktop().open(target)
    }

    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) {
            return Result.failure(Exception(AppStrings.ui_not_found_file))
        }
        return try {
            if (file.delete()) {
                Result.success(true)
            } else {
                Result.failure(Exception(AppStrings.ui_delete_failed))
            }
        } catch (_: SecurityException) {
            Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
        } catch (e: Exception) {
            Result.failure(Exception(e.message))
        }
    }

    actual fun totalSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return File(path).totalSpace
    }

    actual fun freeSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return File(path).freeSpace
    }

    actual fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val file = File(path)
        if (file.isSymbolicLinkNoFollow()) return Result.failure(AuthorityException(AppStrings.ui_forbidden_to_write_symbolic_links))
        if (file.exists()) return Result.success(true)
        return try {
            Result.success(file.mkdir())
        } catch (_: SecurityException) {
            Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
        } catch (e: Exception) {
            Result.failure(AuthorityException(e.message))
        }
    }

    actual fun rename(
        permission: FileAccessPermission,
        path: String,
        oldName: String,
        newName: String,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val oldFile = File(path, oldName)
        val newFile = File(path, newName)

        if (!oldFile.exists() && !oldFile.isSymbolicLinkNoFollow()) {
            return Result.failure(Exception(AppStrings.ui_file_does_not_exist_cannot_rename))
        }
        if (newFile.exists() || newFile.isSymbolicLinkNoFollow()) {
            return Result.failure(Exception(AppStrings.ui_the_file_name_is_already_present_and_cannot_be_renamed))
        }

        return try {
            if (oldFile.renameTo(newFile)) {
                Result.success(true)
            } else {
                Result.failure(Exception(AppStrings.ui_rename_failed))
            }
        } catch (_: SecurityException) {
            Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
        } catch (e: Exception) {
            Result.failure(Exception(e.message))
        }
    }

    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return try {
            NoFollowFileChannels.openRead(path).use { channel ->
                val fileLength = channel.size()
                if (fileLength > MAX_IN_MEMORY_READ_BYTES) {
                    Result.failure(Exception(AppStrings.ui_file_too_large_cannot_read_all_at_once_arg0_bytes_please_use_chunked_reading.format(arg0 = (fileLength).toString())))
                } else {
                    Result.success(channel.asBufferedSource().use { source -> source.readByteArray() })
                }
            }
        } catch (_: SecurityException) {
            Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
        } catch (e: Exception) {
            Result.failure(Exception(e.message))
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
            try {
                NoFollowFileChannels.openRead(path).use { channel ->
                    val fileLength = channel.size()
                    if (start < 0 || end > fileLength || start > end) {
                        return@run Result.failure(Exception(AppStrings.ui_invalid_range))
                    }
                    Result.success(channel.readRangeBytes(start, end))
                }
            } catch (_: SecurityException) {
                Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
            } catch (e: Exception) {
                Result.failure(Exception(e.message))
            }
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
        if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_block_size)))
            return@flow
        }

        val channel = try {
            NoFollowFileChannels.openRead(path)
        } catch (_: SecurityException) {
            emit(Result.failure(AuthorityException(AppStrings.message_task_permission_denied)))
            return@flow
        } catch (e: Exception) {
            emit(Result.failure(Exception(e.message)))
            return@flow
        }

        channel.use { opened ->
            if (opened.size() == 0L) {
                emit(Result.success(0L to byteArrayOf()))
                return@flow
            }
            val bufferSize = chunkSize.toInt()
            opened.asBufferedSource().use { bufferedSource ->
                var currentIndex = 0L
                while (true) {
                    val chunk = try {
                        bufferedSource.readChunkOrNull(bufferSize)
                    } catch (_: SecurityException) {
                        emit(Result.failure(AuthorityException(AppStrings.message_task_permission_denied)))
                        return@flow
                    } catch (e: Exception) {
                        emit(Result.failure(Exception(e.message)))
                        return@flow
                    } ?: break
                    emit(Result.success(currentIndex to chunk))
                    currentIndex++
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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
        if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE || start < 0L || end < start) {
            emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
            return@flow
        }
        val channel = try {
            NoFollowFileChannels.openRead(path)
        } catch (_: SecurityException) {
            emit(Result.failure(AuthorityException(AppStrings.message_task_permission_denied)))
            return@flow
        } catch (e: Exception) {
            emit(Result.failure(Exception(e.message)))
            return@flow
        }
        channel.use { opened ->
            if (end > opened.size()) {
                emit(Result.failure(Exception(AppStrings.ui_invalid_range)))
                return@flow
            }
            if (start == end) return@flow
            var offset = start
            while (offset < end) {
                val expectedSize = minOf(chunkSize, end - offset).toInt()
                val data = ByteArray(expectedSize)
                val bytes = opened.readFully(offset, data, expectedSize)
                if (bytes.size != expectedSize) {
                    emit(Result.failure(Exception(AppStrings.ui_read_range_length_mismatch)))
                    return@flow
                }
                emit(Result.success(offset to bytes))
                offset += bytes.size
            }
        }
    }.flowOn(Dispatchers.IO)

    actual suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
        if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
            return@withContext Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
        }
        try {
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

    actual suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
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

    actual suspend fun writeByteStream(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        startOffset: Long,
        expectedBytes: Long,
        bufferSize: Int,
        readNext: suspend (buffer: ByteArray, length: Int) -> Int,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
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

    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return try {
            NoFollowFileChannels.openReadWrite(path, create = true).use { }
            restrictOwnerOnlyPath(Paths.get(path), directory = false)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(Exception(e.message))
        }
    }

    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> {
        requireFileAccess(permission)
        return NoFollowFileChannels.openRead(path).use { channel ->
            channel.asBufferedSource().use { source -> source.readAllLines() }
        }
    }

    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        NoFollowFileChannels.openAppend(path).use { channel ->
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val written = channel.write(buffer)
                if (written < 0) throw IllegalStateException(AppStrings.ui_writing_file_failed)
                if (written == 0) Thread.yield()
            }
        }
        return
    }

    private fun BufferedSource.readChunkOrNull(chunkSize: Int): ByteArray? {
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

    private fun BufferedSource.readAllLines(): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readUtf8Line() ?: break
            lines += line
        }
        return lines
    }

    private fun Throwable.messageWithType(): String {
        val rawMessage = message?.trim().orEmpty()
        val typeName = this::class.simpleName ?: "Exception"
        return when {
            rawMessage.isEmpty() -> typeName
            rawMessage.all { it == '-' || it.isDigit() } -> "$typeName: $rawMessage"
            else -> rawMessage
        }
    }

    private fun File.isSymbolicLinkNoFollow(): Boolean = Files.isSymbolicLink(toPath())
}
