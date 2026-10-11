package com.folderspan.utils

import strings.AppStrings

internal suspend fun writeBufferedByteStream(
    startOffset: Long,
    expectedBytes: Long,
    bufferSize: Int,
    readNext: suspend (buffer: ByteArray, length: Int) -> Int,
    writeChunk: suspend (offset: Long, data: ByteArray) -> Unit,
    onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
): Result<Boolean> {
    return runCatching {
        require(startOffset >= 0L && expectedBytes >= 0L) { AppStrings.error_write_range_invalid }
        if (expectedBytes == 0L) return@runCatching true

        val safeBufferSize = bufferSize.coerceAtLeast(1)
        val readBuffer = ByteArray(safeBufferSize)
        val writeBuffer = ByteArray(safeBufferSize)
        var writeBufferSize = 0
        var writeBufferOffset = startOffset
        var currentOffset = startOffset
        var remaining = expectedBytes

        suspend fun flushWriteBuffer() {
            if (writeBufferSize <= 0) return
            val chunk = writeBuffer.copyOf(writeBufferSize)
            writeChunk(writeBufferOffset, chunk)
            onBytesWritten(writeBufferOffset, writeBufferSize)
            writeBufferOffset += writeBufferSize.toLong()
            writeBufferSize = 0
        }

        while (remaining > 0L) {
            val requested = minOf(readBuffer.size.toLong(), remaining).toInt()
            val read = readNext(readBuffer, requested)
            if (read < 0) {
                throw IllegalStateException(AppStrings.ui_file_stream_ends_before_the_file_is_read)
            }
            if (read == 0) continue
            if (read > requested) {
                throw IllegalStateException(AppStrings.ui_read_data_with_length_exceeding_the_request_length)
            }

            var copied = 0
            while (copied < read) {
                if (writeBufferSize == 0) {
                    writeBufferOffset = currentOffset
                }
                val copyBytes = minOf(read - copied, writeBuffer.size - writeBufferSize)
                readBuffer.copyInto(
                    destination = writeBuffer,
                    destinationOffset = writeBufferSize,
                    startIndex = copied,
                    endIndex = copied + copyBytes,
                )
                copied += copyBytes
                writeBufferSize += copyBytes
                currentOffset += copyBytes.toLong()
                remaining -= copyBytes.toLong()
                if (writeBufferSize == writeBuffer.size) {
                    flushWriteBuffer()
                }
            }
        }

        flushWriteBuffer()
        true
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) },
    )
}
