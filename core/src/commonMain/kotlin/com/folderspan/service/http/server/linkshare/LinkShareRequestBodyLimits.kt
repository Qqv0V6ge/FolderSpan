package com.folderspan.service.http.server.linkshare

internal object LinkShareRequestBodyLimits {
    const val MAX_BUFFERED_REQUEST_BODY_BYTES = 8L * 1024L * 1024L
    const val MAX_STREAMING_REQUEST_BODY_BYTES = 128L * 1024L * 1024L
    const val STREAMING_UPLOAD_PATH = "/api/share/upload"
    const val READ_EXACT_CHUNK_BYTES = 64 * 1024

    fun isStreamingUploadPath(target: String): Boolean {
        return target.substringBefore("?") == STREAMING_UPLOAD_PATH
    }

    fun allowsNonEmptyBody(method: String): Boolean {
        return when (method.uppercase()) {
            "GET", "HEAD", "OPTIONS" -> false
            else -> true
        }
    }

    fun maxBodyBytes(target: String, bufferedOverride: Long): Long {
        val buffered = minOf(bufferedOverride, MAX_BUFFERED_REQUEST_BODY_BYTES).coerceAtLeast(0L)
        return if (isStreamingUploadPath(target) && bufferedOverride >= MAX_BUFFERED_REQUEST_BODY_BYTES) {
            MAX_STREAMING_REQUEST_BODY_BYTES
        } else {
            buffered
        }
    }
}
