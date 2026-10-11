package com.folderspan.routes

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.service.data.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_STREAM_BUFFER_BYTES
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_STREAM_RANGE_BYTES
import com.folderspan.service.operation.HttpTransferStatusProvider
import com.folderspan.utils.FileUtils
import com.folderspan.utils.ProtoBufCodec
import kotlin.io.encoding.Base64
import strings.AppStrings

internal suspend fun RawHttpApiDispatcher.handleFile(request: RawHttpRequest): RawHttpResponse {
    val isRawStreamBody = request.path == "/api/files/write-bytes" ||
        request.path == "/api/files/stream-upload"
    requireProtobuf(request, allowOctetStream = isRawStreamBody)?.let { return it }
    requireAuth(request)?.let { return it }
    val token = request.authToken().orEmpty()
    return when (request.path) {
        "/api/files/rename" -> fileBatch(fileService.renames(token, request.decodeProtobuf()))
        "/api/files/create-folder" -> withTransferStatus {
            fileBatch(
                fileService.createFolders(token, request.decodeProtobuf()),
                extraHeaders = deviceTransferStatusHeaders(),
            )
        }
        "/api/files/create-file" -> fileBatch(fileService.createFiles(token, request.decodeProtobuf()))
        "/api/files/delete" -> fileBatch(fileService.deletes(token, request.decodeProtobuf()))
        "/api/files/copy" -> handleCopyPath(request, token)
        "/api/files/copy-control" -> {
            val result = fileCopyService.controlCopy(token, request.decodeProtobuf<CopyPathControlRequest>())
            if (result.isSuccess) protobuf(result.toSerializableResult()) else fileFailure(result)
        }

        "/api/files/write-bytes" -> handleWriteBytes(request, token)
        "/api/files/read-bytes" -> handleReadBytes(request, token)
        "/api/files/stream-file" -> handleDeviceStreamFile(request, token)
        "/api/files/stream-upload" -> handleDeviceStreamUpload(request, token)
        "/api/files/get-file-by-path" -> fileSimple(
            fileService.getFileByPath(token, request.decodeProtobuf()),
            AppStrings.ui_file_does_not_exist
        )
        "/api/files/get-file-info-by-path" -> fileInfo(
            fileService.getFileInfoByPath(token, request.decodeProtobuf()),
            AppStrings.ui_file_does_not_exist
        )
        "/api/files/get-file-by-path-and-name" -> fileSimple(
            fileService.getFileByPathAndName(token, request.decodeProtobuf()),
            AppStrings.ui_file_does_not_exist
        )
        "/api/files/get-file-info-by-path-and-name" -> fileInfo(
            fileService.getFileInfoByPathAndName(token, request.decodeProtobuf()),
            AppStrings.ui_file_does_not_exist
        )
        "/api/files/read-lines" -> {
            val result = fileService.readFileLines(token, request.decodeProtobuf<ReadFileLinesRequest>())
            if (result.isSuccess) protobuf(result.toSerializableResult()) else fileFailure(result)
        }

        "/api/files/append-content" -> withTransferStatus {
            val result = fileService.appendToFile(token, request.decodeProtobuf<AppendToFileRequest>())
            if (result.isSuccess) {
                protobuf(result.toSerializableResult(), extraHeaders = deviceTransferStatusHeaders())
            } else {
                fileFailure(result, extraHeaders = deviceTransferStatusHeaders())
            }
        }

        else -> protobufFailure(404, EmptyDataException())
    }
}

internal suspend fun RawHttpApiDispatcher.handleCopyPath(request: RawHttpRequest, token: String): RawHttpResponse {
    val body = request.decodeProtobuf<CopyPathRequest>()
    return withTransferStatus {
        RawHttpResponse.stream(
            statusCode = 200,
            headers = commonHeaders() + deviceTransferStatusHeaders() + ("Content-Type" to OCTET_STREAM_CONTENT_TYPE),
        ) {
            HttpTransferStatusProvider.trackRequest {
                suspend fun emit(progress: CopyPathProgress) {
                    val bytes = ProtoBufCodec.encode(progress)
                    write(bytes.size.toIntBytes())
                    write(bytes)
                    flush()
                }
                val result = fileCopyService.copyPath(token, body, ::emit)
                if (result.isFailure) {
                    throw result.exceptionOrNull() ?: Exception(AppStrings.ui_copy_failed)
                }
            }
        }
    }
}

internal suspend fun RawHttpApiDispatcher.handleWriteBytes(request: RawHttpRequest, token: String): RawHttpResponse {
    return handleWriteBytesBody(
        request = request,
        token = token,
        maxBlockLength = null,
    )
}

