package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.download.WebRtcBrowserZipFileWriter
import com.folderspan.service.webrtc.download.WebRtcBrowserZipSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import strings.AppStrings

class WebRtcBrowserDownloadSupportTest {
    @Test
    fun zipWriterRejectsOutOfOrderChunksBeyondPendingWindow() = runTest {
        val session = RecordingZipSession()
        val writer = WebRtcBrowserZipFileWriter(
            session = session,
            relativePath = "large.bin",
        )

        val error = assertFailsWith<IllegalStateException> {
            writer.writeChunk(
                path = "",
                fileSize = 128L * 1024L * 1024L,
                data = byteArrayOf(1),
                offset = 96L * 1024L * 1024L,
            ).getOrThrow()
        }

        assertEquals(
            AppStrings.ui_zip_file_chunk_caching_window_exceeds_limit_arg0.format(arg0 = "large.bin"),
            error.message,
        )
        assertEquals(listOf("large.bin"), session.begunFiles)
        assertTrue(session.writes.isEmpty())
    }

    @Test
    fun zipWriterFlushesContiguousChunksAndIgnoresAlreadyFlushedOffsets() = runTest {
        val session = RecordingZipSession()
        val writer = WebRtcBrowserZipFileWriter(
            session = session,
            relativePath = "ordered.bin",
        )

        assertTrue(writer.writeChunk("", 4L, byteArrayOf(3, 4), 2L).getOrThrow())
        assertTrue(writer.writeChunk("", 4L, byteArrayOf(1, 2), 0L).getOrThrow())
        assertTrue(writer.writeChunk("", 4L, byteArrayOf(1, 2), 0L).getOrThrow())

        assertEquals(
            listOf(
                ZipWrite("ordered.bin", 2, isLast = false, bytes = listOf(1, 2)),
                ZipWrite("ordered.bin", 2, isLast = true, bytes = listOf(3, 4)),
            ),
            session.writes,
        )
    }

    @Test
    fun zipWriterWritesLargeContiguousChunksWithoutPendingCacheLimit() = runTest {
        val session = RecordingZipSession()
        val writer = WebRtcBrowserZipFileWriter(
            session = session,
            relativePath = "large-contiguous.bin",
        )
        val chunk = ByteArray(33 * 1024 * 1024) { 1 }

        assertTrue(writer.writeChunk("", chunk.size.toLong(), chunk, 0L).getOrThrow())

        assertEquals(
            listOf(ZipWrite("large-contiguous.bin", chunk.size, isLast = true)),
            session.writes,
        )
    }

    private class RecordingZipSession : WebRtcBrowserZipSession {
        val begunFiles = mutableListOf<String>()
        val writes = mutableListOf<ZipWrite>()

        override suspend fun registerDirectory(relativePath: String): Result<Boolean> = Result.success(true)

        override suspend fun beginFile(relativePath: String): Result<Boolean> {
            begunFiles += relativePath
            return Result.success(true)
        }

        override suspend fun writeFileChunk(fileKey: String, bytes: ByteArray, isLast: Boolean): Result<Boolean> {
            val storedBytes = if (bytes.size <= 16) bytes.map { item -> item.toInt() } else emptyList()
            writes += ZipWrite(fileKey, bytes.size, isLast, storedBytes)
            return Result.success(true)
        }

        override suspend fun finalize(): Result<Boolean> = Result.success(true)

        override fun abort(reason: String) = Unit
    }

    private data class ZipWrite(
        val fileKey: String,
        val byteCount: Int,
        val isLast: Boolean,
        val bytes: List<Int> = emptyList(),
    )
}
