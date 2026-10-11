package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorFileStatisticsAnalyzerTest : ChineseLocalizationTest() {
    @Test
    fun collectsTextAndByteStatisticsAcrossEveryByteBoundary() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val text = "a\r\n\r\n${AppStrings.ui_test_editor_file_statistics_analyzer_chinese}\n"
        val encoded = codec.encode(text, EditorTextEncoding.UTF16_LE, includeBom = true).bytes
        val source = RecordingFileEditorContentSource(encoded, canWrite = false)

        val result = EditorFileStatisticsAnalyzer(
            source = source,
            codec = codec,
            encoding = EditorTextEncoding.UTF16_LE,
            hasBom = true,
            chunkSize = 1,
        ).analyze().getOrThrow()

        assertEquals(4L, result.lineCount)
        assertEquals(2L, result.blankLineCount)
        assertEquals(1L, result.characterFrequency['a'])
        assertEquals(1L, result.characterFrequency['中'])
        assertEquals(encoded.count { it == 0.toByte() }.toLong(), result.zeroByteCount)
        assertEquals(encoded.size.toLong(), result.byteDistribution.sum())
        assertEquals(0L, result.replacementCharacterCount)
        assertTrue(source.reads.all { it.last == it.first })
    }

    @Test
    fun rejectsStatisticsAfterSourceSnapshotChanges() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val source = RecordingFileEditorContentSource("a\nb".encodeToByteArray(), canWrite = false)

        val result = EditorFileStatisticsAnalyzer(
            source,
            codec,
            EditorTextEncoding.UTF8,
            hasBom = false,
            chunkSize = 1,
        ).analyze { completed, _ ->
            if (completed == 1L) source.simulateExternalChange("c\nd".encodeToByteArray())
        }

        assertIs<FileEditorSourceChangedException>(result.exceptionOrNull())
    }

    @Test
    fun keywordStatisticsReuseSearchCaseWholeWordAndOverlapSemantics() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val source = RecordingFileEditorContentSource(
            "cat scatter CAT ababa".encodeToByteArray(),
            canWrite = false,
        )
        val analyzer = EditorFileStatisticsAnalyzer(
            source,
            codec,
            EditorTextEncoding.UTF8,
            hasBom = false,
            chunkSize = 3,
        )

        val result = analyzer.analyzeKeywords(
            queries = listOf("cat", "aba"),
            caseSensitive = false,
            wholeWord = true,
        ).getOrThrow()

        assertEquals(2L, result.occurrences["cat"])
        assertEquals(0L, result.occurrences["aba"])
    }

    @Test
    fun combinedStatisticsAndKeywordsUseOneBoundedScan() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val content = "cat\nscatter\ncat".encodeToByteArray()
        val source = RecordingFileEditorContentSource(content, canWrite = false)
        val analyzer = EditorFileStatisticsAnalyzer(
            source,
            codec,
            EditorTextEncoding.UTF8,
            hasBom = false,
            chunkSize = 3,
        )

        val result = analyzer.analyzeWithKeywords(listOf("cat")).getOrThrow()

        assertEquals(3L, result.statistics.lineCount)
        assertEquals(3L, result.keywordStatistics?.occurrences?.get("cat"))
        assertEquals((content.size + 2) / 3, source.reads.size)
        assertTrue(source.reads.all { it.count() <= 3 })
    }

    @Test
    fun statisticsAndKeywordsRespectTheRequestedRange() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val source = RecordingFileEditorContentSource(
            "first\nsecond\nfirst".encodeToByteArray(),
            canWrite = false,
        )
        val analyzer = EditorFileStatisticsAnalyzer(
            source = source,
            codec = codec,
            encoding = EditorTextEncoding.UTF8,
            hasBom = false,
            chunkSize = 2,
        )
        val range = EditorSearchRange(6L, 12L)

        val statistics = analyzer.analyze(range).getOrThrow()
        assertEquals(1L, statistics.lineCount)
        assertEquals(6L, statistics.byteDistribution.sum())
        assertEquals(1L, statistics.characterFrequency['s'])

        val keywords = analyzer.analyzeKeywords(
            queries = listOf("first", "second"),
            range = range,
        ).getOrThrow()
        assertEquals(0L, keywords.occurrences["first"])
        assertEquals(1L, keywords.occurrences["second"])
    }
}
