package com.folderspan.service.mcp.file

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable

@Serializable
enum class FileContentEncoding {
    Utf8,
    Base64,
}

@Serializable
data class FileReadResult(
    val totalSize: Long,
    val offset: Long,
    val returnedLength: Int,
    val hasMore: Boolean,
    val encoding: FileContentEncoding,
    val data: String,
)

class FileContentReader(
    private val resolver: FileEndpointResolver,
) {
    suspend fun read(
        locator: FileLocator,
        offset: Long = 0L,
        length: Int = DEFAULT_FILE_RANGE_BYTES,
        encoding: FileContentEncoding = FileContentEncoding.Utf8,
    ): Result<FileReadResult> = runCatching {
        requireRange(offset, length)
        val (gateway, path) = resolver.resolve(locator)
        if (!gateway.permissions.read) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint does not allow reading")
        }
        val range = gateway.readRange(path, offset, length).getOrThrow()
        val data = when (encoding) {
            FileContentEncoding.Utf8 -> runCatching {
                range.bytes.decodeToString(throwOnInvalidSequence = true)
            }.getOrElse { error ->
                throw FileEndpointException(
                    FileEndpointErrorCode.InvalidArgument,
                    "file range is not valid UTF-8; retry with base64",
                    error,
                )
            }
            FileContentEncoding.Base64 -> Base64.encode(range.bytes)
        }
        FileReadResult(
            totalSize = range.totalSize,
            offset = range.offset,
            returnedLength = range.bytes.size,
            hasMore = range.hasMore,
            encoding = encoding,
            data = data,
        )
    }
}
