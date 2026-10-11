package com.folderspan.service.http.archive

import com.squareup.zstd.ZSTD_e_continue
import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val ZSTD_COMPRESSION_LEVEL_PARAMETER = 100
private const val ZSTD_FAST_COMPRESSION_LEVEL = 1
private const val ZSTD_OUTPUT_BUFFER_BYTES = 64 * 1024

internal actual object FolderSpanArchivePlatformCompression {
    actual val supportedCodecs: Set<Int> = setOf(
        FolderSpanArchiveCompressionCodec.NONE,
        FolderSpanArchiveCompressionCodec.ZSTD,
    )

    actual fun compress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> = when (codec) {
        FolderSpanArchiveCompressionCodec.NONE -> chunks
        FolderSpanArchiveCompressionCodec.ZSTD -> compressZstd(chunks)
        else -> error("unsupported archive compression codec: $codec")
    }

    actual fun decompress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> = when (codec) {
        FolderSpanArchiveCompressionCodec.NONE -> chunks
        FolderSpanArchiveCompressionCodec.ZSTD -> decompressZstd(chunks)
        else -> error("unsupported archive compression codec: $codec")
    }
}

private fun compressZstd(chunks: Flow<ByteArray>): Flow<ByteArray> = flow {
    val compressor = zstdCompressor()
    try {
        compressor.setParameter(ZSTD_COMPRESSION_LEVEL_PARAMETER, ZSTD_FAST_COMPRESSION_LEVEL)
            .throwIfZstdError("configure")
        chunks.collect { input ->
            var inputOffset = 0
            while (inputOffset < input.size) {
                val output = ByteArray(ZSTD_OUTPUT_BUFFER_BYTES)
                compressor.compressStream2(
                    outputByteArray = output,
                    outputEnd = output.size,
                    outputStart = 0,
                    inputByteArray = input,
                    inputEnd = input.size,
                    inputStart = inputOffset,
                    mode = ZSTD_e_continue,
                ).throwIfZstdError("compress")
                val consumed = compressor.inputBytesProcessed
                val produced = compressor.outputBytesProcessed
                require(consumed > 0 || produced > 0) { "zstd compressor made no progress" }
                inputOffset += consumed
                if (produced > 0) emit(output.copyOf(produced))
            }
        }

        var remaining: Long
        do {
            val output = ByteArray(ZSTD_OUTPUT_BUFFER_BYTES)
            remaining = compressor.compressStream2(
                outputByteArray = output,
                outputEnd = output.size,
                outputStart = 0,
                inputByteArray = ByteArray(0),
                inputEnd = 0,
                inputStart = 0,
                mode = ZSTD_e_end,
            ).also { result -> result.throwIfZstdError("finish") }
            val produced = compressor.outputBytesProcessed
            if (produced > 0) emit(output.copyOf(produced))
            require(remaining == 0L || produced > 0) { "zstd compressor made no finish progress" }
        } while (remaining != 0L)
    } finally {
        compressor.close()
    }
}

private fun decompressZstd(chunks: Flow<ByteArray>): Flow<ByteArray> = flow {
    val decompressor = zstdDecompressor()
    var frameComplete = false
    var lastResult = 0L
    try {
        chunks.collect { input ->
            require(!frameComplete || input.isEmpty()) { "trailing compressed archive data" }
            var inputOffset = 0
            while (inputOffset < input.size) {
                val output = ByteArray(ZSTD_OUTPUT_BUFFER_BYTES)
                lastResult = decompressor.decompressStream(
                    outputByteArray = output,
                    outputEnd = output.size,
                    outputStart = 0,
                    inputByteArray = input,
                    inputEnd = input.size,
                    inputStart = inputOffset,
                ).also { result -> result.throwIfZstdError("decompress") }
                val consumed = decompressor.inputBytesProcessed
                val produced = decompressor.outputBytesProcessed
                require(consumed > 0 || produced > 0) { "zstd decompressor made no progress" }
                inputOffset += consumed
                if (produced > 0) emit(output.copyOf(produced))
                if (lastResult == 0L) {
                    frameComplete = true
                    require(inputOffset == input.size) { "trailing compressed archive data" }
                }
            }
        }
        require(frameComplete && lastResult == 0L) { "compressed archive ended before the zstd frame completed" }
    } finally {
        decompressor.close()
    }
}

private fun Long.throwIfZstdError(operation: String) {
    getErrorName(this)?.let { errorName -> error("zstd $operation failed: $errorName") }
}
