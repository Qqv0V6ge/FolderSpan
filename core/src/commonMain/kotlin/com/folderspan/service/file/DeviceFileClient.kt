package com.folderspan.service.file

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveUnsupportedException
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import strings.AppStrings

private const val DEVICE_FILE_DOWNLOAD_BUFFER_BYTES = 256 * 1024
private const val DEVICE_FILE_UPLOAD_BUFFER_BYTES = 256 * 1024

interface DeviceFileClient {
    fun transferStatus(): HttpTransferStatus = HttpTransferStatus.default()

    suspend fun archiveCapabilities(): FolderSpanArchiveStreamCapabilities =
        FolderSpanArchiveStreamCapabilities.unsupported()

    suspend fun readArchiveStream(
        request: FolderSpanArchiveReadRequest,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> = Result.failure(FolderSpanArchiveUnsupportedException())

    suspend fun writeArchiveStream(
        request: FolderSpanArchiveWriteRequest,
        chunks: Flow<ByteArray>,
    ): Result<FolderSpanArchiveTransferResult> = Result.failure(FolderSpanArchiveUnsupportedException())

    suspend fun renames(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>>

    suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>>

    suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>>

    suspend fun deletes(paths: List<String>): Result<List<Result<Boolean>>>

    suspend fun writeStream(
        path: String,
        fileSize: Long,
        startOffset: Long,
        chunks: Flow<ByteArray>,
    ): Result<Boolean> {
        return writeRangeStream(
            path = path,
            fileSize = fileSize,
            startOffset = startOffset,
            endOffset = fileSize,
            chunks = chunks,
        )
    }

    suspend fun writeRangeStream(
        path: String,
        fileSize: Long,
        startOffset: Long,
        endOffset: Long,
        chunks: Flow<ByteArray>,
    ): Result<Boolean> {
        return runCatching {
            require(startOffset >= 0L && endOffset >= startOffset && endOffset <= fileSize) {
                "invalid device write range: $startOffset..$endOffset/$fileSize"
            }
            var writeOffset = startOffset
            chunks.collect { chunk ->
                if (chunk.isEmpty()) return@collect
                require(writeOffset <= endOffset - chunk.size.toLong()) {
                    "device write stream exceeds declared range"
                }
                val written = writeBytes(
                    fileSize = fileSize,
                    blockIndex = 0L,
                    blockLength = chunk.size.toLong(),
                    path = path,
                    byteArray = chunk,
                    startOffset = writeOffset,
                ).getOrThrow()
                if (!written) throw IllegalStateException("device write failed")
                writeOffset += chunk.size.toLong()
            }
            require(writeOffset == endOffset) {
                "device write stream length mismatch: ${writeOffset - startOffset}/${endOffset - startOffset}"
            }
            true
        }
    }

    suspend fun uploadRangeFromFile(
        localPath: String,
        localStartOffset: Long,
        localEndOffset: Long,
        remotePath: String,
        remoteFileSize: Long,
        remoteStartOffset: Long = localStartOffset,
    ): Result<Boolean> {
        if (localStartOffset < 0L || localEndOffset < localStartOffset) {
            return Result.failure(
                IllegalArgumentException("invalid local upload range: $localStartOffset..$localEndOffset")
            )
        }
        val expectedBytes = localEndOffset - localStartOffset
        if (
            remoteFileSize < 0L ||
            expectedBytes > remoteFileSize ||
            remoteStartOffset < 0L ||
            remoteStartOffset > remoteFileSize - expectedBytes
        ) {
            return Result.failure(
                IllegalArgumentException(
                    "invalid remote upload range: $remoteStartOffset..${remoteStartOffset + expectedBytes}/$remoteFileSize"
                )
            )
        }
        val chunks = flow {
            FileUtils.readFileRangeChunks(
                permission = FileAccessPermission.Allowed,
                path = localPath,
                start = localStartOffset,
                end = localEndOffset,
                chunkSize = DEVICE_FILE_UPLOAD_BUFFER_BYTES.toLong(),
            ).collect { chunkResult ->
                val chunk = chunkResult.getOrElse { error -> throw error }.second
                if (chunk.isNotEmpty()) emit(chunk)
            }
        }
        return writeRangeStream(
            path = remotePath,
            fileSize = remoteFileSize,
            startOffset = remoteStartOffset,
            endOffset = remoteStartOffset + expectedBytes,
            chunks = chunks,
        )
    }

    suspend fun readStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> {
        val bytes = readBytes(path, startOffset, endOffset).getOrElse { error ->
            return Result.failure(error)
        }
        onChunk(bytes)
        return Result.success(true)
    }

    suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ): Result<Boolean>

    suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray>

    suspend fun downloadRangeToFile(
        remotePath: String,
        startOffset: Long,
        endOffset: Long,
        localPath: String,
        fileSize: Long,
        localOffset: Long = startOffset,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = coroutineScope {
        val expectedBytes = (endOffset - startOffset).coerceAtLeast(0L)
        var receivedBytes = 0L
        val chunks = Channel<ByteArray>(capacity = 1)
        val source = async {
            var closeCause: Throwable? = null
            try {
                val read = readStream(remotePath, startOffset, endOffset) { chunk ->
                    if (chunk.isEmpty()) return@readStream
                    val nextReceivedBytes = receivedBytes + chunk.size.toLong()
                    if (nextReceivedBytes > expectedBytes) {
                        throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                    }
                    chunks.send(chunk)
                    receivedBytes = nextReceivedBytes
                }.getOrElse { error -> throw error }
                if (!read) {
                    throw IllegalStateException(AppStrings.ui_read_failed)
                }
                Result.success(true)
            } catch (cancel: CancellationException) {
                closeCause = cancel
                throw cancel
            } catch (error: Throwable) {
                closeCause = error
                Result.failure(error)
            } finally {
                chunks.close(closeCause)
            }
        }
        val chunkReader = DeviceFileDownloadChunkReader(chunks)
        val write = FileUtils.writeByteStream(
            permission = FileAccessPermission.Allowed,
            path = localPath,
            fileSize = fileSize,
            startOffset = localOffset,
            expectedBytes = expectedBytes,
            bufferSize = DEVICE_FILE_DOWNLOAD_BUFFER_BYTES,
            readNext = chunkReader::readNext,
            onBytesWritten = onBytesWritten,
        )
        if (write.isFailure || !write.getOrDefault(false)) {
            source.cancel()
            source.join()
            return@coroutineScope Result.failure(
                write.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_write_failed)
            )
        }
        val read = source.await()
        if (read.isFailure || !read.getOrDefault(false)) {
            return@coroutineScope Result.failure(
                read.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_read_failed)
            )
        }
        if (receivedBytes != expectedBytes) {
            return@coroutineScope Result.failure(IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range))
        }
        Result.success(true)
    }

    suspend fun getFileByPath(path: String): Result<FileSimpleInfo>

    suspend fun getFileByPathAndName(path: String, name: String): Result<FileSimpleInfo>

    suspend fun getFileInfoByPath(path: String): Result<FileInfo>

    suspend fun getFileInfoByPathAndName(path: String, name: String): Result<FileInfo>

    suspend fun readFileLines(path: String): Result<List<String>>

    suspend fun appendToFile(path: String, content: String): Result<Boolean>

    suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)? = null,
        requestId: String? = null,
    ): Result<Boolean>

    suspend fun controlCopy(
        requestId: String,
        action: CopyPathControlAction,
    ): Result<Boolean>
}

private class DeviceFileDownloadChunkReader(
    private val chunks: ReceiveChannel<ByteArray>,
) {
    private var currentChunk = ByteArray(0)
    private var currentOffset = 0

    suspend fun readNext(buffer: ByteArray, length: Int): Int {
        require(length in 0..buffer.size)
        if (length == 0) return 0
        var copied = 0
        while (copied < length) {
            if (currentOffset >= currentChunk.size) {
                val next = chunks.receiveCatching()
                val chunk = next.getOrNull()
                if (chunk == null) {
                    next.exceptionOrNull()?.let { error -> throw error }
                    return if (copied == 0) -1 else copied
                }
                currentChunk = chunk
                currentOffset = 0
                if (currentChunk.isEmpty()) continue
            }
            val copyLength = minOf(length - copied, currentChunk.size - currentOffset)
            currentChunk.copyInto(
                destination = buffer,
                destinationOffset = copied,
                startIndex = currentOffset,
                endIndex = currentOffset + copyLength,
            )
            copied += copyLength
            currentOffset += copyLength
        }
        return copied
    }
}

internal interface SequentialRangeDeviceFileClient

internal interface ContinuousRangeDeviceFileClient : DeviceFileClient, SequentialRangeDeviceFileClient
