package com.folderspan.service.http.archive

import com.folderspan.service.webui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal actual object FolderSpanArchivePlatformCompression {
    actual val supportedCodecs: Set<Int> get() = checkNotNull(WebUiAttachment.hostInfo).compressionCodecs.toSet()
    actual fun compress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> = transform(codec, chunks, false)
    actual fun decompress(codec: Int, chunks: Flow<ByteArray>): Flow<ByteArray> = transform(codec, chunks, true)

    private fun transform(codec: Int, chunks: Flow<ByteArray>, decompress: Boolean): Flow<ByteArray> {
        require(codec in supportedCodecs) { "unsupported archive compression codec" }
        if (codec == FolderSpanArchiveCompressionCodec.NONE) return chunks
        return flow {
            val id = WebUiPlatformClient.post<WebUiCompressionOpen, WebUiHandle>("/compression", WebUiCompressionOpen(codec, decompress)).id
            try {
                coroutineScope {
                    val upload = launch {
                        chunks.collect { bytes ->
                            var offset = 0
                            while (offset < bytes.size) {
                                val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                                WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/compression?handle=$id", bytes.copyOfRange(offset, end), "application/octet-stream")
                                offset = end
                            }
                        }
                        WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/compression/finish?handle=$id")
                    }
                    while (true) {
                        val chunk = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/compression?handle=$id")
                        if (chunk.headers.entries.any { it.key.equals("X-FolderSpan-End", true) && it.value == "true" }) break
                        emit(chunk.body)
                    }
                    upload.join()
                }
            } finally { withContext(NonCancellable) { withTimeoutOrNull(5_000) { WebUiPlatformClient.release(id) } } }
        }
    }
}
