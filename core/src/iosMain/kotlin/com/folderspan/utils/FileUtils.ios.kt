@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import strings.AppStrings
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.use
import platform.Foundation.*
import platform.posix.ELOOP
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.fstat
import platform.posix.ftruncate
import platform.posix.lstat
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.strerror
import platform.posix.stat
import platform.posix.timespec
import platform.posix.write

actual object FileUtils {
    private val fileManager = NSFileManager.defaultManager
    private const val MAX_IN_MEMORY_READ_BYTES = 64L * 1024 * 1024

    actual fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return try {
            IosSecurityScopeStore.withCoordinatedReadIfNeeded(normalizedPath) { coordinatedPath ->
                buildFileSimpleInfo(coordinatedPath).map { file ->
                    file.copy(path = normalizedPath)
                }
            }
        } catch (error: Throwable) {
            Result.failure(mapFileError(error))
        }
    }

    actual fun getFile(
        permission: FileAccessPermission,
        path: String,
        fileName: String,
    ): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val fullPath = if (path.endsWith("/")) "$path$fileName" else "$path/$fileName"
        val normalizedPath = fullPath.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            buildFileSimpleInfo(normalizedPath)
        }
    }

    actual fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            val simple = buildFileSimpleInfo(normalizedPath).getOrElse { item ->  return@withSecurityScopeIfNeeded Result.failure(item) }
            Result.success(
                FileInfo(
                    name = simple.name,
                    description = simple.description,
                    isDirectory = simple.isDirectory,
                    isHidden = simple.isHidden,
                    path = simple.path,
                    mineType = simple.mineType,
                    size = simple.size,
                    permissions = 0,
                    user = "",
                    userGroup = "",
                    createdDate = simple.createdDate,
                    updatedDate = simple.updatedDate,
                    protocol = simple.protocol,
                    protocolId = simple.protocolId
                )
            )
        }
    }

    actual fun openFile(permission: FileAccessPermission, file: String) {
        requireFileAccess(permission)
    }

    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (!pathExistsNoFollow(permission, normalizedPath)) {
                return@withSecurityScopeIfNeeded Result.failure(Exception("File not found"))
            }
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val success = fileManager.removeItemAtPath(normalizedPath, error.ptr)
                if (success) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(error.value?.localizedDescription ?: "Delete failed"))
                }
            }
        }
    }

    actual fun totalSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return getFileSystemAttribute(path, NSFileSystemSize)
    }

    actual fun freeSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return getFileSystemAttribute(path, NSFileSystemFreeSize)
    }

    actual fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) {
                return@withSecurityScopeIfNeeded symbolicLinkAccessFailure()
            }
            if (fileManager.fileExistsAtPath(normalizedPath)) return@withSecurityScopeIfNeeded Result.success(true)
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val success = fileManager.createDirectoryAtPath(
                    normalizedPath,
                    true,
                    attributes = null,
                    error = error.ptr
                )
                if (success) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(error.value?.localizedDescription ?: "Create failed"))
                }
            }
        }
    }

    actual fun rename(
        permission: FileAccessPermission,
        path: String,
        oldName: String,
        newName: String,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val oldPath = (if (path.endsWith("/")) "$path$oldName" else "$path/$oldName").trim()
        val newPath = (if (path.endsWith("/")) "$path$newName" else "$path/$newName").trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(oldPath) {
            if (!pathExistsNoFollow(permission, oldPath)) {
                return@withSecurityScopeIfNeeded Result.failure(Exception("Source file not found"))
            }
            if (pathExistsNoFollow(permission, newPath)) {
                return@withSecurityScopeIfNeeded Result.failure(Exception("Target already exists"))
            }
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val success = fileManager.moveItemAtPath(oldPath, newPath, error.ptr)
                if (success) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(error.value?.localizedDescription ?: "Rename failed"))
                }
            }
        }
    }

    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return try {
            IosSecurityScopeStore.withCoordinatedReadIfNeeded(normalizedPath) { coordinatedPath ->
                if (PathUtils.isSymbolicLink(permission, coordinatedPath)) {
                    return@withCoordinatedReadIfNeeded symbolicLinkAccessFailure()
                }
                if (!fileManager.fileExistsAtPath(coordinatedPath)) {
                    return@withCoordinatedReadIfNeeded Result.failure(Exception("File not found"))
                }
                val size = existingFileSize(coordinatedPath)
                    ?: return@withCoordinatedReadIfNeeded Result.failure(Exception("File not found"))
                if (size <= 0L) return@withCoordinatedReadIfNeeded Result.success(ByteArray(0))
                if (size > MAX_IN_MEMORY_READ_BYTES) {
                    return@withCoordinatedReadIfNeeded Result.failure(
                        Exception(AppStrings.ui_file_too_large_cannot_read_all_at_once_arg0_bytes_please_use_chunked_reading.format(arg0 = (size).toString()))
                    )
                }
                runCatching {
                    val fd = openFileDescriptor(coordinatedPath, O_RDONLY)
                    try {
                        val openedSize = existingFileSize(fd)
                        if (openedSize <= 0L) ByteArray(0) else readBytesAt(fd, 0L, openedSize.toInt())
                    } finally {
                        close(fd)
                    }
                }.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(mapFileError(it)) }
                )
            }
        } catch (error: Throwable) {
            Result.failure(mapFileError(error))
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
            val normalizedPath = path.trim()
            try {
                IosSecurityScopeStore.withCoordinatedReadIfNeeded(normalizedPath) { coordinatedPath ->
                    if (PathUtils.isSymbolicLink(permission, coordinatedPath)) {
                        return@withCoordinatedReadIfNeeded symbolicLinkAccessFailure()
                    }
                    if (!fileManager.fileExistsAtPath(coordinatedPath)) return@withCoordinatedReadIfNeeded Result.failure(Exception("File not found"))
                    if (start !in 0..end) return@withCoordinatedReadIfNeeded Result.failure(Exception("Invalid range"))
                    val fileSize = existingFileSize(coordinatedPath)
                        ?: return@withCoordinatedReadIfNeeded Result.failure(Exception("File not found"))
                    if (end > fileSize) return@withCoordinatedReadIfNeeded Result.failure(Exception("Invalid range"))
                    val length = (end - start).coerceAtLeast(0)
                    if (length == 0L) return@withCoordinatedReadIfNeeded Result.success(ByteArray(0))
                    if (length > Int.MAX_VALUE) return@withCoordinatedReadIfNeeded Result.failure(Exception("Range too large"))

                    runCatching {
                        readBytesAt(coordinatedPath, start, length.toInt())
                    }.fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { Result.failure(mapFileError(it)) }
                    )
                }
            } catch (error: Throwable) {
                Result.failure(mapFileError(error))
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
        if (chunkSize <= 0L) {
            emit(Result.failure(Exception("Invalid chunk size")))
            return@flow
        }
        if (chunkSize > Int.MAX_VALUE) {
            emit(Result.failure(Exception("Chunk size too large")))
            return@flow
        }

        val file = getFile(permission, path).getOrElse { error ->
            emit(Result.failure(error))
            return@flow
        }
        var index = 0L
        readFileRangeChunks(
            permission = permission,
            path = file.path,
            start = 0L,
            end = file.size,
            chunkSize = chunkSize,
        ).collect { result ->
            emit(result.map { (_, bytes) -> index++ to bytes })
        }
    }.flowOn(Dispatchers.Default)

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
        val normalizedPath = path.trim()
        if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE || start < 0L || end < start) {
            emit(Result.failure(Exception("Invalid range")))
            return@flow
        }

        val fileSize = try {
            IosSecurityScopeStore.withCoordinatedReadIfNeeded(normalizedPath) { coordinatedPath ->
                if (PathUtils.isSymbolicLink(permission, coordinatedPath)) throw symbolicLinkAccessException()
                if (!fileManager.fileExistsAtPath(coordinatedPath)) {
                    throw Exception("File not found")
                }
                existingFileSize(coordinatedPath) ?: throw Exception("File not found")
            }
        } catch (error: Throwable) {
            emit(Result.failure(mapFileError(error)))
            return@flow
        }
        if (end > fileSize) {
            emit(Result.failure(Exception("Invalid range")))
            return@flow
        }
        if (start == end) return@flow

        var offset = start
        while (offset < end) {
            val expectedSize = minOf(chunkSize, end - offset).toInt()
            val chunk = try {
                IosSecurityScopeStore.withCoordinatedReadIfNeeded(normalizedPath) { coordinatedPath ->
                    if (PathUtils.isSymbolicLink(permission, coordinatedPath)) throw symbolicLinkAccessException()
                    val fd = openFileDescriptor(coordinatedPath, O_RDONLY)
                    try {
                        readBytesAt(fd, offset, expectedSize)
                    } finally {
                        close(fd)
                    }
                }
            } catch (error: Throwable) {
                emit(Result.failure(mapFileError(error)))
                return@flow
            }
            if (chunk.size != expectedSize) {
                emit(Result.failure(Exception("Range read length mismatch")))
                return@flow
            }
            emit(Result.success(offset to chunk))
            offset += chunk.size
        }
    }.flowOn(Dispatchers.Default)

    actual suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean> =
        withContext(Dispatchers.Default) {
            if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
            val normalizedPath = path.trim()
            IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
                if (PathUtils.isSymbolicLink(permission, normalizedPath)) {
                    return@withSecurityScopeIfNeeded symbolicLinkAccessFailure()
                }
                if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
                    return@withSecurityScopeIfNeeded Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
                }
                runCatching {
                    val fd = openFileDescriptor(normalizedPath, O_RDWR or O_CREAT)
                    try {
                        if (fileSize == 0L) {
                            truncateFile(fd, 0L)
                            return@runCatching Result.success(true)
                        }
                        if (existingFileSize(fd) > fileSize) {
                            truncateFile(fd, fileSize)
                        }
                        writeBytesAt(fd, data, offset)
                    } finally {
                        close(fd)
                    }
                    Result.success(true)
                }.getOrElse { error ->
                    Result.failure(mapFileError(error))
                }
            }
        }

    actual suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = withContext(Dispatchers.Default) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
        val normalizedPath = path.trim()
        IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) {
                return@withSecurityScopeIfNeeded symbolicLinkAccessFailure()
            }
            runCatching {
                val fd = openFileDescriptor(normalizedPath, O_RDWR or O_CREAT)
                try {
                    if (fileSize == 0L) {
                        truncateFile(fd, 0L)
                        return@runCatching Result.success(true)
                    }
                    if (existingFileSize(fd) != fileSize) {
                        truncateFile(fd, fileSize)
                    }
                    ranges.collect { (offset, data) ->
                        if (!isWriteRangeWithinFile(fileSize, offset, data.size.toLong())) {
                            throw IllegalArgumentException(AppStrings.error_write_range_invalid)
                        }
                        writeBytesAt(fd, data, offset)
                        onRangeWritten(offset, data.size)
                    }
                } finally {
                    close(fd)
                }
                Result.success(true)
            }.getOrElse { error ->
                Result.failure(mapFileError(error))
            }
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
    ): Result<Boolean> = withContext(Dispatchers.Default) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
        val normalizedPath = path.trim()
        IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) {
                return@withSecurityScopeIfNeeded symbolicLinkAccessFailure()
            }
            runCatching {
                if (!isWriteRangeWithinFile(fileSize, startOffset, expectedBytes)) {
                    throw IllegalArgumentException(AppStrings.error_write_range_invalid)
                }
                val fd = openFileDescriptor(normalizedPath, O_RDWR or O_CREAT)
                try {
                    if (fileSize == 0L) {
                        truncateFile(fd, 0L)
                        return@runCatching Result.success(true)
                    }
                    if (existingFileSize(fd) != fileSize) {
                        truncateFile(fd, fileSize)
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
                        writeBytesAt(fd, buffer, 0, read, offset)
                        onBytesWritten(offset, read)
                        offset += read
                        remaining -= read
                    }
                } finally {
                    close(fd)
                }
                Result.success(true)
            }.getOrElse { error ->
                Result.failure(mapFileError(error))
            }
        }
    }

    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        val normalizedPath = path.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) {
                return@withSecurityScopeIfNeeded symbolicLinkAccessFailure()
            }
            runCatching {
                val fd = openFileDescriptor(normalizedPath, O_RDWR or O_CREAT)
                close(fd)
                Result.success(true)
            }.getOrElse { error ->
                Result.failure(mapFileError(error))
            }
        }
    }

    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> {
        requireFileAccess(permission)
        val normalizedPath = path.trim()
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) throw symbolicLinkAccessException()
            if (!fileManager.fileExistsAtPath(normalizedPath)) {
                throw Exception("File not found")
            }
            runCatching {
                val fd = openFileDescriptor(normalizedPath, O_RDONLY)
                try {
                    val size = existingFileSize(fd)
                    if (size <= 0L) emptyList() else {
                        val source = Buffer()
                        source.write(readBytesAt(fd, 0L, size.toInt()))
                        source.readAllLines()
                    }
                } finally {
                    close(fd)
                }
            }.getOrElse { error ->
                throw mapFileError(error)
            }
        }
    }

    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        val normalizedPath = path.trim()
        IosSecurityScopeStore.withSecurityScopeIfNeeded(normalizedPath) {
            if (PathUtils.isSymbolicLink(permission, normalizedPath)) throw symbolicLinkAccessException()
            if (!fileManager.fileExistsAtPath(normalizedPath)) {
                throw Exception("File not found")
            }
            runCatching {
                val fd = openFileDescriptor(normalizedPath, O_WRONLY or O_APPEND or O_CREAT)
                try {
                    val bytes = content.encodeToByteArray()
                    bytes.usePinned { pinned ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
                            if (written < 0) throw posixFileException("Write failed")
                            if (written == 0L) throw Exception("Write failed: wrote 0 bytes")
                            offset += written.toInt()
                        }
                    }
                } finally {
                    close(fd)
                }
            }.getOrElse { error ->
                throw mapFileError(error)
            }
        }
        return
    }

    private fun BufferedSource.readAllLines(): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readUtf8Line() ?: break
            lines += line
        }
        return lines
    }

    private fun openFileDescriptor(path: String, flags: Int): Int {
        val fd = open(path, flags or O_NOFOLLOW, FILE_MODE)
        if (fd < 0) throw posixFileException("Open failed")
        return fd
    }

    private fun truncateFile(fd: Int, size: Long) {
        if (ftruncate(fd, size) != 0) throw posixFileException("Resize failed")
    }

    private fun readBytesAt(path: String, offset: Long, length: Int): ByteArray {
        val fd = openFileDescriptor(path, O_RDONLY)
        return try {
            readBytesAt(fd, offset, length)
        } finally {
            close(fd)
        }
    }

    private fun readBytesAt(fd: Int, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var totalRead = 0
        bytes.usePinned { pinned ->
            while (totalRead < length) {
                val read = pread(fd, pinned.addressOf(totalRead), (length - totalRead).toULong(), offset + totalRead)
                if (read < 0) throw posixFileException("Read failed")
                if (read == 0L) break
                totalRead += read.toInt()
            }
        }
        return if (totalRead == length) bytes else bytes.copyOf(totalRead)
    }

    private fun writeBytesAt(fd: Int, data: ByteArray, offset: Long) {
        writeBytesAt(fd, data, 0, data.size, offset)
    }

    private fun writeBytesAt(fd: Int, data: ByteArray, dataOffset: Int, length: Int, fileOffset: Long) {
        var totalWritten = 0
        data.usePinned { pinned ->
            while (totalWritten < length) {
                val written = pwrite(
                    fd,
                    pinned.addressOf(dataOffset + totalWritten),
                    (length - totalWritten).toULong(),
                    fileOffset + totalWritten
                )
                if (written < 0) throw posixFileException("Write failed")
                if (written == 0L) throw Exception("Write failed: wrote 0 bytes")
                totalWritten += written.toInt()
            }
        }
    }

    private fun posixFileException(message: String): Exception {
        val error = platform.posix.errno
        if (error == ELOOP) return symbolicLinkAccessException()
        val detail = strerror(error)?.toKString() ?: "unknown"
        return Exception("$message: $detail")
    }

    private fun existingFileSize(path: String): Long? {
        return memScoped {
            val statBuf = alloc<stat>()
            if (lstat(path, statBuf.ptr) != 0) null else statBuf.st_size
        }
    }

    private fun existingFileSize(fd: Int): Long {
        return memScoped {
            val statBuf = alloc<stat>()
            if (fstat(fd, statBuf.ptr) != 0) throw posixFileException("Stat failed")
            statBuf.st_size
        }
    }

    private fun pathExistsNoFollow(permission: FileAccessPermission, path: String): Boolean =
        fileManager.fileExistsAtPath(path) || PathUtils.isSymbolicLink(permission, path)

    private fun symbolicLinkAccessException(): AuthorityException =
        AuthorityException(AppStrings.ui_file_access_through_symbolic_links_was_rejected)

    private fun <T> symbolicLinkAccessFailure(): Result<T> =
        Result.failure(symbolicLinkAccessException())

    private fun mapFileError(throwable: Throwable): Throwable {
        if (throwable is AuthorityException) return throwable
        val message = throwable.message.orEmpty()
        val lower = message.lowercase()
        return when {
            AppStrings.legacy_symbolic_link_marker in message ||
            AppStrings.legacy_symbolic_link_marker in message ||
                "symbolic link" in lower ||
                "symlink" in lower ||
                "too many levels" in lower -> symbolicLinkAccessException()

            message.contains("permission denied", ignoreCase = true) ||
                message.contains("operation not permitted", ignoreCase = true) -> AuthorityException("Permission denied")

            throwable is Exception -> throwable
            else -> Exception(message.ifEmpty { "IO failed" })
        }
    }

    private fun buildFileSimpleInfo(path: String): Result<FileSimpleInfo> {
        return runCatching {
            memScoped {
                val isSymbolicLink =
                    fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) == NSFileTypeSymbolicLink
                val isDir = alloc<BooleanVar>()
                val exists = isSymbolicLink || fileManager.fileExistsAtPath(path, isDir.ptr)
                if (!exists) throw Exception("File not found")
                val statBuf = alloc<stat>()
                if (lstat(path, statBuf.ptr) != 0) throw Exception("File not found")
                val name = path.substringAfterLast('/')
                val isDirectory = !isSymbolicLink && isDir.value
                val isHidden = name.startsWith(".")
                val extension = name.substringAfterLast('.', "").lowercase()
                val mineType = if (!isDirectory && extension.isNotEmpty()) ".${extension}" else ""
                val size = statBuf.st_size
                val directorySize = if (isDirectory) {
                    countDirectoryEntries(path)
                } else {
                    size
                }
                val createdDate = timespecToMillis(statBuf.st_birthtimespec).takeIf { item ->  item != 0L }
                    ?: timespecToMillis(statBuf.st_ctimespec)
                val updatedDate = timespecToMillis(statBuf.st_mtimespec)

                FileSimpleInfo(
                    name = name,
                    description = "",
                    isDirectory = isDirectory,
                    isHidden = isHidden,
                    path = path,
                    mineType = mineType,
                    size = directorySize,
                    createdDate = createdDate,
                    updatedDate = updatedDate,
                    isSymbolicLink = isSymbolicLink,
                    isSymbolicLinkKnown = true,
                )
            }
        }
    }

    private fun countDirectoryEntries(path: String): Long {
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val contents = fileManager.contentsOfDirectoryAtPath(path, error.ptr)
            contents?.size?.toLong() ?: 0L
        }
    }

    private fun getFileSystemAttribute(path: String, key: String?): Long {
        val resolvedKey = key ?: return 0L
        val attributes = fileManager.attributesOfFileSystemForPath(path, error = null) ?: return 0L
        val value = attributes[resolvedKey] as? NSNumber
        return value?.longLongValue ?: 0L
    }

    private fun timespecToMillis(spec: timespec): Long {
        return spec.tv_sec * 1_000L + spec.tv_nsec / 1_000_000L
    }

    private const val FILE_MODE = S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH
}
