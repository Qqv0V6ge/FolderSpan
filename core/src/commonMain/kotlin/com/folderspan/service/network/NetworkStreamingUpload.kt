package com.folderspan.service.network

import strings.AppStrings

internal suspend fun pumpUploadChunks(
    size: Long,
    onProgress: (Long, Long) -> Unit,
    readChunk: suspend () -> ByteArray?,
    writeChunk: suspend (ByteArray) -> Unit,
): Result<Boolean> {
    var doneBytes = 0L
    while (true) {
        val chunk = readChunk() ?: break
        if (chunk.isEmpty()) continue
        writeChunk(chunk)
        doneBytes += chunk.size
        val total = if (size >= 0L) size else doneBytes
        onProgress(doneBytes, total)
    }
    val total = if (size >= 0L) size else doneBytes
    onProgress(doneBytes.coerceAtLeast(0L), total)
    return Result.success(true)
}

internal fun requireKnownUploadSize(size: Long): Result<Boolean>? {
    if (size < 0L) {
        return Result.failure(IllegalArgumentException(AppStrings.network_stream_upload_size_required))
    }
    return null
}
