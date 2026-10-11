package com.folderspan.service.http.archive

import kotlinx.coroutines.flow.Flow

internal expect object FolderSpanArchivePlatformCompression {
    val supportedCodecs: Set<Int>

    fun compress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray>

    fun decompress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray>
}
