package com.folderspan.service.data

import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteBytesRequestTest {

    @Test
    fun resolveFileSizeUsesActualFileSizeTextWhenPresent() {
        val request = WriteBytesRequest(
            fileSize = Int.MAX_VALUE.toLong(),
            blockIndex = 0L,
            blockLength = 1024L,
            path = "/large.bin",
            byteArray = byteArrayOf(1, 2, 3),
            actualFileSizeText = "2285763977",
        )

        val resolved = request.resolveFileSize()

        assertTrue(resolved.isSuccess)
        assertEquals(2285763977L, resolved.getOrThrow())
    }

    @Test
    fun resolveFileSizeFallsBackToLegacyField() {
        val request = WriteBytesRequest(
            fileSize = 1024L,
            blockIndex = 0L,
            blockLength = 1024L,
            path = "/small.bin",
            byteArray = byteArrayOf(1, 2, 3),
        )

        val resolved = request.resolveFileSize()

        assertTrue(resolved.isSuccess)
        assertEquals(1024L, resolved.getOrThrow())
    }

    @Test
    fun resolveBlockStartOffsetUsesExplicitOffsetWhenPresent() {
        val request = WriteBytesRequest(
            fileSize = 1024L,
            blockIndex = 3L,
            blockLength = 256L,
            path = "/small.bin",
            byteArray = byteArrayOf(1, 2, 3),
            blockStartOffset = 512L,
        )

        val resolved = request.resolveBlockStartOffset()

        assertTrue(resolved.isSuccess)
        assertEquals(512L, resolved.getOrThrow())
    }

    @Test
    fun resolveBlockStartOffsetRejectsMissingOffset() {
        val request = WriteBytesRequest(
            fileSize = 1024L,
            blockIndex = 2L,
            blockLength = 256L,
            path = "/small.bin",
            byteArray = byteArrayOf(1, 2, 3),
        )

        val resolved = request.resolveBlockStartOffset()

        assertTrue(resolved.isFailure)
        assertEquals(AppStrings.error_write_block_offset_invalid, resolved.exceptionOrNull()?.message)
    }

    @Test
    fun streamRequestUsesActualFileSizeTextWhenPresent() {
        val request = WriteBytesStreamRequest(
            fileSize = Int.MAX_VALUE.toLong(),
            blockIndex = 0L,
            blockLength = 1024L,
            path = "/large.bin",
            actualFileSizeText = "2285763977",
        )

        val resolved = request.resolveFileSize()

        assertTrue(resolved.isSuccess)
        assertEquals(2285763977L, resolved.getOrThrow())
    }

    @Test
    fun streamRequestUsesExplicitBlockStartOffset() {
        val request = WriteBytesStreamRequest(
            fileSize = 1024L,
            blockIndex = 3L,
            blockLength = 256L,
            path = "/small.bin",
            blockStartOffset = 512L,
        )

        val resolved = request.resolveBlockStartOffset()

        assertTrue(resolved.isSuccess)
        assertEquals(512L, resolved.getOrThrow())
    }
}
