package com.folderspan.service.http.archive

import com.folderspan.service.file.DeviceFileClient
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.supervisorScope

internal const val SMALL_FILE_ARCHIVE_RELAY_CAPACITY = 2

internal enum class SmallFileArchiveTransportPreference {
    LAN,
    WAN,
}

internal class SmallFileArchiveTransferCoordinator(
    private val sourceClient: DeviceFileClient?,
    private val targetClient: DeviceFileClient?,
    private val transportPreference: SmallFileArchiveTransportPreference,
) {
    suspend fun negotiate(): FolderSpanArchiveStreamOptions {
        val sourceCapabilities = (
            sourceClient?.archiveCapabilities() ?: FolderSpanArchiveStreamCapabilities.local()
        ).normalized()
        val targetCapabilities = (
            targetClient?.archiveCapabilities() ?: FolderSpanArchiveStreamCapabilities.local()
        ).normalized()
        val commonCodecs = sourceCapabilities.codecs.intersect(targetCapabilities.codecs.toSet())
        val codec = when {
            transportPreference == SmallFileArchiveTransportPreference.WAN &&
                FolderSpanArchiveCompressionCodec.ZSTD in commonCodecs -> FolderSpanArchiveCompressionCodec.ZSTD
            FolderSpanArchiveCompressionCodec.NONE in commonCodecs -> FolderSpanArchiveCompressionCodec.NONE
            else -> throw FolderSpanArchiveUnsupportedException("archive codecs do not intersect")
        }
        return FolderSpanArchiveStreamOptions(codec).validated()
    }

    suspend fun <T> select(
        items: List<T>,
        sourcePath: (T) -> String,
        relativePath: (T) -> String,
        size: (T) -> Long,
        hidden: (T) -> Boolean = { false },
        mimeType: (T) -> String = { "" },
        modifiedTimeMillis: (T) -> Long = { 0L },
    ): SmallFileArchiveSelection<T>? {
        val options = negotiate()
        val sourceCapabilities = sourceClient?.archiveCapabilities() ?: FolderSpanArchiveStreamCapabilities.local()
        val targetCapabilities = targetClient?.archiveCapabilities() ?: FolderSpanArchiveStreamCapabilities.local()
        if (!sourceCapabilities.supports(options) || !targetCapabilities.supports(options)) return null
        val maxEntries = minOf(sourceCapabilities.maxEntries, targetCapabilities.maxEntries)
        val maxFileBytes = minOf(sourceCapabilities.maxFileBytes, targetCapabilities.maxFileBytes)
        val maxBatchBytes = minOf(sourceCapabilities.maxBatchBytes, targetCapabilities.maxBatchBytes)
        if (maxEntries < 2 || maxFileBytes < 0L || maxBatchBytes <= 0L) return null
        return selectSmallFileArchiveBatches(
            items = items,
            sourcePath = sourcePath,
            relativePath = relativePath,
            size = size,
            hidden = hidden,
            mimeType = mimeType,
            modifiedTimeMillis = modifiedTimeMillis,
            maxBatchEntries = minOf(FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES, maxEntries),
            maxFileBytes = maxFileBytes,
            maxBatchPayloadBytes = minOf(FOLDER_SPAN_ARCHIVE_TARGET_BATCH_PAYLOAD_BYTES, maxBatchBytes),
        )
    }

    suspend fun transferBatch(
        entries: List<FolderSpanArchiveEntryRequest>,
        destinationRootPath: String,
        onFileBytes: suspend (relativePath: String, bytes: Int) -> Unit = { _, _ -> },
    ): Result<FolderSpanArchiveTransferResult> {
        val options = try {
            negotiate()
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return when {
            sourceClient != null && targetClient == null ->
                transferDeviceToLocal(entries, destinationRootPath, options, onFileBytes)
            sourceClient == null && targetClient != null ->
                transferLocalToDevice(entries, destinationRootPath, options, onFileBytes)
            sourceClient != null && targetClient != null ->
                transferDeviceToDevice(entries, destinationRootPath, options, onFileBytes)
            else -> Result.failure(IllegalArgumentException("archive transfer needs at least one device endpoint"))
        }
    }

    private suspend fun transferDeviceToLocal(
        entries: List<FolderSpanArchiveEntryRequest>,
        destinationRootPath: String,
        options: FolderSpanArchiveStreamOptions,
        onFileBytes: suspend (String, Int) -> Unit,
    ): Result<FolderSpanArchiveTransferResult> = supervisorScope {
        val chunks = Channel<ByteArray>(capacity = SMALL_FILE_ARCHIVE_RELAY_CAPACITY)
        val producer = async {
            var failure: Throwable? = null
            try {
                sourceClient!!.readArchiveStream(
                    FolderSpanArchiveReadRequest(entries, options),
                ) { chunk -> chunks.send(chunk) }.getOrThrow()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                chunks.close(failure)
            }
        }
        try {
            val result = ArchiveStreamDecoder.decode(
                chunks = chunks.receiveAsFlow(),
                sink = ArchiveEntryExtractor(this, destinationRootPath),
                onFileBytes = onFileBytes,
            )
            producer.await()
            Result.success(result)
        } catch (error: CancellationException) {
            producer.cancel()
            throw error
        } catch (error: Throwable) {
            producer.cancel()
            Result.failure(error)
        }
    }

    private suspend fun transferLocalToDevice(
        entries: List<FolderSpanArchiveEntryRequest>,
        destinationRootPath: String,
        options: FolderSpanArchiveStreamOptions,
        onFileBytes: suspend (String, Int) -> Unit,
    ): Result<FolderSpanArchiveTransferResult> {
        val stream = ArchiveStreamEncoder.encode(entries, options) { entry ->
            localFileChunks(entry, onFileBytes)
        }
        return targetClient!!.writeArchiveStream(
            request = FolderSpanArchiveWriteRequest(
                destinationRootPath = destinationRootPath,
                expectedEntries = entries.size,
                expectedFileBytes = entries.filterNot { it.directory }.sumOf { it.size },
            ),
            chunks = stream,
        )
    }

    private suspend fun transferDeviceToDevice(
        entries: List<FolderSpanArchiveEntryRequest>,
        destinationRootPath: String,
        options: FolderSpanArchiveStreamOptions,
        onFileBytes: suspend (String, Int) -> Unit,
    ): Result<FolderSpanArchiveTransferResult> = supervisorScope {
        val relay = Channel<ByteArray>(capacity = SMALL_FILE_ARCHIVE_RELAY_CAPACITY)
        val sizes = entries.associate { entry -> entry.relativePath to entry.size }
        suspend fun reportCommitted(result: FolderSpanArchiveTransferResult) {
            result.completedRelativePaths.forEach { path ->
                var remaining = sizes[path] ?: 0L
                while (remaining > 0L) {
                    val bytes = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                    onFileBytes(path, bytes)
                    remaining -= bytes
                }
            }
        }
        val source = async {
            var failure: Throwable? = null
            try {
                sourceClient!!.readArchiveStream(FolderSpanArchiveReadRequest(entries, options)) { chunk ->
                    relay.send(chunk)
                }.getOrThrow()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                relay.close(failure)
            }
        }
        val target = async {
            targetClient!!.writeArchiveStream(
                request = FolderSpanArchiveWriteRequest(
                    destinationRootPath = destinationRootPath,
                    expectedEntries = entries.size,
                    expectedFileBytes = entries.filterNot { it.directory }.sumOf { it.size },
                ),
                chunks = validateArchiveRelayPrelude(relay.receiveAsFlow(), options),
            ).getOrThrow()
        }
        try {
            val result = target.await()
            source.await()
            reportCommitted(result)
            Result.success(result)
        } catch (error: CancellationException) {
            source.cancel()
            target.cancel()
            throw error
        } catch (error: Throwable) {
            source.cancel()
            target.cancel()
            reportCommitted(error.completedArchiveTransferResult())
            Result.failure(error)
        }
    }
}

internal fun Throwable.completedArchiveTransferResult(): FolderSpanArchiveTransferResult = when (this) {
    is FolderSpanArchiveStreamException -> partialResult
    is FolderSpanArchiveRemoteWriteException -> partialResult
    else -> cause?.completedArchiveTransferResult() ?: FolderSpanArchiveTransferResult()
}

private fun localFileChunks(
    entry: FolderSpanArchiveEntryRequest,
    onFileBytes: suspend (String, Int) -> Unit,
): Flow<ByteArray> = flow {
    if (entry.directory) return@flow
    FileUtils.readFileChunks(
        permission = FileAccessPermission.Allowed,
        path = entry.sourcePath,
        chunkSize = FOLDER_SPAN_ARCHIVE_BUFFER_BYTES.toLong(),
    ).collect { result ->
        val chunk = result.getOrThrow().second
        if (chunk.isNotEmpty()) {
            emit(chunk)
            onFileBytes(entry.relativePath, chunk.size)
        }
    }
}

private fun validateArchiveRelayPrelude(
    chunks: Flow<ByteArray>,
    expectedOptions: FolderSpanArchiveStreamOptions,
): Flow<ByteArray> = flow {
    var buffered = ByteArray(0)
    var validated = false
    chunks.collect { chunk ->
        if (validated) {
            emit(chunk)
            return@collect
        }
        require(buffered.size + chunk.size <= FOLDER_SPAN_ARCHIVE_BUFFER_BYTES * 2) {
            "archive prelude exceeds relay validation buffer"
        }
        buffered += chunk
        if (buffered.size < FolderSpanArchiveCodec.MAGIC.size) return@collect
        require(buffered.copyOf(FolderSpanArchiveCodec.MAGIC.size).contentEquals(FolderSpanArchiveCodec.MAGIC)) {
            "archive relay magic is invalid"
        }
        if (buffered.size < FolderSpanArchiveCodec.MAGIC.size + 4) return@collect
        val headerSize = FolderSpanArchiveCodec.readHeaderLength(buffered, FolderSpanArchiveCodec.MAGIC.size)
        require(headerSize in 1..FOLDER_SPAN_ARCHIVE_BUFFER_BYTES) { "archive relay header is invalid" }
        val preludeSize = FolderSpanArchiveCodec.MAGIC.size + 4 + headerSize
        if (buffered.size < preludeSize) return@collect
        val header = FolderSpanArchiveCodec.decodeStreamHeader(
            buffered.copyOfRange(FolderSpanArchiveCodec.MAGIC.size + 4, preludeSize)
        )
        require(header.codec == expectedOptions.codec) { "archive relay codec changed" }
        validated = true
        emit(buffered)
        buffered = ByteArray(0)
    }
    require(validated) { "archive relay ended before its prelude" }
}
