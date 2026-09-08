package com.folderspan.service.http.archive

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

object FolderSpanArchiveCompressionCodec {
    const val NONE = 0
    const val ZSTD = 1

    fun requireKnown(value: Int): Int {
        require(value == NONE || value == ZSTD) { "unsupported FSAR compression codec: $value" }
        return value
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveStreamCapabilities(
    @ProtoNumber(1) val codecs: List<Int>,
    @ProtoNumber(2) val maxEntries: Int,
    @ProtoNumber(3) val maxFileBytes: Long,
    @ProtoNumber(4) val maxBatchBytes: Long,
) {
    fun normalized(): FolderSpanArchiveStreamCapabilities {
        val knownCodecs = codecs.distinct().filter { value ->
            value == FolderSpanArchiveCompressionCodec.NONE || value == FolderSpanArchiveCompressionCodec.ZSTD
        }
        require(maxEntries >= 0) { "archive maxEntries must be non-negative" }
        require(maxFileBytes >= 0L) { "archive maxFileBytes must be non-negative" }
        require(maxBatchBytes >= 0L) { "archive maxBatchBytes must be non-negative" }
        return copy(codecs = knownCodecs)
    }

    fun supports(options: FolderSpanArchiveStreamOptions): Boolean =
        codecs.contains(options.codec)

    companion object {
        fun unsupported(): FolderSpanArchiveStreamCapabilities = FolderSpanArchiveStreamCapabilities(
            codecs = emptyList(),
            maxEntries = 0,
            maxFileBytes = 0L,
            maxBatchBytes = 0L,
        )

        fun local(): FolderSpanArchiveStreamCapabilities = FolderSpanArchiveStreamCapabilities(
            codecs = FolderSpanArchivePlatformCompression.supportedCodecs.toList().sorted(),
            maxEntries = FOLDER_SPAN_ARCHIVE_MAX_ENTRIES,
            maxFileBytes = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES,
            maxBatchBytes = FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES,
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveStreamOptions(
    @ProtoNumber(1) val codec: Int = FolderSpanArchiveCompressionCodec.NONE,
) {
    fun validated(): FolderSpanArchiveStreamOptions {
        FolderSpanArchiveCompressionCodec.requireKnown(codec)
        return this
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class FolderSpanArchiveStreamHeader(
    @ProtoNumber(1) val codec: Int = FolderSpanArchiveCompressionCodec.NONE,
    @ProtoNumber(2) val entryCount: Int,
    @ProtoNumber(3) val declaredFileBytes: Long,
    @ProtoNumber(4) val declaredFrameBytes: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveReadRequest(
    @ProtoNumber(1) val entries: List<FolderSpanArchiveEntryRequest>,
    @ProtoNumber(2) val options: FolderSpanArchiveStreamOptions,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveWriteRequest(
    @ProtoNumber(1) val destinationRootPath: String,
    @ProtoNumber(2) val expectedEntries: Int,
    @ProtoNumber(3) val expectedFileBytes: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveTransferResult(
    @ProtoNumber(1) val completedRelativePaths: List<String> = emptyList(),
    @ProtoNumber(2) val committedFileBytes: Long = 0L,
)

class FolderSpanArchiveUnsupportedException(
    message: String = "archive streaming is not supported by this device transport",
) : UnsupportedOperationException(message)

class FolderSpanArchiveRemoteWriteException(
    message: String,
    val partialResult: FolderSpanArchiveTransferResult,
) : Exception(message)