internal suspend fun RawHttpApiDispatcher.handleDeviceStreamUpload(request: RawHttpRequest, token: String): RawHttpResponse {
    return handleWriteBytesBody(
        request = request,
        token = token,
        maxBlockLength = DEVICE_DIRECT_STREAM_RANGE_BYTES.toLong(),
    )
}

private suspend fun RawHttpApiDispatcher.handleWriteBytesBody(
    request: RawHttpRequest,
    token: String,
    maxBlockLength: Long?,
): RawHttpResponse {
    return withTransferStatus {
        val metadata = request.writeBytesMetadataHeader()
            ?: return@withTransferStatus fileFailure(
                Result.failure<Boolean>(IllegalArgumentException(AppStrings.ui_missing_metadata_writing)),
                extraHeaders = deviceTransferStatusHeaders()
            )
        val streamRequest = runCatching {
            ProtoBufCodec.decode<WriteBytesStreamRequest>(Base64.decode(metadata))
        }.getOrElse { error ->
            return@withTransferStatus fileFailure(
                Result.failure<Boolean>(IllegalArgumentException(AppStrings.error_write_metadata_invalid, error)),
                extraHeaders = deviceTransferStatusHeaders()
            )
        }
        val preparedResult = if (maxBlockLength == null) {
            fileService.prepareWriteBytes(token, streamRequest)
        } else {
            fileService.prepareWriteBytes(
                authToken = token,
                request = streamRequest,
                maxBlockLength = maxBlockLength,
            )
        }
        val prepared = preparedResult.getOrElse { error ->
            return@withTransferStatus fileFailure(
                Result.failure<Boolean>(error),
                extraHeaders = deviceTransferStatusHeaders()
            )
        }
        val bodyReader = request.bodyReader
        val result = if (bodyReader != null) {
            if (bodyReader.contentLength != prepared.blockLength) {
                return@withTransferStatus fileFailure(
                    Result.failure<Boolean>(IllegalArgumentException(AppStrings.error_data_size_declared_block_length_mismatch)),
                    extraHeaders = deviceTransferStatusHeaders()
                )
            }
            FileUtils.writeByteStream(FileAccessPermission.Allowed,
                path = prepared.path,
                fileSize = prepared.fileSize,
                startOffset = prepared.blockOffset,
                expectedBytes = prepared.blockLength,
                bufferSize = rawRequestBodyBufferBytes(prepared.blockLength),
                readNext = { buffer, length ->
                    bodyReader.read(buffer, 0, length)
                },
                onBytesWritten = { _, _ -> },
            )
        } else {
            if (request.body.size.toLong() != prepared.blockLength) {
                return@withTransferStatus fileFailure(
                    Result.failure<Boolean>(IllegalArgumentException(AppStrings.error_data_size_declared_block_length_mismatch)),
                    extraHeaders = deviceTransferStatusHeaders()
                )
            }
            FileUtils.writeBytes(FileAccessPermission.Allowed,
                path = prepared.path,
                fileSize = prepared.fileSize,
                data = request.body,
                offset = prepared.blockOffset
            )
        }
        if (result.isSuccess) protobuf(result.toSerializableResult(), extraHeaders = deviceTransferStatusHeaders())
        else fileFailure(result, extraHeaders = deviceTransferStatusHeaders())
    }
}

internal suspend fun RawHttpApiDispatcher.handleReadBytes(request: RawHttpRequest, token: String): RawHttpResponse {
    return withTransferStatus {
        val body = request.decodeProtobuf<ReadBytesRequest>()
        val data = fileService.readBytes(token, body).getOrElse { error ->
            return@withTransferStatus fileFailure(
                Result.failure<ByteArray>(error),
                extraHeaders = deviceTransferStatusHeaders()
            )
        }
        val headers = commonHeaders() + deviceTransferStatusHeaders() + mapOf(
            "Content-Type" to OCTET_STREAM_CONTENT_TYPE,
            "Cache-Control" to "no-cache"
        )
        rawBytes(data, OCTET_STREAM_CONTENT_TYPE, headers)
    }
}

