package com.folderspan.utils

import strings.AppStrings

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.folderspan.androidContext
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.exception.AuthorityException
import com.folderspan.privileged.PrivilegedFileAccess
import com.folderspan.privileged.PrivilegedFileBackends
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.*

actual object FileUtils {

    private const val CONTENT_URI_READ_ONLY_PERMISSIONS = 0b100100100

    actual fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) {
            return contentUriToFileInfo(path)
        }
        return withPrivilegedFallback(
            primary = { localGetFileInfo(path) },
            fallback = { client -> client.getFileInfo(path) }
        )
    }

    actual fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) {
            return contentUriToFileSimpleInfo(path)
        }
        return withPrivilegedFallback(
            primary = { localGetFile(path) },
            fallback = { client -> client.getFile(path) }
        )
    }

    actual fun getFile(
        permission: FileAccessPermission,
        path: String,
        fileName: String,
    ): Result<FileSimpleInfo> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        return withPrivilegedFallback(
            primary = { localGetFile(path, fileName) },
            fallback = { client -> client.getFile(path, fileName) }
        )
    }

    actual fun openFile(permission: FileAccessPermission, file: String) {
        requireFileAccess(permission)
        val context = androidContext()
        if (file.startsWith("content://")) {
            val uri = file.toUri()
            val mimeType = context.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(chooser)
            }.onFailure { error ->
                Toast.makeText(context, AppStrings.ui_unable_open_file, Toast.LENGTH_SHORT).show()
                LogKit.e(AppStrings.ui_openfile_unable_open_content_uri_arg0.format(arg0 = file), error)
            }
            return
        }
        if (PathUtils.isSymbolicLink(permission, file)) {
            throw AuthorityException(AppStrings.ui_forbidden_to_open_symbolic_links)
        }
        SensitiveFileAccessPolicy.deniedExceptionForFileProvider(file)?.let { error -> throw error }
        val target = File(file)
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(target.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"

        val uri = FileProvider.getUriForFile(context, "com.folderspan.provider", target)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(chooser)
        }.onFailure { error ->
            Toast.makeText(context, AppStrings.ui_unable_open_file_arg0.format(arg0 = target.name), Toast.LENGTH_SHORT).show()
            LogKit.e(AppStrings.ui_openfile_unable_open_file_arg0.format(arg0 = file), error)
        }
    }

    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) return contentUriReadOnlyFailure()
        return withPrivilegedFallback(
            primary = { localDeleteFile(path) },
            fallback = { client -> client.delete(path) }
        )
    }

    actual fun totalSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return PrivilegedFileAccess.withFallback(
            primary = {
                localTotalSpace(path)?.let { value -> Result.success(value) }
                    ?: Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_space_information))
            },
            operation = { client -> client.totalSpace(path) }
        ).getOrElse { 0L }
    }

    actual fun freeSpace(permission: FileAccessPermission, path: String): Long {
        requireFileAccess(permission)
        return PrivilegedFileAccess.withFallback(
            primary = {
                localFreeSpace(path)?.let { value -> Result.success(value) }
                    ?: Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_space_information))
            },
            operation = { client -> client.freeSpace(path) }
        ).getOrElse { 0L }
    }

    actual fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) return contentUriReadOnlyFailure()
        if (PathUtils.isSymbolicLink(permission, path)) return symbolicLinkAccessFailure()
        return withPrivilegedFallback(
            primary = { localCreateFolder(path) },
            fallback = { client -> client.createDirectory(path) }
        )
    }

    actual fun rename(
        permission: FileAccessPermission,
        path: String,
        oldName: String,
        newName: String,
    ): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) return contentUriReadOnlyFailure()
        return withPrivilegedFallback(
            primary = { localRename(path, oldName, newName) },
            fallback = { client -> client.rename(path, oldName, newName) }
        )
    }

    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) {
            return runCatching {
                val context = androidContext()
                val uri = path.toUri()
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val length = afd.length
                    if (length > MAX_IN_MEMORY_READ_BYTES) {
                        throw IllegalStateException(AppStrings.ui_file_too_large_cannot_read_all_at_once_arg0_bytes_please_use_chunked_reading.format(arg0 = (length).toString()))
                    }
                }
                context.contentResolver.openInputStream(uri)?.source()?.buffer()?.use { source ->
                    source.readByteArray()
                } ?: throw IllegalStateException(AppStrings.ui_unable_to_read_shared_files)
            }.fold(
                onSuccess = { item -> Result.success(item) },
                onFailure = { item -> Result.failure(item) }
            )
        }
        if (PathUtils.isSymbolicLink(permission, path)) return symbolicLinkAccessFailure()
        return withPrivilegedFallback(
            primary = { localReadFile(path) },
            fallback = { client -> remoteReadFile(client, path) }
        )
    }

    actual fun readFileRange(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
    ): Result<ByteArray> =
        run {
            if (!hasFileAccess(permission)) return@run deniedFileAccessResult()
            if (path.startsWith("content://")) {
                return@run runCatching {
                    readContentUriRange(path, start, end)
                }.fold(
                    onSuccess = { item -> Result.success(item) },
                    onFailure = { item -> Result.failure(item) }
                )
            }
            if (PathUtils.isSymbolicLink(permission, path)) return@run symbolicLinkAccessFailure()
            withPrivilegedFallback(
                primary = { localReadFileRange(path, start, end) },
                fallback = { client -> remoteReadFileRange(client, path, start, end) }
            )
        }

    private fun readContentUriRange(path: String, start: Long, end: Long): ByteArray {
        val context = androidContext()
        val uri = path.toUri()
        val safeStart = start.coerceAtLeast(0L)
        val expectedSize = (end - safeStart).coerceAtLeast(0L)
        if (expectedSize == 0L) return byteArrayOf()
        if (expectedSize > DEVICE_DIRECT_MAX_LENGTH.toLong()) {
            throw IllegalArgumentException(AppStrings.ui_read_range_exceeds_single_block_limit_arg0.format(arg0 = (DEVICE_DIRECT_MAX_LENGTH).toString()))
        }

        val dataByFileDescriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    input.channel.position(safeStart)
                    input.source().buffer().use { source ->
                        source.readRangeBytes(0L, expectedSize)
                    }
                }
            }
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_content_uri_file_descriptor_read_failed_fallback_stream_read.format(arg0 = path, arg1 = (error.message).toString()))
        }.getOrNull()

        if (dataByFileDescriptor != null) {
            return dataByFileDescriptor
        }

        val dataByAssetDescriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.createInputStream().use { input ->
                    input.channel.position(safeStart)
                    input.source().buffer().use { source ->
                        source.readRangeBytes(0L, expectedSize)
                    }
                }
            }
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_content_uri_resource_descriptor_reading_failed_fallback_stream_reading.format(arg0 = path, arg1 = (error.message).toString()))
        }.getOrNull()

        if (dataByAssetDescriptor != null) {
            return dataByAssetDescriptor
        }

        return context.contentResolver.openInputStream(uri)?.source()?.buffer()?.use { source ->
            source.readRangeBytes(start, end)
        } ?: throw IllegalStateException(AppStrings.ui_unable_to_read_shared_files)
    }

    private fun skipBufferedSource(source: BufferedSource, byteCount: Long): Long {
        var remaining = byteCount.coerceAtLeast(0L)
        if (remaining == 0L) return 0L
        val scratch = ByteArray(minOf(remaining, 8_192L).toInt())
        var skipped = 0L
        while (remaining > 0) {
            val read = source.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read <= 0) break
            skipped += read
            remaining -= read
        }
        return skipped
    }

    private fun readContentUriRangeChunks(
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

        val context = androidContext()
        val uri = path.toUri()
        val bufferSize = chunkSize.toInt()

        suspend fun emitFromCurrentPosition(readChunk: (Int) -> ByteArray?) {
            var offset = start
            while (offset < end) {
                val remaining = end - offset
                val readSize = minOf(bufferSize.toLong(), remaining).toInt()
                val data = readChunk(readSize) ?: break
                if (data.isEmpty()) break
                emit(Result.success(offset to data))
                offset += data.size
            }
            if (offset != end) {
                emit(Result.failure(Exception(AppStrings.ui_read_range_length_mismatch)))
            }
        }

        val descriptorRead = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    input.channel.position(start)
                    input.source().buffer().use { source ->
                        emitFromCurrentPosition { readSize ->
                            source.readChunkOrNull(readSize)
                        }
                    }
                }
                true
            } ?: false
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_content_uri_range_chunked_file_descriptor_read_failed_fallback.format(arg0 = path, arg1 = (error.message).toString()))
        }.getOrDefault(false)
        if (descriptorRead) return@flow

        val assetDescriptorRead = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.createInputStream().use { input ->
                    input.channel.position(start)
                    input.source().buffer().use { source ->
                        emitFromCurrentPosition { readSize ->
                            source.readChunkOrNull(readSize)
                        }
                    }
                }
                true
            } ?: false
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_content_uri_range_block_resource_descriptor_read_failed_fallback.format(arg0 = path, arg1 = (error.message).toString()))
        }.getOrDefault(false)
        if (assetDescriptorRead) return@flow

        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            emit(Result.failure(IllegalStateException(AppStrings.ui_unable_to_read_shared_files)))
            return@flow
        }
        input.source().buffer().use { source ->
            val skipped = skipBufferedSource(source, start)
            if (skipped != start) {
                emit(Result.failure(Exception(AppStrings.ui_read_range_length_mismatch)))
                return@flow
            }
            emitFromCurrentPosition { readSize ->
                source.readChunkOrNull(readSize)
            }
        }
    }.flowOn(Dispatchers.IO)

    actual fun readFileChunks(
        permission: FileAccessPermission,
        path: String,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> {
        if (!hasFileAccess(permission)) return flow { emit(deniedFileAccessResult()) }
        if (path.startsWith("content://")) {
            return flow {
                val context = androidContext()
                val uri = path.toUri()
                val bufferSize = when {
                    chunkSize <= 0 -> DEFAULT_BUFFER_SIZE
                    chunkSize > Int.MAX_VALUE -> {
                        emit(Result.failure(Exception(AppStrings.ui_invalid_block_size)))
                        return@flow
                    }

                    else -> chunkSize.toInt()
                }
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    emit(Result.failure(IllegalStateException(AppStrings.ui_unable_to_read_shared_files)))
                    return@flow
                }
                input.source().buffer().use { source ->
                    var index = 0L
                    while (true) {
                        val chunk = source.readChunkOrNull(bufferSize) ?: break
                        emit(Result.success(index to chunk))
                        index++
                    }
                }
            }.flowOn(Dispatchers.IO)
        }
        if (PathUtils.isSymbolicLink(permission, path)) {
            return flow { emit(symbolicLinkAccessFailure()) }
        }
        return readFileChunksWithFallback(path, chunkSize).flowOn(Dispatchers.IO)
    }

    actual fun readFileRangeChunks(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>> {
        if (!hasFileAccess(permission)) return flow { emit(deniedFileAccessResult()) }
        if (path.startsWith("content://")) {
            return readContentUriRangeChunks(path, start, end, chunkSize)
        }
        if (PathUtils.isSymbolicLink(permission, path)) {
            return flow { emit(symbolicLinkAccessFailure()) }
        }
        return readFileRangeChunksWithFallback(path, start, end, chunkSize).flowOn(Dispatchers.IO)
    }

    actual suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!hasFileAccess(permission)) return@withContext deniedFileAccessResult()
        if (path.startsWith("content://")) {
            contentUriReadOnlyFailure()
        } else if (PathUtils.isSymbolicLink(permission, path)) {
            symbolicLinkAccessFailure()
        } else {
            withPrivilegedFallback(
                primary = { localWriteBytes(path, fileSize, data, offset) },
                fallback = { client -> remoteWriteBytes(client, path, fileSize, data, offset) }
            )
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
        if (path.startsWith("content://")) {
            contentUriReadOnlyFailure()
        } else if (PathUtils.isSymbolicLink(permission, path)) {
            symbolicLinkAccessFailure()
        } else {
            PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
                val client = backend.withClient { item -> Result.success(item) }.getOrNull()
                    ?: return@withContext Result.failure(AuthorityException(AppStrings.ui_root_service_is_not_ready))
                return@withContext remoteWriteByteRanges(client, path, fileSize, ranges, onRangeWritten)
            }
            val primary = localWriteByteRanges(path, fileSize, ranges, onRangeWritten)
            if (primary.isSuccess || !primary.exceptionOrNull().isPermissionError()) {
                return@withContext primary
            }
            PrivilegedFileBackends.preferredAuthorized()?.let { backend ->
                val client = backend.withClient { item -> Result.success(item) }.getOrNull() ?: return@let
                val result = remoteWriteByteRanges(client, path, fileSize, ranges, onRangeWritten)
                if (result.isSuccess) return@withContext result
            }
            primary
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
        if (path.startsWith("content://")) {
            contentUriReadOnlyFailure()
        } else if (PathUtils.isSymbolicLink(permission, path)) {
            symbolicLinkAccessFailure()
        } else {
            PrivilegedFileBackends.beforeLocalIo()?.let { backend ->
                val client = backend.withClient { item -> Result.success(item) }.getOrNull()
                    ?: return@withContext Result.failure(AuthorityException(AppStrings.ui_root_service_is_not_ready))
                return@withContext remoteWriteByteStream(
                    client = client,
                    path = path,
                    fileSize = fileSize,
                    startOffset = startOffset,
                    expectedBytes = expectedBytes,
                    bufferSize = bufferSize,
                    readNext = readNext,
                    onBytesWritten = onBytesWritten,
                )
            }
            val primary = localWriteByteStream(
                path = path,
                fileSize = fileSize,
                startOffset = startOffset,
                expectedBytes = expectedBytes,
                bufferSize = bufferSize,
                readNext = readNext,
                onBytesWritten = onBytesWritten,
            )
            if (primary.isSuccess || !primary.exceptionOrNull().isPermissionError()) {
                return@withContext primary
            }
            PrivilegedFileBackends.preferredAuthorized()?.let { backend ->
                val client = backend.withClient { item -> Result.success(item) }.getOrNull() ?: return@let
                val result = remoteWriteByteStream(
                    client = client,
                    path = path,
                    fileSize = fileSize,
                    startOffset = startOffset,
                    expectedBytes = expectedBytes,
                    bufferSize = bufferSize,
                    readNext = readNext,
                    onBytesWritten = onBytesWritten,
                )
                if (result.isSuccess) return@withContext result
            }
            primary
        }
    }

    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> {
        if (!hasFileAccess(permission)) return deniedFileAccessResult()
        if (path.startsWith("content://")) return contentUriReadOnlyFailure()
        if (PathUtils.isSymbolicLink(permission, path)) return symbolicLinkAccessFailure()
        return withPrivilegedFallback(
            primary = { localCreateFile(path) },
            fallback = { client -> client.createFile(path) }
        )
    }

    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> {
        requireFileAccess(permission)
        if (path.startsWith("content://")) {
            val context = androidContext()
            val uri = path.toUri()
            return context.contentResolver.openInputStream(uri)?.source()?.buffer()?.use { source ->
                source.readAllLines()
            } ?: throw IllegalStateException(AppStrings.ui_unable_to_read_shared_files)
        }
        if (PathUtils.isSymbolicLink(permission, path)) throw AuthorityException(AppStrings.ui_not_allowed_to_read_symbolic_links)
        return PrivilegedFileAccess.withFallback(
            primary = {
                runCatching { localReadFileLines(path) }.fold(
                    onSuccess = { lines -> Result.success(lines) },
                    onFailure = { error -> Result.failure(error) }
                )
            },
            operation = { client ->
                client.openFile(path, ParcelFileDescriptor.MODE_READ_ONLY).mapCatching { pfd ->
                    pfd.use { item ->
                        ParcelFileDescriptor.AutoCloseInputStream(item).source().buffer().use { source ->
                            source.readAllLines()
                        }
                    }
                }
            }
        ).getOrElse { error -> throw error }
    }

    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        if (path.startsWith("content://")) {
            throw contentUriReadOnlyException()
        }
        if (PathUtils.isSymbolicLink(permission, path)) throw AuthorityException(AppStrings.ui_forbidden_to_write_symbolic_links)
        PrivilegedFileAccess.withFallback(
            primary = {
                runCatching {
                    localAppendToFile(path, content)
                    true
                }.fold(
                    onSuccess = { success -> Result.success(success) },
                    onFailure = { error -> Result.failure(error) }
                )
            },
            operation = { client ->
                client.openFile(
                    path,
                    ParcelFileDescriptor.MODE_APPEND or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
                ).mapCatching { pfd ->
                    pfd.use { item ->
                        ParcelFileDescriptor.AutoCloseOutputStream(item).sink().buffer().use { sink ->
                            sink.writeUtf8(content)
                        }
                        true
                    }
                }
            }
        ).getOrElse { error -> throw error }
        return
    }

    private fun contentUriReadOnlyFailure(): Result<Boolean> = Result.failure(contentUriReadOnlyException())

    private fun <T> symbolicLinkAccessFailure(): Result<T> =
        Result.failure(AuthorityException(AppStrings.ui_not_allowed_to_read_write_symbolic_links))

    private fun contentUriReadOnlyException() = AuthorityException(AppStrings.ui_share_files_only_support_reading)

    private fun contentUriToFileSimpleInfo(path: String): Result<FileSimpleInfo> {
        return runCatching {
            val context = androidContext()
            val resolver = context.contentResolver
            val uri = path.toUri()
            var name = uri.lastPathSegment ?: path
            var size = 0L
            val mimeType = resolver.getType(uri) ?: ""
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
            val now = System.currentTimeMillis()
            FileSimpleInfo(
                name = name,
                description = "",
                isDirectory = false,
                isHidden = false,
                path = path,
                mineType = mimeType,
                size = size,
                createdDate = now,
                updatedDate = now,
                protocol = FileProtocol.Share,
                protocolId = SYSTEM_SHARE_DESK_ID,
                isSymbolicLink = false,
                isSymbolicLinkKnown = true,
            )
        }.fold(
            onSuccess = { item -> Result.success(item) },
            onFailure = { item -> Result.failure(item) }
        )
    }

    private fun contentUriToFileInfo(path: String): Result<FileInfo> {
        return contentUriToFileSimpleInfo(path).map { simple ->
            FileInfo(
                name = simple.name,
                description = simple.description,
                isDirectory = simple.isDirectory,
                isHidden = simple.isHidden,
                path = simple.path,
                mineType = simple.mineType,
                size = simple.size,
                permissions = CONTENT_URI_READ_ONLY_PERMISSIONS,
                user = "",
                userGroup = "",
                createdDate = simple.createdDate,
                updatedDate = simple.updatedDate,
                protocol = simple.protocol,
                protocolId = simple.protocolId
            )
        }
    }
}
