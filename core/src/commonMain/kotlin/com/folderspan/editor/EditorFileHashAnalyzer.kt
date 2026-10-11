package com.folderspan.editor

import korlibs.crypto.MD5
import korlibs.crypto.SHA1
import korlibs.crypto.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import strings.AppStrings

data class EditorFileHashes(
    val md5: String,
    val sha1: String,
    val sha256: String,
    val crc32: String,
    val sourceSnapshot: FileEditorSourceSnapshot,
)

/** Calculates all supported hashes in one bounded pass over the content source. */
class EditorFileHashAnalyzer(
    private val source: FileEditorContentSource,
    private val chunkSize: Int = DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE,
) {
    init {
        require(chunkSize > 0)
    }

    suspend fun analyze(
        onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<EditorFileHashes> = try {
        val initialSnapshot = source.currentSnapshot().getOrThrow()
        val md5 = MD5()
        val sha1 = SHA1()
        val sha256 = SHA256()
        var crc32 = 0xFFFFFFFFL
        var offset = 0L
        while (offset < initialSnapshot.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, initialSnapshot.size)
            val bytes = source.readRange(offset, end).getOrThrow()
            check(bytes.size.toLong() == end - offset) {
                AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range
            }
            md5.update(bytes)
            sha1.update(bytes)
            sha256.update(bytes)
            crc32 = updateCrc32(crc32, bytes)
            offset = end
            onProgress(offset, initialSnapshot.size)
        }
        if (initialSnapshot.size == 0L) onProgress(0L, 0L)
        val finalSnapshot = source.currentSnapshot().getOrThrow()
        if (finalSnapshot != initialSnapshot) {
            throw FileEditorSourceChangedException(initialSnapshot, finalSnapshot)
        }
        Result.success(
            EditorFileHashes(
                md5 = md5.digest().hexLower,
                sha1 = sha1.digest().hexLower,
                sha256 = sha256.digest().hexLower,
                crc32 = (crc32 xor 0xFFFFFFFFL).toString(16).padStart(8, '0'),
                sourceSnapshot = finalSnapshot,
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

private fun updateCrc32(initial: Long, bytes: ByteArray): Long {
    var crc = initial
    bytes.forEach { byte ->
        crc = crc xor (byte.toLong() and 0xFFL)
        repeat(8) {
            crc = if ((crc and 1L) != 0L) {
                (crc ushr 1) xor 0xEDB88320L
            } else {
                crc ushr 1
            }
        }
    }
    return crc
}
