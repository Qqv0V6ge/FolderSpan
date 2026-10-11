package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorTextCodecTest : ChineseLocalizationTest() {
    @Test
    fun supportedEncodingsRoundTripRepresentativeText() = runTest {
        val codec = EditorTextCodec.load()
        val samples = mapOf(
            EditorTextEncoding.ASCII to "FolderSpan 123",
            EditorTextEncoding.UTF8 to AppStrings.ui_test_editor_text_codec_multilingual_emoji_sample,
            EditorTextEncoding.UTF16_LE to AppStrings.ui_test_editor_text_codec_multilingual_emoji_sample,
            EditorTextEncoding.UTF16_BE to AppStrings.ui_test_editor_text_codec_multilingual_emoji_sample,
            EditorTextEncoding.GBK to AppStrings.ui_test_editor_text_codec_folderspan,
            EditorTextEncoding.ISO_8859_1 to "FolderSpan café",
        )

        samples.forEach { (encoding, text) ->
            val encoded = codec.encode(text, encoding)
            val decoded = codec.decode(encoded.bytes, encoding)

            assertEquals(0, encoded.replacementCount, encoding.name)
            assertFalse(decoded.hadErrors, encoding.name)
            assertEquals(text, decoded.text, encoding.name)
        }
    }

    @Test
    fun bomIsWrittenDetectedAndStripped() = runTest {
        val codec = EditorTextCodec.load()
        listOf(
            EditorTextEncoding.UTF8,
            EditorTextEncoding.UTF16_LE,
            EditorTextEncoding.UTF16_BE,
        ).forEach { encoding ->
            val encoded = codec.encode("text", encoding, includeBom = true)
            val detection = codec.detect(encoded.bytes)

            assertEquals(encoding, detection.encoding)
            assertTrue(detection.hasBom)
            assertEquals("text", codec.decode(encoded.bytes, encoding).text)
        }
    }

    @Test
    fun detectionUsesAsciiUtf8Utf16GbkAndLatin1Fallback() = runTest {
        val codec = EditorTextCodec.load()

        assertEquals(EditorTextEncoding.ASCII, codec.detect("plain".encodeToByteArray()).encoding)
        assertEquals(EditorTextEncoding.UTF8, codec.detect(AppStrings.ui_test_editor_text_codec_chinese_sample.encodeToByteArray()).encoding)
        assertEquals(
            EditorTextEncoding.UTF16_LE,
            codec.detect(codec.encode("English text", EditorTextEncoding.UTF16_LE).bytes).encoding,
        )
        assertEquals(
            EditorTextEncoding.GBK,
            codec.detect(codec.encode(AppStrings.ui_test_editor_text_codec_chinese_sample, EditorTextEncoding.GBK).bytes).encoding,
        )
        val fallback = codec.detect(byteArrayOf(0x81.toByte(), 0x30))
        assertEquals(EditorTextEncoding.ISO_8859_1, fallback.encoding)
        assertEquals(EditorEncodingConfidence.Low, fallback.confidence)
    }

    @Test
    fun unavailableCharactersAreReplacedAndReported() = runTest {
        val codec = EditorTextCodec.load()

        val ascii = codec.encode(AppStrings.ui_test_editor_text_codec_a_b, EditorTextEncoding.ASCII)
        val latin = codec.encode(AppStrings.ui_test_editor_text_codec_a_b, EditorTextEncoding.ISO_8859_1)
        val gbk = codec.encode("A😀B", EditorTextEncoding.GBK)

        assertContentEquals("A?B".encodeToByteArray(), ascii.bytes)
        assertEquals(listOf(1), ascii.replacementCharacterIndices)
        assertContentEquals("A?B".encodeToByteArray(), latin.bytes)
        assertEquals(listOf(1), latin.replacementCharacterIndices)
        assertEquals(2, gbk.replacementCount)
    }

    @Test
    fun invalidSequencesAreReportedWithoutThrowing() = runTest {
        val codec = EditorTextCodec.load()

        assertTrue(codec.decode(byteArrayOf(0xFF.toByte()), EditorTextEncoding.UTF8).hadErrors)
        assertTrue(codec.decode(byteArrayOf(0x41), EditorTextEncoding.UTF16_LE).hadErrors)
        assertTrue(codec.decode(byteArrayOf(0x81.toByte()), EditorTextEncoding.GBK).hadErrors)
        assertTrue(codec.decode(byteArrayOf(0xFF.toByte()), EditorTextEncoding.ASCII).hadErrors)
    }

    @Test
    fun incrementalDecoderPreservesCharactersAcrossEveryByteBoundary() = runTest {
        val codec = EditorTextCodec.load()
        val samples = mapOf(
            EditorTextEncoding.UTF8 to AppStrings.ui_test_editor_text_codec_unicode_chinese_emoji_sample,
            EditorTextEncoding.UTF16_LE to AppStrings.ui_test_editor_text_codec_unicode_chinese_emoji_sample,
            EditorTextEncoding.UTF16_BE to AppStrings.ui_test_editor_text_codec_unicode_chinese_emoji_sample,
            EditorTextEncoding.GBK to AppStrings.ui_test_editor_text_codec_gbk_chinese_sample,
        )

        samples.forEach { (encoding, expected) ->
            val encoded = codec.encode(expected, encoding).bytes
            for (splitAt in 1 until encoded.size) {
                val decoder = codec.incrementalDecoder(encoding)
                val actual = buildString {
                    append(decoder.accept(encoded.copyOfRange(0, splitAt)).text)
                    append(decoder.accept(encoded.copyOfRange(splitAt, encoded.size)).text)
                    append(decoder.finish().text)
                }
                assertEquals(expected, actual, "$encoding splitAt=$splitAt")
            }
        }
    }

    @Test
    fun incrementalDecoderReportsTruncatedFinalCharacterOnlyAtFinish() = runTest {
        val codec = EditorTextCodec.load()
        val decoder = codec.incrementalDecoder(EditorTextEncoding.UTF8)
        val first = decoder.accept(byteArrayOf(0xE4.toByte(), 0xB8.toByte()))

        assertEquals("", first.text)
        assertEquals(2, first.pendingByteCount)
        assertEquals(0, first.replacementCount)
        assertTrue(decoder.finish().replacementCount > 0)
    }
}
