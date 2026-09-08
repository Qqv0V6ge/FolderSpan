package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRangeBoundsTest {
    @Test
    fun acceptsInRangeAndEmptyTruncate() {
        assertTrue(isWriteRangeWithinFile(fileSize = 1024L, offset = 0L, length = 16L))
        assertTrue(isWriteRangeWithinFile(fileSize = 1024L, offset = 1024L, length = 0L))
        assertTrue(isWriteRangeWithinFile(fileSize = 1024L, offset = 1000L, length = 24L))
        assertTrue(isWriteRangeWithinFile(fileSize = 0L, offset = 0L, length = 0L))
    }

    @Test
    fun rejectsNegativeAndPastEnd() {
        assertFalse(isWriteRangeWithinFile(fileSize = -1L, offset = 0L, length = 0L))
        assertFalse(isWriteRangeWithinFile(fileSize = 1024L, offset = -1L, length = 8L))
        assertFalse(isWriteRangeWithinFile(fileSize = 1024L, offset = 0L, length = -1L))
        assertFalse(isWriteRangeWithinFile(fileSize = 1024L, offset = 1020L, length = 16L))
        assertFalse(isWriteRangeWithinFile(fileSize = 0L, offset = 0L, length = 1L))
        assertFalse(isWriteRangeWithinFile(fileSize = 0L, offset = 1L, length = 0L))
    }

    @Test
    fun rejectsOverflowingOffsetPlusLength() {
        assertFalse(
            isWriteRangeWithinFile(
                fileSize = 1024L,
                offset = Long.MAX_VALUE - 4L,
                length = 8L,
            ),
        )
        assertFalse(
            isWriteRangeWithinFile(
                fileSize = 1024L,
                offset = 8L,
                length = Long.MAX_VALUE,
            ),
        )
    }
}
