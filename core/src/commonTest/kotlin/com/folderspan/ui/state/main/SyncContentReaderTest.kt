package com.folderspan.ui.state.main

import korlibs.crypto.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SyncContentReaderTest {
    @Test fun chunkBoundariesDoNotChangeContentAndReadBytesAreCountedSeparately() = runTest {
        val bytes = "same content across chunk boundaries".encodeToByteArray()
        var read = 0
        val first = syncStreamingContentSha256(bytes.size.toLong(), { consume ->
            for (chunk in bytes.asList().chunked(3)) consume(chunk.toByteArray())
        }, onRead = { read += it })
        val second = syncStreamingContentSha256(bytes.size.toLong(), { it(bytes) }, onRead = { read += it })
        assertEquals(bytes.sha256().hexLower, first)
        assertEquals(first, second)
        assertEquals(bytes.size * 2, read)
        assertNotEquals(first, syncStreamingContentSha256(bytes.size.toLong(), { it(ByteArray(bytes.size)) }))
        assertEquals(byteArrayOf().sha256().hexLower, syncStreamingContentSha256(0, {}))
    }

    @Test fun lengthChangesReadFailuresAndCancellationNeverProduceAProof() = runTest {
        assertFailsWith<IllegalStateException> { syncStreamingContentSha256(10, { it(byteArrayOf(1)) }) }
        assertFailsWith<IllegalStateException> { syncStreamingContentSha256(1, { it(byteArrayOf(1, 2)) }) }
        assertFailsWith<IllegalStateException> { syncStreamingContentSha256(1, { error("unreadable") }) }
        assertFailsWith<IllegalArgumentException> { syncStreamingContentSha256(-1, {}) }
        var checks = 0
        var read = 0
        assertFailsWith<CancellationException> {
            syncStreamingContentSha256(4, { consume -> repeat(4) { consume(byteArrayOf(1)) } },
                onRead = { read += it }, ensureRunning = { if (++checks == 3) throw CancellationException("cancel") })
        }
        assertEquals(1, read)
    }
}
