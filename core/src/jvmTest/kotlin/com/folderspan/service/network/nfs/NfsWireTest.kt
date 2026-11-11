package com.folderspan.service.network.nfs

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NfsWireTest {
    @Test
    fun xdrRoundTripAndLengthBoundaries() {
        val wire = XdrWriter().apply { int(-1); long(1L shl 40); bool(true); string("目录"); opaque(byteArrayOf(1, 2, 3)) }.bytes()
        val reader = XdrReader(wire)
        assertEquals(-1, reader.int())
        assertEquals(1L shl 40, reader.long())
        assertEquals(true, reader.bool())
        assertEquals("目录", reader.string())
        assertContentEquals(byteArrayOf(1, 2, 3), reader.opaque())
        reader.end()
        assertFailsWith<IllegalArgumentException> { XdrReader(XdrWriter().apply { int(-1) }.bytes()).opaque() }
        assertFailsWith<IllegalArgumentException> { XdrReader(XdrWriter().apply { int(99) }.bytes()).opaque(8) }
        assertFailsWith<IllegalStateException> { XdrReader(XdrWriter().apply { int(2) }.bytes()).bool() }
        assertFailsWith<IllegalArgumentException> { XdrReader(byteArrayOf(1)).long() }
    }

    @Test
    fun rpcCombinesFragmentsAndRejectsOversizedOrEndlessRecords() = runSuspendTest {
        val parts = ArrayDeque<ByteArray>()
        parts += XdrWriter().apply { int(2) }.bytes()
        parts += byteArrayOf(1, 2)
        parts += XdrWriter().apply { int(Int.MIN_VALUE or 1) }.bytes()
        parts += byteArrayOf(3)
        assertContentEquals(byteArrayOf(1, 2, 3), readRpcRecord { count -> parts.removeFirst().also { assertEquals(count, it.size) } })
        assertFailsWith<IllegalArgumentException> {
            readRpcRecord { XdrWriter().apply { int(Int.MIN_VALUE or (8 * 1024 * 1024 + 1)) }.bytes() }
        }
        assertFailsWith<IllegalStateException> { readRpcRecord { count -> ByteArray(count) } }
    }

    @Test
    fun shortWritesRetryTheUnwrittenSuffixAndRejectLengthMismatch() = runSuspendTest {
        val offsets = mutableListOf<Long>()
        var remaining: ByteArray? = byteArrayOf(1, 2, 3, 4, 5)
        writeNfsChunks(5, { remaining.also { remaining = null } }, { _, _ -> }) { offset, _ ->
            offsets += offset
            1
        }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), offsets)
        assertFailsWith<IllegalStateException> {
            writeNfsChunks(3, { null }, { _, _ -> }) { _, _ -> error("no bytes to write") }
        }
        remaining = byteArrayOf(1)
        assertFailsWith<IllegalStateException> {
            writeNfsChunks(1, { remaining.also { remaining = null } }, { _, _ -> }) { _, _ -> 0 }
        }
    }

    @Test
    fun exportPathsRejectParentComponentsAndUnsafeNames() {
        assertEquals(listOf("safe", "目录"), nfsComponents("/safe/目录"))
        assertFailsWith<IllegalArgumentException> { nfsComponents("/safe/../outside") }
        assertFailsWith<IllegalArgumentException> { nfsComponents("relative") }
        assertFailsWith<IllegalArgumentException> { nfsComponents("/safe/\\outside") }
    }
}
