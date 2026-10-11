package com.folderspan.utils

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferedByteStreamWriterTest {
    @Test
    fun writeBufferedByteStreamCoalescesSmallReadsIntoBufferSizedWrites() = runSuspendTest {
        val source = ByteArray(10) { index -> (index + 1).toByte() }
        var cursor = 0
        val writes = mutableListOf<Pair<Long, ByteArray>>()
        val progress = mutableListOf<Pair<Long, Int>>()

        val result = writeBufferedByteStream(
            startOffset = 0L,
            expectedBytes = source.size.toLong(),
            bufferSize = 4,
            readNext = { buffer, _ ->
                if (cursor >= source.size) return@writeBufferedByteStream -1
                buffer[0] = source[cursor++]
                1
            },
            writeChunk = { offset, data ->
                writes += offset to data
            },
            onBytesWritten = { offset, bytesWritten ->
                progress += offset to bytesWritten
            },
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 2), writes.map { it.first to it.second.size })
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 2), progress)
        assertContentEquals(source, writes.flatMap { it.second.asIterable() }.toByteArray())
    }
}
