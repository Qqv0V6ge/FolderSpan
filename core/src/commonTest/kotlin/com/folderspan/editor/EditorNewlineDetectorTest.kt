package com.folderspan.editor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorNewlineDetectorTest {
    @Test
    fun crlfAcrossChunksCountsAsSingleNewline() {
        val detector = EditorNewlineDetector()

        detector.accept("first\r")
        detector.accept("\nsecond\nthird\r")
        val result = detector.finish()

        assertEquals(EditorNewlineKind.Mixed, result.kind)
        assertEquals(1L, result.crlfCount)
        assertEquals(1L, result.lfCount)
        assertEquals(1L, result.crCount)
    }

    @Test
    fun detectorRecognizesEachNewlineKind() {
        mapOf(
            "a\nb" to EditorNewlineKind.LF,
            "a\r\nb" to EditorNewlineKind.CRLF,
            "a\rb" to EditorNewlineKind.CR,
            "ab" to EditorNewlineKind.None,
        ).forEach { (text, expected) ->
            val detector = EditorNewlineDetector()
            detector.accept(text)
            assertEquals(expected, detector.finish().kind)
        }
    }

    @Test
    fun newlineConversionNormalizesAllForms() {
        val text = "a\r\nb\nc\rd"

        assertEquals("a\nb\nc\nd", convertNewlines(text, EditorNewlineKind.LF))
        assertEquals("a\r\nb\r\nc\r\nd", convertNewlines(text, EditorNewlineKind.CRLF))
        assertEquals("a\rb\rc\rd", convertNewlines(text, EditorNewlineKind.CR))
    }

    @Test
    fun byteTransformerConvertsAcrossEveryChunkForAllEncodings() = runTest {
        val codec = EditorTextCodec.load()
        val original = "a\r\nb\nc\rd"
        EditorTextEncoding.entries.forEach { encoding ->
            val input = codec.encode(original, encoding).bytes
            val transformer = EditorNewlineByteTransformer(codec, encoding, EditorNewlineKind.CRLF)
            val output = buildList<Byte> {
                input.forEach { byte -> transformer.accept(byteArrayOf(byte)).forEach(::add) }
                transformer.finish().forEach(::add)
            }.toByteArray()

            assertEquals(
                "a\r\nb\r\nc\r\nd",
                codec.decode(output, encoding, stripBom = false).text,
                encoding.name,
            )
        }
    }
}
