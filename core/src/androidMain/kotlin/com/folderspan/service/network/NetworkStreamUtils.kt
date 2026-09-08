package com.folderspan.service.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import strings.AppStrings

private const val NETWORK_STREAM_BUFFER_SIZE = 64 * 1024

internal fun copyStreamWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    onProgress: (Long, Long) -> Unit
) {
    val buffer = ByteArray(NETWORK_STREAM_BUFFER_SIZE)
    var doneBytes = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        output.write(buffer, 0, read)
        doneBytes += read
        onProgress(doneBytes, totalBytes)
    }
    if (doneBytes > 0L) {
        onProgress(doneBytes, totalBytes)
    }
}

internal class PullChunkInputStream(
    private val readChunk: suspend () -> ByteArray?,
) : InputStream() {
    private var buffer: ByteArray = EmptyBytes
    private var position: Int = 0
    private var eof: Boolean = false

    override fun read(): Int {
        refill()
        if (eof) return -1
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        refill()
        if (eof) return -1
        val copy = minOf(len, buffer.size - position)
        buffer.copyInto(b, off, position, position + copy)
        position += copy
        return copy
    }

    private fun refill() {
        while (!eof && position >= buffer.size) {
            val next = runBlocking { readChunk() }
            if (next == null) {
                eof = true
                buffer = EmptyBytes
                position = 0
                return
            }
            buffer = next
            position = 0
            if (buffer.isEmpty()) continue
            return
        }
    }

    private companion object {
        val EmptyBytes = ByteArray(0)
    }
}

internal suspend fun relayStreamByChunks(
    input: InputStream,
    totalBytes: Long,
    onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
): Result<Boolean> {
    val buffer = ByteArray(NETWORK_STREAM_BUFFER_SIZE)
    while (true) {
        val read = withContext(Dispatchers.IO) {
            input.read(buffer)
        }
        if (read <= 0) break
        val chunkResult = onChunk(buffer.copyOf(read), totalBytes)
        if (chunkResult.isFailure) {
            return Result.failure(chunkResult.exceptionOrNull() ?: Exception(AppStrings.ui_flowing_transmission_failed))
        }
    }
    return Result.success(true)
}
