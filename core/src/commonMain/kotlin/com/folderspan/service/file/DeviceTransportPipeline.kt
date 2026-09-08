package com.folderspan.service.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

internal data class DeviceTransportPipelineConfig(
    val chunkSize: Int,
    val readParallelism: Int,
    val writeParallelism: Int,
    val queueDepth: Int,
) {
    constructor(
        chunkSize: Int,
        pipelineDepth: Int,
    ) : this(
        chunkSize = chunkSize,
        readParallelism = pipelineDepth,
        writeParallelism = pipelineDepth,
        queueDepth = pipelineDepth,
    )

    val pipelineDepth: Int
        get() = minOf(readParallelism, writeParallelism, queueDepth)

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(readParallelism > 0) { "readParallelism must be positive" }
        require(writeParallelism > 0) { "writeParallelism must be positive" }
        require(queueDepth > 0) { "queueDepth must be positive" }
    }
}

internal data class DeviceTransportChunk(
    val index: Int,
    val startOffset: Long,
    val endOffset: Long,
    val bytes: ByteArray,
) {
    val size: Int
        get() = bytes.size
}

internal data class DeviceTransportProgress(
    val transferredBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
)

internal fun calculateDeviceTransportChunkCount(
    totalBytes: Long,
    chunkSize: Int,
): Int {
    if (totalBytes <= 0L) return 1
    val safeChunkSize = chunkSize.coerceAtLeast(1).toLong()
    return ((totalBytes + safeChunkSize - 1L) / safeChunkSize).coerceAtLeast(1L).toInt()
}

internal fun calculateDeviceTransportRange(
    chunkIndex: Int,
    totalBytes: Long,
    chunkSize: Int,
): Pair<Long, Long> {
    require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
    require(totalBytes >= 0L) { "totalBytes must be non-negative" }
    require(chunkSize > 0) { "chunkSize must be positive" }

    val startOffset = chunkIndex.toLong() * chunkSize.toLong()
    val endOffset = minOf(startOffset + chunkSize.toLong(), totalBytes)
    require(startOffset <= totalBytes) { "chunk start exceeds total bytes" }
    return startOffset to endOffset
}

internal suspend fun runDeviceTransportPipeline(
    totalBytes: Long,
    config: DeviceTransportPipelineConfig,
    readChunk: suspend (index: Int, startOffset: Long, endOffset: Long) -> Result<ByteArray>,
    writeChunk: suspend (DeviceTransportChunk) -> Result<Boolean>,
    onChunkCommitted: suspend (DeviceTransportProgress) -> Unit = {},
): Result<Long> {
    if (totalBytes < 0L) {
        return Result.failure(IllegalArgumentException("totalBytes must be non-negative"))
    }
    if (totalBytes == 0L) return Result.success(0L)

    return runCatching {
        coroutineScope {
            val totalChunks = calculateDeviceTransportChunkCount(totalBytes, config.chunkSize)
            val readWorkerCount = minOf(config.readParallelism, totalChunks).coerceAtLeast(1)
            val writeWorkerCount = minOf(config.writeParallelism, totalChunks).coerceAtLeast(1)
            val queueCapacity = minOf(config.queueDepth, totalChunks).coerceAtLeast(1)
            val chunkIndexes = Channel<Int>(capacity = readWorkerCount)
            val chunkQueue = Channel<DeviceTransportChunk>(capacity = queueCapacity)
            val progressMutex = Mutex()
            var transferredBytes = 0L
            var completedChunks = 0

            val readers = List(readWorkerCount) {
                launch(Dispatchers.Default) {
                    for (chunkIndex in chunkIndexes) {
                        val (startOffset, endOffset) = calculateDeviceTransportRange(
                            chunkIndex = chunkIndex,
                            totalBytes = totalBytes,
                            chunkSize = config.chunkSize,
                        )
                        val expectedSize = (endOffset - startOffset).coerceAtLeast(0L)
                        val bytes = readChunk(chunkIndex, startOffset, endOffset).getOrElse { error ->
                            throw error
                        }
                        if (bytes.size.toLong() != expectedSize) {
                            throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
                        }
                        chunkQueue.send(
                            DeviceTransportChunk(
                                index = chunkIndex,
                                startOffset = startOffset,
                                endOffset = endOffset,
                                bytes = bytes,
                            )
                        )
                    }
                }
            }

            val readerCloser = launch(Dispatchers.Default) {
                readers.joinAll()
                chunkQueue.close()
            }

            val writers = List(writeWorkerCount) {
                launch(Dispatchers.Default) {
                    for (chunk in chunkQueue) {
                        val writeResult = writeChunk(chunk)
                        if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                            throw (writeResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_write_failed))
                        }
                        progressMutex.withLock {
                            transferredBytes += chunk.size.toLong()
                            completedChunks += 1
                            onChunkCommitted(
                                DeviceTransportProgress(
                                    transferredBytes = transferredBytes,
                                    completedChunks = completedChunks,
                                    totalChunks = totalChunks,
                                )
                            )
                        }
                    }
                }
            }

            try {
                repeat(totalChunks) { chunkIndex ->
                    chunkIndexes.send(chunkIndex)
                }
            } finally {
                chunkIndexes.close()
            }

            writers.joinAll()
            readerCloser.join()
            transferredBytes
        }
    }
}
