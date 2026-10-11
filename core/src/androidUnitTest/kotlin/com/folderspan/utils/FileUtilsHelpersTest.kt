package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer

class FileUtilsHelpersTest {
    @Test
    fun inMemoryReadLimitStaysBelowBinderSafeByteArrayChunkSize() {
        assertTrue(MAX_IN_MEMORY_READ_BYTES <= 4L * 1024L * 1024L)
    }

    @Test
    fun readChunksOrEmptyEmitsSingleEmptyChunkForEmptySource() {
        val chunks = Buffer().readChunksOrEmpty(chunkSize = 4).toList()

        assertEquals(1, chunks.size)
        assertEquals(0L, chunks.single().first)
        assertContentEquals(byteArrayOf(), chunks.single().second)
    }
}
