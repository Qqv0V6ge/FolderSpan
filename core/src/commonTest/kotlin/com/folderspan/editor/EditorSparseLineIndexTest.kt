package com.folderspan.editor

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorSparseLineIndexTest {
    @Test
    fun buildsSparseCheckpointsAndRoundTripsPrivateFormat() = runSuspendTest {
        val text = buildString {
            repeat(5_000) { append("line\n") }
            append("tail")
        }
        val source = RecordingFileEditorContentSource(text.encodeToByteArray(), canWrite = false)
        val index = EditorSparseLineIndexer(source, EditorTextEncoding.UTF8, chunkSize = 37)
            .build()
            .getOrThrow()

        assertEquals(5_001L, index.totalLineCount)
        assertEquals(listOf(1L, 4_097L), index.checkpoints.map { it.lineNumber })
        assertEquals(4_096L * 5L, index.checkpoints[1].byteOffset)
        assertTrue(index.complete)
        assertTrue(source.reads.all { it.last - it.first + 1L <= 37L })

        val restored = assertNotNull(EditorSparseLineIndex.decode(index.encode()))
        assertEquals(index, restored)
        assertEquals(index.cacheKey(), restored.cacheKey())
    }

    @Test
    fun detectsMixedNewlinesAcrossChunksForAllTextEncodings() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val text = "a\r\nb\nc\rd"
        EditorTextEncoding.entries.forEach { encoding ->
            val encoded = codec.encode(text, encoding).bytes
            if (encoding == EditorTextEncoding.ASCII ||
                encoding == EditorTextEncoding.UTF8 ||
                encoding == EditorTextEncoding.UTF16_LE ||
                encoding == EditorTextEncoding.UTF16_BE ||
                encoding == EditorTextEncoding.GBK ||
                encoding == EditorTextEncoding.ISO_8859_1
            ) {
                val source = RecordingFileEditorContentSource(encoded, canWrite = false)
                val indexer = EditorSparseLineIndexer(source, encoding, chunkSize = 3)
                val index = indexer.build().getOrThrow()

                assertEquals(4L, index.totalLineCount, encoding.name)
                val fourthLine = indexer.lineToByte(index, 4L).getOrThrow()
                assertEquals(encoded.size - codec.encode("d", encoding).bytes.size.toLong(), fourthLine)
                assertEquals(3L, indexer.byteToLine(index, fourthLine - 1L).getOrThrow())
            }
        }
    }

    @Test
    fun rejectsLookupAfterSourceSnapshotChanges() = runSuspendTest {
        val source = RecordingFileEditorContentSource("a\nb\n".encodeToByteArray())
        val indexer = EditorSparseLineIndexer(source, EditorTextEncoding.UTF8)
        val index = indexer.build().getOrThrow()

        source.simulateExternalChange("changed".encodeToByteArray())
        val error = indexer.lineToByte(index, 2L).exceptionOrNull()

        assertIs<FileEditorSourceChangedException>(error)
    }
}
