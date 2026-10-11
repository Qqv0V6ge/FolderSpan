package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.DeviceCopyUnsupportedException
import com.folderspan.service.data.*
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_STREAM_BUFFER_BYTES
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_STREAM_RANGE_BYTES
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.HttpTransferStatusCache
import com.folderspan.service.operation.HttpTransferStatusHeaders
import com.folderspan.ui.state.main.isTaskLevelTransferFailure
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.EOFException
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

private const val DEVICE_COPY_LONG_STREAM_TIMEOUT_MS = 120_000L
private const val DEVICE_COPY_STREAM_TIMEOUT_MS = 30_000L
private const val DEVICE_COPY_BLOCK_TIMEOUT_MS = 20_000L
private const val DEVICE_COPY_WRITE_BLOCK_SOCKET_TIMEOUT_MS = 60_000L
private const val DEVICE_COPY_WRITE_BLOCK_MAX_ATTEMPTS = 2
private const val DEVICE_COPY_WRITE_BLOCK_RETRY_DELAY_MS = 250L
private const val FOLDER_CREATE_MAX_ATTEMPTS = 2
private const val FOLDER_CREATE_RETRY_DELAY_MS = 250L

private fun Throwable.isRetryableWriteBlockError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpRequestTimeoutException) return true
        val message = current.message.orEmpty().lowercase()
        if (message.contains("timeout") || message.contains("timed out")) return true
        current = current.cause
    }
    return false
}