internal suspend fun RawHttpApiDispatcher.handleDeviceStreamFile(request: RawHttpRequest, token: String): RawHttpResponse {
    return withTransferStatus {
        val body = request.decodeProtobuf<DeviceStreamFileRequest>()
        val prepared = fileService.prepareStreamFile(
            authToken = token,
            request = body,
            maxRangeLength = DEVICE_DIRECT_STREAM_RANGE_BYTES.toLong(),
        ).getOrElse { error ->
            return@withTransferStatus fileFailure(
                Result.failure<ByteArray>(error),
                extraHeaders = deviceStreamTransferStatusHeaders()
            )
        }
        val headers = commonHeaders() + deviceStreamTransferStatusHeaders() + mapOf(
            "Content-Type" to OCTET_STREAM_CONTENT_TYPE,
            "Cache-Control" to "no-cache"
        )
        if (prepared.requestSize == 0L) {
            return@withTransferStatus rawBytes(byteArrayOf(), OCTET_STREAM_CONTENT_TYPE, headers)
        }
        RawHttpResponse.stream(200, headers, contentLength = prepared.requestSize) {
            var bytesWritten = 0L
            FileUtils.readFileRangeChunks(FileAccessPermission.Allowed,
                path = prepared.path,
                start = prepared.startOffset,
                end = prepared.endOffset,
                chunkSize = DEVICE_DIRECT_STREAM_BUFFER_BYTES.toLong(),
            ).collect { chunkResult ->
                val (offset, data) = chunkResult.getOrElse { error -> throw error }
                if (offset != prepared.startOffset + bytesWritten) throw Exception(AppStrings.ui_read_range_offset_discontinuous)
                write(data)
                bytesWritten += data.size
            }
            if (bytesWritten != prepared.requestSize) throw Exception(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
        }
    }
}

internal fun RawHttpApiDispatcher.fileBatch(
    result: Result<List<Result<Boolean>>>,
    extraHeaders: Map<String, String> = emptyMap(),
): RawHttpResponse {
    return if (result.isSuccess) {
        protobuf(
            Result.success(
                result.getOrDefault(emptyList()).map { item -> item.toSerializableResult() }
            ).toSerializableResult(),
            extraHeaders = extraHeaders,
        )
    } else {
        fileFailure(result, extraHeaders)
    }
}

internal fun fileSimple(result: Result<FileSimpleInfo>, notFoundMessage: String): RawHttpResponse {
    return if (result.isSuccess) protobuf(result.toSerializableResult()) else when (val error = result.exceptionOrNull()) {
        is AuthorityException -> protobufFailure(403, error)
        is IllegalArgumentException -> protobufFailure(400, error)
        else -> protobufFailure(404, error.toMissingFileException(notFoundMessage))
    }
}

internal fun fileInfo(result: Result<FileInfo>, notFoundMessage: String): RawHttpResponse {
    return if (result.isSuccess) protobuf(result.toSerializableResult()) else when (val error = result.exceptionOrNull()) {
        is AuthorityException -> protobufFailure(403, error)
        is IllegalArgumentException -> protobufFailure(400, error)
        else -> protobufFailure(404, error.toMissingFileException(notFoundMessage))
    }
}

internal fun fileFailure(
    result: Result<*>,
    extraHeaders: Map<String, String> = emptyMap(),
): RawHttpResponse {
    return when (val error = result.exceptionOrNull()) {
        is AuthorityException -> protobufFailure(403, error, extraHeaders)
        is IllegalArgumentException -> protobufFailure(400, error, extraHeaders)
        is EmptyDataException -> protobufFailure(404, error, extraHeaders)
        else -> {
            val missingFileError = error.toMissingFileExceptionOrNull()
            if (missingFileError != null) {
                protobufFailure(404, missingFileError, extraHeaders)
            } else {
                protobufFailure(500, error ?: Exception(AppStrings.ui_webrtc_file_operation_failed), extraHeaders)
            }
        }
    }
}

private fun Throwable?.toMissingFileException(fallbackMessage: String): EmptyDataException {
    return this.toMissingFileExceptionOrNull()
        ?: EmptyDataException(this?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage)
}

private fun Throwable?.toMissingFileExceptionOrNull(): EmptyDataException? {
    var current = this
    while (current != null) {
        if (current is EmptyDataException) return current
        val message = current.message.orEmpty()
        if (message.isMissingFileMessage()) {
            return EmptyDataException(message)
        }
        current = current.cause
    }
    return null
}

private fun String.isMissingFileMessage(): Boolean {
    if (isBlank()) return false
    val text = lowercase()
    return text.contains(AppStrings.ui_file_does_not_exist.lowercase()) ||
        text.contains(AppStrings.ui_not_found_file.lowercase()) ||
        text.contains(AppStrings.ui_path_does_not_exist.lowercase()) ||
        text.contains(AppStrings.ui_file_does_not_exist) ||
        text.contains(AppStrings.ui_not_found_file) ||
        text.contains(AppStrings.ui_path_does_not_exist) ||
        text.contains("no such file") ||
        text.contains("not found") ||
        text.contains("enoent")
}
