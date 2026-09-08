package com.folderspan.service.http.archive

import kotlinx.coroutines.flow.Flow

internal actual object FolderSpanArchivePlatformCompression {
    actual val supportedCodecs: Set<Int> = setOf(FolderSpanArchiveCompressionCodec.NONE)

    actual fun compress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> {
        require(codec == FolderSpanArchiveCompressionCodec.NONE) { "zstd is unavailable on Wasm" }
        return chunks
    }

    actual fun decompress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> {
        require(codec == FolderSpanArchiveCompressionCodec.NONE) { "zstd is unavailable on Wasm" }
        return chunks
    }
}
