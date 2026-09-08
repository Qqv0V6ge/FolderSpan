package com.folderspan.service.http.archive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import strings.AppStrings

internal data class FolderSpanArchivePlannedEntry(
    val request: FolderSpanArchiveEntryRequest,
    val header: FolderSpanArchiveEntryHeader,
    val encodedHeader: ByteArray,
)

internal data class FolderSpanArchiveFramePlan(
    val entries: List<FolderSpanArchivePlannedEntry>,
    val declaredFileBytes: Long,
    val declaredFrameBytes: Long,
)

internal class FolderSpanArchiveStreamException(
    message: String,
    val partialResult: FolderSpanArchiveTransferResult,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface FolderSpanArchiveEntrySink {
    suspend fun begin(header: FolderSpanArchiveEntryHeader)

    suspend fun write(header: FolderSpanArchiveEntryHeader, chunk: ByteArray)

    suspend fun complete(header: FolderSpanArchiveEntryHeader)

    suspend fun abort(header: FolderSpanArchiveEntryHeader, cause: Throwable) = Unit
}

internal object ArchiveFrameEncoder {
    fun plan(entries: List<FolderSpanArchiveEntryRequest>): FolderSpanArchiveFramePlan {
        FolderSpanArchiveCodec.validateEntryRequests(entries)
        var fileBytes = 0L
        var frameBytes = 4L
        val plannedEntries = entries.map { entry ->
            val header = FolderSpanArchiveEntryHeader(
                relativePath = FolderSpanArchiveCodec.normalizeRelativePath(entry.relativePath),
                kind = if (entry.directory) FolderSpanArchiveEntryKind.Directory else FolderSpanArchiveEntryKind.File,
                size = if (entry.directory) 0L else entry.size,
                modifiedTimeMillis = entry.modifiedTimeMillis,
                hidden = entry.hidden,
                mimeType = entry.mimeType,
            )
            val encodedHeader = FolderSpanArchiveCodec.encodeHeader(header)
            fileBytes = fileBytes.safeArchiveAdd(header.size)
            frameBytes = frameBytes
                .safeArchiveAdd(4L)
                .safeArchiveAdd(encodedHeader.size.toLong())
                .safeArchiveAdd(header.size)
            require(frameBytes <= FOLDER_SPAN_ARCHIVE_MAX_FRAME_BYTES) { AppStrings.ui_archive_total_exceeds_limit }
            FolderSpanArchivePlannedEntry(entry, header, encodedHeader)
        }
        return FolderSpanArchiveFramePlan(
            entries = plannedEntries,
            declaredFileBytes = fileBytes,
            declaredFrameBytes = frameBytes,
        )
    }

    fun encode(
        plan: FolderSpanArchiveFramePlan,
        readFileChunks: suspend (FolderSpanArchiveEntryRequest) -> Flow<ByteArray>,
    ): Flow<ByteArray> = flow {
        plan.entries.forEach { entry ->
            emit(FolderSpanArchiveCodec.headerLengthBytes(entry.encodedHeader.size))
            emit(entry.encodedHeader)
            if (entry.header.kind == FolderSpanArchiveEntryKind.File) {
                var emittedBytes = 0L
                readFileChunks(entry.request).collect { chunk ->
                    if (chunk.isEmpty()) return@collect
                    emittedBytes = emittedBytes.safeArchiveAdd(chunk.size.toLong())
                    require(emittedBytes <= entry.header.size) {
                        AppStrings.ui_archive_entry_length_does_not_match
                    }
                    emit(chunk)
                }
                require(emittedBytes == entry.header.size) {
                    AppStrings.ui_archive_entry_length_does_not_match
                }
            }
        }
        emit(FolderSpanArchiveCodec.headerLengthBytes(0))
    }
}

internal object ArchiveStreamEncoder {
    fun encode(
        entries: List<FolderSpanArchiveEntryRequest>,
        options: FolderSpanArchiveStreamOptions,
        readFileChunks: suspend (FolderSpanArchiveEntryRequest) -> Flow<ByteArray>,
    ): Flow<ByteArray> {
        val validatedOptions = options.validated()
        val plan = ArchiveFrameEncoder.plan(entries)
        val frames = ArchiveFrameEncoder.encode(plan, readFileChunks)
        return flow {
            require(
                FolderSpanArchivePlatformCompression.supportedCodecs.contains(validatedOptions.codec)
            ) { "archive codec is unavailable on this platform: ${validatedOptions.codec}" }
            emit(
                FolderSpanArchiveCodec.encodeStreamPrelude(
                    FolderSpanArchiveStreamHeader(
                        codec = validatedOptions.codec,
                        entryCount = plan.entries.size,
                        declaredFileBytes = plan.declaredFileBytes,
                        declaredFrameBytes = plan.declaredFrameBytes,
                    )
                )
            )
            FolderSpanArchivePlatformCompression.compress(validatedOptions.codec, frames)
                .collect { chunk -> emit(chunk) }
        }
    }
}

internal object ArchiveStreamDecoder {
    suspend fun decode(
        chunks: Flow<ByteArray>,
        sink: FolderSpanArchiveEntrySink,
        onFileBytes: suspend (relativePath: String, bytes: Int) -> Unit = { _, _ -> },
    ): FolderSpanArchiveTransferResult = coroutineScope {
        val reader = ArchiveChunkReader(chunks.produceIn(this))
        try {
            val magic = reader.readExact(FolderSpanArchiveCodec.MAGIC.size)
            require(magic.contentEquals(FolderSpanArchiveCodec.MAGIC)) {
                AppStrings.ui_archive_flow_number_invalid
            }
            val headerSize = FolderSpanArchiveCodec.readHeaderLength(reader.readExact(4))
            require(headerSize in 1..FOLDER_SPAN_ARCHIVE_BUFFER_BYTES) {
                AppStrings.ui_archive_header_length_invalid
            }
            val streamHeader = FolderSpanArchiveCodec.decodeStreamHeader(reader.readExact(headerSize))
            val decompressed = FolderSpanArchivePlatformCompression.decompress(
                streamHeader.codec,
                reader.remainingFlow(),
            )
            coroutineScope {
                val frameReader = ArchiveChunkReader(decompressed.produceIn(this))
                try {
                    decodeFrames(frameReader, sink, streamHeader, onFileBytes)
                } finally {
                    frameReader.cancel()
                }
            }
        } finally {
            reader.cancel()
        }
    }

    private suspend fun decodeFrames(
        reader: ArchiveChunkReader,
        sink: FolderSpanArchiveEntrySink,
        expected: FolderSpanArchiveStreamHeader,
        onFileBytes: suspend (relativePath: String, bytes: Int) -> Unit,
    ): FolderSpanArchiveTransferResult {
        val completed = linkedSetOf<String>()
        val entryPaths = mutableSetOf<String>()
        var committedFileBytes = 0L
        var decodedFileBytes = 0L
        var frameBytes = 0L
        var entryCount = 0
        try {
            while (true) {
                val headerLengthBytes = reader.readExact(4)
                frameBytes = frameBytes.safeArchiveAdd(4L)
                val headerSize = FolderSpanArchiveCodec.readHeaderLength(headerLengthBytes)
                if (headerSize == 0) {
                    require(!reader.hasRemaining()) { AppStrings.ui_end_of_archive_marking_contains_extra_data }
                    require(entryCount == expected.entryCount) { "archive entry count does not match prelude" }
                    require(decodedFileBytes == expected.declaredFileBytes) {
                        "archive file bytes do not match prelude"
                    }
                    require(frameBytes == expected.declaredFrameBytes) {
                        "archive frame bytes do not match prelude"
                    }
                    return FolderSpanArchiveTransferResult(completed.toList(), committedFileBytes)
                }

                require(headerSize in 1..FOLDER_SPAN_ARCHIVE_BUFFER_BYTES) {
                    AppStrings.ui_archive_header_length_invalid
                }
                frameBytes = frameBytes.safeArchiveAdd(headerSize.toLong())
                require(frameBytes <= FOLDER_SPAN_ARCHIVE_MAX_FRAME_BYTES) { AppStrings.ui_archive_total_exceeds_limit }
                entryCount++
                require(entryCount <= FOLDER_SPAN_ARCHIVE_MAX_ENTRIES) {
                    AppStrings.ui_number_of_entries_exceeds_the_limit
                }

                val header = FolderSpanArchiveCodec.decodeHeader(reader.readExact(headerSize))
                val relativePath = FolderSpanArchiveCodec.normalizeRelativePath(header.relativePath)
                require(entryPaths.add(relativePath)) {
                    AppStrings.ui_archive_file_path_duplication_arg0.format(arg0 = relativePath)
                }
                if (header.kind == FolderSpanArchiveEntryKind.File) {
                    require(header.size <= FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES) {
                        AppStrings.ui_archive_files_exceeding_the_threshold
                    }
                }
                decodedFileBytes = decodedFileBytes.safeArchiveAdd(header.size)
                frameBytes = frameBytes.safeArchiveAdd(header.size)
                require(decodedFileBytes <= FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES) {
                    AppStrings.ui_archive_total_exceeds_limit
                }
                require(frameBytes <= FOLDER_SPAN_ARCHIVE_MAX_FRAME_BYTES) { AppStrings.ui_archive_total_exceeds_limit }

                var entryBegan = false
                try {
                    sink.begin(header)
                    entryBegan = true
                    var remaining = header.size
                    while (remaining > 0L) {
                        val nextSize = minOf(remaining, FOLDER_SPAN_ARCHIVE_BUFFER_BYTES.toLong()).toInt()
                        val chunk = reader.readAtMost(nextSize)
                            ?: throw IllegalArgumentException(
                                AppStrings.ui_archive_entry_length_does_not_match_or_ends_prematurely
                            )
                        require(chunk.isNotEmpty()) {
                            AppStrings.ui_archive_entry_length_does_not_match_or_ends_prematurely
                        }
                        sink.write(header, chunk)
                        remaining -= chunk.size.toLong()
                        onFileBytes(relativePath, chunk.size)
                    }
                    sink.complete(header)
                } catch (error: Throwable) {
                    if (entryBegan) sink.abort(header, error)
                    throw error
                }
                completed += relativePath
                committedFileBytes = committedFileBytes.safeArchiveAdd(header.size)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: FolderSpanArchiveStreamException) {
            throw error
        } catch (error: Throwable) {
            throw FolderSpanArchiveStreamException(
                message = error.message ?: AppStrings.ui_archive_transmission_failed,
                partialResult = FolderSpanArchiveTransferResult(completed.toList(), committedFileBytes),
                cause = error,
            )
        }
    }
}

private class ArchiveChunkReader(
    private val chunks: ReceiveChannel<ByteArray>,
) {
    private var current = ByteArray(0)
    private var offset = 0
    private var exhausted = false

    suspend fun readExact(length: Int): ByteArray {
        require(length >= 0) { AppStrings.ui_read_length_invalid }
        val result = ByteArray(length)
        var written = 0
        while (written < length) {
            val chunk = readAtMost(length - written)
                ?: throw IllegalArgumentException(AppStrings.ui_archive_the_flow_before_it_ends)
            chunk.copyInto(result, destinationOffset = written)
            written += chunk.size
        }
        return result
    }

    suspend fun readAtMost(length: Int): ByteArray? {
        require(length > 0) { AppStrings.ui_read_length_invalid }
        while (offset >= current.size) {
            if (exhausted) return null
            val result = chunks.receiveCatching()
            val next = result.getOrNull()
            if (next == null) {
                exhausted = true
                return null
            }
            if (next.isEmpty()) continue
            current = next
            offset = 0
        }
        val size = minOf(length, current.size - offset)
        return current.copyOfRange(offset, offset + size).also { offset += size }
    }

    suspend fun hasRemaining(): Boolean {
        if (offset < current.size) return true
        while (!exhausted) {
            val result = chunks.receiveCatching()
            val next = result.getOrNull()
            if (next == null) {
                exhausted = true
                return false
            }
            if (next.isNotEmpty()) {
                current = next
                offset = 0
                return true
            }
        }
        return false
    }

    fun remainingFlow(): Flow<ByteArray> = flow {
        if (offset < current.size) {
            emit(current.copyOfRange(offset, current.size))
            offset = current.size
        }
        for (chunk in chunks) {
            if (chunk.isNotEmpty()) emit(chunk)
        }
        exhausted = true
    }

    fun cancel() {
        chunks.cancel()
    }
}

private fun Long.safeArchiveAdd(value: Long): Long {
    require(value >= 0L && this <= Long.MAX_VALUE - value) { AppStrings.ui_archive_total_exceeds_limit }
    return this + value
}