class FileRouteClient(
    private val httpClient: HttpClient,
    private val manager: HttpRouteClientManager
) : DeviceFileClient {
    private val transferStatusCache = HttpTransferStatusCache()
    private val transferPlanner = AdaptiveHttpTransferPlanner()
    private val transferPlannerMutex = Mutex()

    override fun transferStatus(): HttpTransferStatus = transferStatusCache.current()

    private fun updateTransferStatus(response: HttpResponse) {
        HttpTransferStatusHeaders.parse { name -> response.headers[name] }?.let { status ->
            transferStatusCache.update(status)
        }
    }

    private fun verifyPlainByteStream(response: HttpResponse, expectedPlainLength: Long) {
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength != expectedPlainLength) {
            throw Exception(AppStrings.ui_read_length_mismatch_expect_arg0_header_arg1.format(arg0 = (expectedPlainLength).toString(), arg1 = (contentLength).toString()))
        }
    }

    private suspend fun currentTransferPlan(): AdaptiveHttpTransferPlan =
        transferPlannerMutex.withLock {
            transferPlanner.plan(transferStatus())
        }

    internal suspend fun adaptiveTransferPlan(): AdaptiveHttpTransferPlan =
        currentTransferPlan()

    private suspend fun recordTransferSuccess(bytesTransferred: Long, durationMillis: Long) {
        transferPlannerMutex.withLock {
            transferPlanner.recordSuccess(bytesTransferred, durationMillis)
        }
    }

    private suspend fun recordTransferFailure(error: Throwable) {
        transferPlannerMutex.withLock {
            if (transferStatus().busy) {
                transferPlanner.recordBusy()
            }
            transferPlanner.recordFailure(error)
        }
    }

    override suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)?,
        requestId: String?,
    ): Result<Boolean> {
        return copyPath(srcPath = srcPath, destPath = destPath, onProgress = onProgress, requestId = requestId, batchId = null)
    }

    override suspend fun renames(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>> {
        return renames(renameInfos = renameInfos, requestId = null, batchId = null)
    }

    suspend fun renames(
        renameInfos: List<RenameInfo>,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<Result<Boolean>>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_rename_request_client_count_arg0.format(arg0 = (renameInfos.size).toString()))
            val response = httpClient.post("/api/files/rename") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(RenameRequest(renameInfos)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_failed_to_rename_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val list = decodeHttpSuccessBody<List<SerializableResult>>(bytes)
                .map { result -> result.toResult<Boolean>() }
            LogKit.d(AppStrings.ui_rename_response_count_arg0.format(arg0 = (list.size).toString()))
            Result.success(list)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_rename_exception_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>> {
        return createFolders(infos = paths, requestId = null, batchId = null)
    }

    suspend fun createFolders(
        infos: List<String>,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<Result<Boolean>>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            var attempt = 1
            var finalResult: Result<List<Result<Boolean>>>? = null
            while (finalResult == null) {
                try {
                    val response = httpClient.post("/api/files/create-folder") {
                        setFolderSpanRequestBody(
                            ProtoBufCodec.encode(CreateFolderRequest(infos)),
                            manager.encryptedHttpTransport,
                        )
                    }
                    updateTransferStatus(response)

                    if (!response.status.isSuccess()) {
                        val error = readHttpResponseException(response)
                        LogKit.w(AppStrings.ui_create_folder_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                        throw error
                    }

                    val bytes = response.folderSpanBodyBytes()
                    val list = decodeHttpSuccessBody<List<SerializableResult>>(bytes)
                        .map { result -> result.toResult<Boolean>() }
                    finalResult = Result.success(list)
                } catch (e: Exception) {
                    if (attempt < FOLDER_CREATE_MAX_ATTEMPTS && e.isRetryableDeviceConnectionError()) {
                        LogKit.w(
                            AppStrings.ui_create_folder_request_for_temporary_connection_failure_retrying +
                                "count=${infos.size}, attempt=$attempt/$FOLDER_CREATE_MAX_ATTEMPTS, error=${e.message}"
                        )
                        delay((FOLDER_CREATE_RETRY_DELAY_MS * attempt).milliseconds)
                        attempt++
                        continue
                    }
                    if (e is CancellationException) {
                        LogKit.w(AppStrings.ui_folder_request_terminated_arg0.format(arg0 = (e.message).toString()))
                    } else {
                        LogKit.e(AppStrings.ui_folder_creation_exception_arg0.format(arg0 = (e.message).toString()), e)
                    }
                    finalResult = Result.failure(e)
                }
            }
            finalResult
        }
    }

    suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)? = null,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
                LogKit.i(AppStrings.ui_device_internal_copy_client_src_arg0_dest_arg1.format(arg0 = (srcPath), arg1 = (destPath)))
                val statement = httpClient.preparePost("/api/files/copy") {
                    timeout {
                        requestTimeoutMillis = DEVICE_COPY_STREAM_TIMEOUT_MS
                        connectTimeoutMillis = DEVICE_COPY_BLOCK_TIMEOUT_MS
                        socketTimeoutMillis = DEVICE_COPY_STREAM_TIMEOUT_MS
                    }
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.OctetStream.toString())
                    }
                    setFolderSpanRequestBody(
                        ProtoBufCodec.encode(CopyPathRequest(srcPath, destPath, resolveRequiredCopyRequestId(requestId))),
                        manager.encryptedHttpTransport
                    )
                }

                statement.execute { response ->
                    updateTransferStatus(response)
                    if (!response.status.isSuccess()) {
                        val error = when (response.status) {
                            HttpStatusCode.NotFound,
                            HttpStatusCode.MethodNotAllowed,
                            HttpStatusCode.NotImplemented -> DeviceCopyUnsupportedException(
                                AppStrings.ui_device_direct_copy_unsupported_http_arg0.format(arg0 = (response.status.value).toString())
                            )

                            else -> readHttpResponseException(response)
                        }
                        LogKit.w(AppStrings.ui_device_internal_copy_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                        throw error
                    }

                    var terminal: CopyPathProgress? = null
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val size = try {
                            channel.readInt()
                        } catch (_: EOFException) {
                            break
                        } catch (e: Throwable) {
                            if (channel.isClosedForRead) break else throw e
                        }
                        if (size <= 0) continue

                        val progressBytes = ByteArray(size)
                        channel.readFully(progressBytes, 0, size)
                        val progress = ProtoBufCodec.decode<CopyPathProgress>(progressBytes)
                        onProgress?.invoke(progress)
                        if (progress.done) {
                            terminal = progress
                        }
                    }

                    val terminalEvent = terminal ?: throw Exception(AppStrings.ui_copy_progress_interruption)
                    if (!terminalEvent.success) {
                        throw Exception(terminalEvent.message.ifBlank { AppStrings.ui_copy_failed })
                    }
                    LogKit.d(AppStrings.ui_device_direct_copy_completed)
                    Result.success(true)
                }
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_internal_copy_error_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    override suspend fun controlCopy(
        requestId: String,
        action: CopyPathControlAction,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult {
            try {
                val response = httpClient.post("/api/files/copy-control") {
                    setFolderSpanRequestBody(
                        ProtoBufCodec.encode(CopyPathControlRequest(requestId, action)),
                        manager.encryptedHttpTransport
                    )
                }

                if (!response.status.isSuccess()) {
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_device_replication_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                val bytes = response.folderSpanBodyBytes()
                val success = decodeHttpSuccessBody<Boolean>(bytes)
                Result.success(success)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_device_copy_control_exception_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    override suspend fun deletes(paths: List<String>): Result<List<Result<Boolean>>> {
        return deletes(paths = paths, requestId = null, batchId = null)
    }

    suspend fun deletes(
        paths: List<String>,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<Result<Boolean>>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_remove_client_count_arg0.format(arg0 = (paths.size).toString()))
            val response = httpClient.post("/api/files/delete") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(DeleteRequest(paths)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_delete_failed_http_arg0_message_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val list = decodeHttpSuccessBody<List<SerializableResult>>(bytes)
                .map { result -> result.toResult<Boolean>() }
            LogKit.d(AppStrings.ui_remove_response_count_arg0.format(arg0 = (list.size).toString()))
            Result.success(list)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_remove_exception_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ): Result<Boolean> {
        return writeBytes(
            fileSize = fileSize,
            blockIndex = blockIndex,
            blockLength = blockLength,
            path = path,
            byteArray = byteArray,
            startOffset = startOffset,
            requestId = null,
            batchId = null,
        )
    }

    suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        suspend fun execute(): Result<Boolean> {
            return try {
                if (byteArray.size.toLong() != blockLength) {
                    throw IllegalArgumentException(AppStrings.ui_insert_data_size_and_declaration_block_length_mismatch)
                }
                if (blockLength > DEVICE_DIRECT_MAX_LENGTH.toLong()) {
                    throw IllegalArgumentException(AppStrings.ui_insert_range_exceeds_device_direct_block_limit_arg0.format(arg0 = (DEVICE_DIRECT_MAX_LENGTH).toString()))
                }
                val encodedFileSize = when {
                    fileSize <= Int.MAX_VALUE.toLong() -> fileSize
                    else -> Int.MAX_VALUE.toLong()
                }
                val metadata = WriteBytesStreamRequest(
                    fileSize = encodedFileSize,
                    blockIndex = blockIndex,
                    blockLength = blockLength,
                    path = path,
                    actualFileSizeText = fileSize.toString(),
                    blockStartOffset = startOffset,
                )
                var attempt = 0
                var lastFailure: Exception? = null
                while (attempt < DEVICE_COPY_WRITE_BLOCK_MAX_ATTEMPTS) {
                    attempt += 1
                    try {
                        val response = httpClient.post("/api/files/write-bytes") {
                            timeout {
                                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                connectTimeoutMillis = DEVICE_COPY_BLOCK_TIMEOUT_MS
                                socketTimeoutMillis = DEVICE_COPY_WRITE_BLOCK_SOCKET_TIMEOUT_MS
                            }
                            contentType(ContentType.Application.OctetStream)
                            accept(ContentType.Application.ProtoBuf)
                            val encodedMetadata = Base64.encode(ProtoBufCodec.encode(metadata))
                            header(WRITE_BYTES_STREAM_REQUEST_HEADER, encodedMetadata)
                            setFolderSpanRequestBody(byteArray, manager.encryptedHttpTransport)
                        }

                        if (!response.status.isSuccess()) {
                            updateTransferStatus(response)
                            val error = readHttpResponseException(response)
                            LogKit.w(AppStrings.ui_insert_block_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                            throw error
                        }

                        updateTransferStatus(response)
                        val bytes = response.folderSpanBodyBytes()
                        val ok = decodeHttpSuccessBody<Boolean>(bytes)
                        if (!ok) LogKit.w(AppStrings.ui_insert_block_failed_path_arg0_idx_arg1.format(arg0 = (path), arg1 = (blockIndex).toString()))
                        return Result.success(ok)
                    } catch (e: Exception) {
                        lastFailure = e
                        if (attempt < DEVICE_COPY_WRITE_BLOCK_MAX_ATTEMPTS && e.isRetryableWriteBlockError()) {
                            LogKit.w(
                                AppStrings.ui_write_block_timeout_retrying_path_arg0_idx_arg1.format(arg0 = (path), arg1 = (blockIndex).toString()) +
                                    "offset=$startOffset, attempt=$attempt/$DEVICE_COPY_WRITE_BLOCK_MAX_ATTEMPTS, " +
                                    "error=${e.message}"
                            )
                            delay((DEVICE_COPY_WRITE_BLOCK_RETRY_DELAY_MS * attempt).milliseconds)
                            continue
                        }
                        throw e
                    }
                }
                Result.failure(lastFailure ?: Exception(AppStrings.ui_insert_block_failed))
            } catch (e: Exception) {
                if (e.isTaskLevelTransferFailure()) {
                    LogKit.w(AppStrings.ui_inserting_block_stop_arg0.format(arg0 = (e.message).toString()))
                } else {
                    LogKit.e(AppStrings.ui_insert_block_error_arg0.format(arg0 = (e.message).toString()), e)
                }
                Result.failure(e)
            }
        }

        if (requestId.isNullOrBlank() && batchId.isNullOrBlank()) {
            return execute()
        }
        return manager.requestRegistry.trackResult(requestId, batchId) {
            execute()
        }
    }

    suspend fun streamUploadRangeFromLocal(
        sourcePath: String,
        destinationPath: String,
        fileSize: Long,
        sourceOffset: Long,
        destinationOffset: Long,
        expectedBytes: Long,
        requestId: String? = null,
        batchId: String? = null,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        suspend fun execute(): Result<Boolean> {
            return try {
                if (sourceOffset < 0L || destinationOffset < 0L || expectedBytes < 0L) {
                    throw IllegalArgumentException(AppStrings.ui_upload_range_invalid)
                }
                if (destinationOffset + expectedBytes > fileSize) {
                    throw IllegalArgumentException(AppStrings.ui_upload_range_exceeds_file_size)
                }
                if (expectedBytes > DEVICE_DIRECT_STREAM_RANGE_BYTES.toLong()) {
                    throw IllegalArgumentException(AppStrings.ui_flow_write_range_exceeds_the_devices_direct_stream_limit_arg0.format(arg0 = (DEVICE_DIRECT_STREAM_RANGE_BYTES).toString()))
                }
                val encodedFileSize = when {
                    fileSize <= Int.MAX_VALUE.toLong() -> fileSize
                    else -> Int.MAX_VALUE.toLong()
                }
                val metadata = WriteBytesStreamRequest(
                    fileSize = encodedFileSize,
                    blockIndex = 0L,
                    blockLength = expectedBytes,
                    path = destinationPath,
                    actualFileSizeText = fileSize.toString(),
                    blockStartOffset = destinationOffset,
                )
                val encodedMetadata = Base64.encode(ProtoBufCodec.encode(metadata))
                val uploadContent = object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long = expectedBytes

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        var writtenBytes = 0L
                        FileUtils.readFileRangeChunks(FileAccessPermission.Allowed,
                            path = sourcePath,
                            start = sourceOffset,
                            end = sourceOffset + expectedBytes,
                            chunkSize = DEVICE_DIRECT_STREAM_BUFFER_BYTES.toLong(),
                        ).collect { result ->
                            val (offset, chunk) = result.getOrElse { error -> throw error }
                            val expectedOffset = sourceOffset + writtenBytes
                            if (offset != expectedOffset) {
                                throw IllegalStateException(AppStrings.ui_upload_and_read_range_offset_discontinuous)
                            }
                            if (chunk.isEmpty()) return@collect
                            channel.writeFully(chunk)
                            onBytesWritten(destinationOffset + writtenBytes, chunk.size)
                            writtenBytes += chunk.size
                        }
                        if (writtenBytes != expectedBytes) {
                            throw IllegalStateException(AppStrings.ui_upload_read_length_mismatch_expected_arg0_actual_arg1.format(arg0 = (expectedBytes).toString(), arg1 = (writtenBytes).toString()))
                        }
                    }
                }
                val plan = currentTransferPlan()
                val startedAt = getTimeMillis()
                val response = httpClient.post("/api/files/stream-upload") {
                    timeout {
                        requestTimeoutMillis = maxOf(DEVICE_COPY_LONG_STREAM_TIMEOUT_MS, plan.requestTimeoutMillis)
                        connectTimeoutMillis = DEVICE_COPY_BLOCK_TIMEOUT_MS
                        socketTimeoutMillis = maxOf(DEVICE_COPY_LONG_STREAM_TIMEOUT_MS, plan.requestTimeoutMillis)
                    }
                    contentType(ContentType.Application.OctetStream)
                    accept(ContentType.Application.ProtoBuf)
                    header(WRITE_BYTES_STREAM_REQUEST_HEADER, encodedMetadata)
                    setBody(uploadContent)
                }

                if (!response.status.isSuccess()) {
                    updateTransferStatus(response)
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_long_flow_upload_range_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                updateTransferStatus(response)
                val ok = decodeHttpSuccessBody<Boolean>(response.folderSpanBodyBytes())
                if (!ok) LogKit.w(AppStrings.ui_long_flow_upload_range_returns_failure_path_arg0_offset_arg1.format(arg0 = (destinationPath), arg1 = (destinationOffset).toString()))
                if (ok) recordTransferSuccess(expectedBytes, getTimeMillis() - startedAt)
                Result.success(ok)
            } catch (e: Exception) {
                recordTransferFailure(e)
                if (e.isTaskLevelTransferFailure()) {
                    LogKit.w(AppStrings.ui_long_flow_upload_stop_arg0.format(arg0 = (e.message).toString()))
                } else {
                    LogKit.e(AppStrings.ui_long_flow_upload_range_abnormal_arg0.format(arg0 = (e.message).toString()), e)
                }
                Result.failure(e)
            }
        }

        if (requestId.isNullOrBlank() && batchId.isNullOrBlank()) {
            return execute()
        }
        return manager.requestRegistry.trackResult(requestId, batchId) {
            execute()
        }
    }

    override suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray> {
        return readBytes(path = path, startOffset = startOffset, endOffset = endOffset, requestId = null, batchId = null)
    }

    suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<ByteArray> {
        suspend fun execute(): Result<ByteArray> =
            try {
                val expectedSize = (endOffset - startOffset).coerceAtLeast(0L)
                if (expectedSize > DEVICE_DIRECT_MAX_LENGTH.toLong()) {
                    throw Exception(AppStrings.ui_read_range_exceeds_device_direct_connection_block_arg0.format(arg0 = (DEVICE_DIRECT_MAX_LENGTH).toString()))
                }
                val plan = currentTransferPlan()
                val startedAt = getTimeMillis()
                val response = httpClient.post("/api/files/read-bytes") {
                    timeout {
                        requestTimeoutMillis = plan.requestTimeoutMillis
                        connectTimeoutMillis = plan.requestTimeoutMillis
                        socketTimeoutMillis = plan.requestTimeoutMillis
                    }
                    accept(ContentType.Application.OctetStream)
                    setFolderSpanRequestBody(
                        ProtoBufCodec.encode(ReadBytesRequest(path, startOffset, endOffset)),
                        manager.encryptedHttpTransport
                    )
                }

                if (!response.status.isSuccess()) {
                    updateTransferStatus(response)
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_read_range_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                updateTransferStatus(response)
                verifyPlainByteStream(response, expectedSize)
                val data = ByteArray(expectedSize.toInt())
                val channel = response.bodyAsChannel()
                channel.readHttpRawByteRangeInto(
                    expectedLength = expectedSize,
                    target = data,
                    endOfStreamMessage = AppStrings.ui_file_stream_ends_before_the_file_is_read,
                )
                recordTransferSuccess(expectedSize, getTimeMillis() - startedAt)
                Result.success(data)
            } catch (e: Exception) {
                recordTransferFailure(e)
                LogKit.e(AppStrings.ui_reading_range_error_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }

        if (requestId.isNullOrBlank() && batchId.isNullOrBlank()) {
            return execute()
        }
        return manager.requestRegistry.trackResult(requestId, batchId) {
            execute()
        }
    }

    suspend fun downloadBytesToFile(
        remotePath: String,
        startOffset: Long,
        endOffset: Long,
        localPath: String,
        fileSize: Long,
        localOffset: Long,
        requestId: String? = null,
        batchId: String? = null,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        suspend fun execute(): Result<Boolean> =
            try {
                val expectedSize = (endOffset - startOffset).coerceAtLeast(0L)
                if (expectedSize > DEVICE_DIRECT_MAX_LENGTH.toLong()) {
                    throw Exception(AppStrings.ui_read_range_exceeds_device_direct_connection_block_arg0.format(arg0 = (DEVICE_DIRECT_MAX_LENGTH).toString()))
                }
                val plan = currentTransferPlan()
                val startedAt = getTimeMillis()
                val response = httpClient.post("/api/files/read-bytes") {
                    timeout {
                        requestTimeoutMillis = plan.requestTimeoutMillis
                        connectTimeoutMillis = plan.requestTimeoutMillis
                        socketTimeoutMillis = plan.requestTimeoutMillis
                    }
                    accept(ContentType.Application.OctetStream)
                    setFolderSpanRequestBody(
                        ProtoBufCodec.encode(ReadBytesRequest(remotePath, startOffset, endOffset)),
                        manager.encryptedHttpTransport
                    )
                }

                if (!response.status.isSuccess()) {
                    updateTransferStatus(response)
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_download_range_write_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                updateTransferStatus(response)
                verifyPlainByteStream(response, expectedSize)
                val data = ByteArray(expectedSize.toInt())
                val channel = response.bodyAsChannel()
                channel.readHttpRawByteRangeInto(
                    expectedLength = expectedSize,
                    target = data,
                    endOfStreamMessage = AppStrings.ui_file_stream_ends_before_the_file_is_read,
                )
                val write = FileUtils.writeBytes(FileAccessPermission.Allowed,
                    path = localPath,
                    fileSize = fileSize,
                    data = data,
                    offset = localOffset,
                )
                if (write.isFailure || !write.getOrDefault(false)) {
                    throw write.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed)
                }
                onBytesWritten(localOffset, data.size)
                recordTransferSuccess(expectedSize, getTimeMillis() - startedAt)
                Result.success(true)
            } catch (e: Exception) {
                recordTransferFailure(e)
                LogKit.e(AppStrings.ui_download_range_write_exception_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }

        if (requestId.isNullOrBlank() && batchId.isNullOrBlank()) {
            return execute()
        }
        return manager.requestRegistry.trackResult(requestId, batchId) {
            execute()
        }
    }

    override suspend fun downloadRangeToFile(
        remotePath: String,
        startOffset: Long,
        endOffset: Long,
        localPath: String,
        fileSize: Long,
        localOffset: Long,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> = downloadRangeToFile(
        remotePath = remotePath,
        startOffset = startOffset,
        endOffset = endOffset,
        localPath = localPath,
        fileSize = fileSize,
        localOffset = localOffset,
        requestId = null,
        batchId = null,
        onBytesWritten = onBytesWritten,
    )

    suspend fun downloadRangeToFile(
        remotePath: String,
        startOffset: Long,
        endOffset: Long,
        localPath: String,
        fileSize: Long,
        localOffset: Long = startOffset,
        requestId: String? = null,
        batchId: String? = null,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        val expectedSize = (endOffset - startOffset).coerceAtLeast(0L)
        return when (selectDeviceDirectDownloadToFileRoute(expectedSize)) {
            DeviceDirectReadRoute.ReadBytes -> downloadBytesToFile(
                remotePath = remotePath,
                startOffset = startOffset,
                endOffset = endOffset,
                localPath = localPath,
                fileSize = fileSize,
                localOffset = localOffset,
                requestId = requestId,
                batchId = batchId,
                onBytesWritten = onBytesWritten,
            )
            DeviceDirectReadRoute.StreamFile -> streamFileRangeToFile(
                remotePath = remotePath,
                startOffset = startOffset,
                endOffset = endOffset,
                localPath = localPath,
                fileSize = fileSize,
                localOffset = localOffset,
                requestId = requestId,
                batchId = batchId,
                onBytesWritten = onBytesWritten,
            )
        }
    }

    suspend fun streamFileRangeToFile(
        remotePath: String,
        startOffset: Long,
        endOffset: Long,
        localPath: String,
        fileSize: Long,
        localOffset: Long = startOffset,
        requestId: String? = null,
        batchId: String? = null,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean> {
        suspend fun execute(): Result<Boolean> {
            val expectedSize = (endOffset - startOffset).coerceAtLeast(0L)
            val plan = currentTransferPlan()
            val startedAt = getTimeMillis()
            val result = streamHttpRangeToFile(
                httpClient = httpClient,
                endpoint = "/api/files/stream-file",
                requestBody = ProtoBufCodec.encode(DeviceStreamFileRequest(remotePath, startOffset, endOffset)),
                expectedSize = expectedSize,
                maxExpectedSize = DEVICE_DIRECT_STREAM_RANGE_BYTES.toLong(),
                maxExpectedSizeError = AppStrings.ui_flow_reading_exceeds_the_devices_direct_stream_limit_arg0.format(arg0 = (DEVICE_DIRECT_STREAM_RANGE_BYTES).toString()),
                localPath = localPath,
                fileSize = fileSize,
                localOffset = localOffset,
                bufferSize = DEVICE_DIRECT_STREAM_BUFFER_BYTES,
                requestTimeoutMs = maxOf(DEVICE_COPY_LONG_STREAM_TIMEOUT_MS, plan.requestTimeoutMillis),
                connectTimeoutMs = DEVICE_COPY_BLOCK_TIMEOUT_MS,
                socketTimeoutMs = maxOf(DEVICE_COPY_LONG_STREAM_TIMEOUT_MS, plan.requestTimeoutMillis),
                updateTransferStatus = ::updateTransferStatus,
                verifyByteStream = ::verifyPlainByteStream,
                encryptedTransport = manager.encryptedHttpTransport,
                failureLogMessage = { response, error ->
                    AppStrings.ui_long_flow_download_range_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString())
                },
                exceptionLogMessage = { error -> AppStrings.ui_long_flow_download_range_anomaly_arg0.format(arg0 = (error.message).toString()) },
                onBytesWritten = onBytesWritten,
            )
            if (result.isSuccess && result.getOrDefault(false)) {
                recordTransferSuccess(expectedSize, getTimeMillis() - startedAt)
            } else {
                result.exceptionOrNull()?.let { error -> recordTransferFailure(error) }
            }
            return result
        }

        if (requestId.isNullOrBlank() && batchId.isNullOrBlank()) {
            return execute()
        }
        return manager.requestRegistry.trackResult(requestId, batchId) {
            execute()
        }
    }

    override suspend fun getFileByPath(path: String): Result<FileSimpleInfo> {
        return getFileByPath(path = path, requestId = null, batchId = null)
    }

    suspend fun getFileByPath(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<FileSimpleInfo> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_file_by_path_arg0.format(arg0 = (path)))
            val response = httpClient.post("/api/files/get-file-by-path") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(GetFileByPathRequest(path)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_failed_to_retrieve_file_by_path_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val info = decodeHttpSuccessBody<FileSimpleInfo>(bytes)
            LogKit.d(AppStrings.ui_file_retrieved_successfully_arg0.format(arg0 = (info.name)))
            Result.success(info)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_file_retrieval_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun getFileByPathAndName(path: String, name: String): Result<FileSimpleInfo> {
        return getFileByPathAndName(path = path, name = name, requestId = null, batchId = null)
    }

    suspend fun getFileByPathAndName(
        path: String,
        name: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<FileSimpleInfo> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_file_by_path_and_name_arg0_arg1.format(arg0 = (path), arg1 = (name)))
            val response = httpClient.post("/api/files/get-file-by-path-and-name") {
                setFolderSpanRequestBody(
                    ProtoBufCodec.encode(GetFileByPathAndNameRequest(path, name)),
                    manager.encryptedHttpTransport
                )
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_file_retrieval_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val info = decodeHttpSuccessBody<FileSimpleInfo>(bytes)
            LogKit.d(AppStrings.ui_file_retrieved_successfully_by_path_name_arg0.format(arg0 = (info.name)))
            Result.success(info)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_file_not_found_at_path_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun getFileInfoByPath(path: String): Result<FileInfo> {
        return getFileInfoByPath(path = path, requestId = null, batchId = null)
    }

    suspend fun getFileInfoByPath(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<FileInfo> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_file_information_by_path_arg0.format(arg0 = (path)))
            val response = httpClient.post("/api/files/get-file-info-by-path") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(GetFileByPathRequest(path)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_failed_to_retrieve_file_information_by_path_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val info = decodeHttpSuccessBody<FileInfo>(bytes)
            LogKit.d(AppStrings.ui_successfully_retrieved_file_information_by_path_arg0.format(arg0 = (info.name)))
            Result.success(info)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_path_file_information_retrieval_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun getFileInfoByPathAndName(path: String, name: String): Result<FileInfo> {
        return getFileInfoByPathAndName(path = path, name = name, requestId = null, batchId = null)
    }

    suspend fun getFileInfoByPathAndName(
        path: String,
        name: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<FileInfo> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_file_information_by_path_and_name_arg0_arg1.format(arg0 = (path), arg1 = (name)))
            val response = httpClient.post("/api/files/get-file-info-by-path-and-name") {
                setFolderSpanRequestBody(
                    ProtoBufCodec.encode(GetFileByPathAndNameRequest(path, name)),
                    manager.encryptedHttpTransport
                )
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_failed_to_retrieve_file_information_by_path_and_name_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val info = decodeHttpSuccessBody<FileInfo>(bytes)
            LogKit.d(AppStrings.ui_file_information_retrieved_successfully_by_path_name_arg0.format(arg0 = (info.name)))
            Result.success(info)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_file_information_is_retrieved_by_path_name_but_an_error_occurred_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> {
        return createFiles(paths = paths, requestId = null, batchId = null)
    }

    suspend fun createFiles(
        paths: List<String>,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<Result<Boolean>>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_create_file_client_count_arg0.format(arg0 = (paths.size).toString()))
            val response = httpClient.post("/api/files/create-file") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(CreateFileRequest(paths)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_create_file_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val list = decodeHttpSuccessBody<List<SerializableResult>>(bytes)
                .map { result -> result.toResult<Boolean>() }
            LogKit.d(AppStrings.ui_create_file_response_count_arg0.format(arg0 = (list.size).toString()))
            Result.success(list)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_creating_file_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun readFileLines(path: String): Result<List<String>> {
        return readFileLines(path = path, requestId = null, batchId = null)
    }

    suspend fun readFileLines(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<String>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_read_file_line_client_path_arg0.format(arg0 = (path)))
            val request = ReadFileLinesRequest(path)
            val response = httpClient.post("/api/files/read-lines") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_read_file_line_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val lines = decodeHttpSuccessBody<List<String>>(bytes)
            LogKit.d(AppStrings.ui_read_file_line_successfully_path_arg0_lines_arg1.format(arg0 = (path), arg1 = (lines.size).toString()))
            Result.success(lines)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_file_line_read_exception_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun appendToFile(path: String, content: String): Result<Boolean> {
        return appendToFile(path = path, content = content, requestId = null, batchId = null)
    }

    suspend fun appendToFile(
        path: String,
        content: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_add_file_content_client_path_arg0_contentlength_arg1.format(arg0 = (path), arg1 = (content.length).toString()))
            val request = AppendToFileRequest(path, content)
            val response = httpClient.post("/api/files/append-content") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                updateTransferStatus(response)
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_add_file_content_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            updateTransferStatus(response)
            val bytes = response.folderSpanBodyBytes()
            val success = decodeHttpSuccessBody<Boolean>(bytes)
            LogKit.d(AppStrings.ui_added_file_content_successfully_path_arg0.format(arg0 = (path)))
            Result.success(success)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_append_file_content_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }
}
