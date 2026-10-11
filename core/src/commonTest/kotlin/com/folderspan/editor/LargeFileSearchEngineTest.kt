package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeFileSearchEngineTest : ChineseLocalizationTest() {
    @Test
    fun textSearchKeepsExactOffsetsAcrossChunksForUtf16AndGbk() = runSuspendTest {
        val codec = EditorTextCodec.load()
        listOf(EditorTextEncoding.UTF16_LE, EditorTextEncoding.UTF16_BE, EditorTextEncoding.GBK)
            .forEach { encoding ->
                val text = AppStrings.ui_test_large_file_search_engine_hi_cd_hi
                val bytes = codec.encode(text, encoding).bytes
                val expectedStart = codec.encode("ab", encoding).bytes.size.toLong()
                val keywordSize = codec.encode(AppStrings.ui_test_large_file_search_engine_hello, encoding).bytes.size.toLong()
                val source = RecordingFileEditorContentSource(bytes, canWrite = false)
                val results = mutableListOf<EditorSearchResult>()

                val summary = LargeFileSearchEngine(source, codec, chunkSize = 3, batchSize = 1)
                    .search(
                        EditorSearchRequest(
                            mode = EditorSearchMode.Text,
                            queries = listOf(AppStrings.ui_test_large_file_search_engine_hello),
                            range = EditorSearchRange(0L, bytes.size.toLong()),
                            encoding = encoding,
                        ),
                        onBatch = results::addAll,
                    ).getOrThrow()

                assertEquals(2L, summary.totalMatches, encoding.name)
                assertEquals(expectedStart, results.first().startOffset, encoding.name)
                assertEquals(expectedStart + keywordSize, results.first().endOffsetExclusive)
                assertTrue(source.reads.all { it.last - it.first + 1L <= 3L })
            }
    }

    @Test
    fun textSearchSupportsLiteralSpacesAndNewlinesAcrossChunks() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val source = RecordingFileEditorContentSource("a \nb a \nb".encodeToByteArray(), canWrite = false)
        val results = mutableListOf<EditorSearchResult>()

        val summary = LargeFileSearchEngine(source, codec, chunkSize = 1).search(
            EditorSearchRequest(
                mode = EditorSearchMode.Text,
                queries = listOf(" \n"),
                range = EditorSearchRange(0L, source.size),
            ),
            results::addAll,
        ).getOrThrow()

        assertEquals(2L, summary.totalMatches)
        assertEquals(listOf(1L, 6L), results.map(EditorSearchResult::startOffset))
        assertEquals(listOf(3L, 8L), results.map(EditorSearchResult::endOffsetExclusive))
    }

    @Test
    fun regexMatchesCrossReadBoundariesWithoutDuplicates() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val bytes = "xxABC123yyABC123".encodeToByteArray()
        val source = RecordingFileEditorContentSource(bytes, canWrite = false)

        val regexResults = mutableListOf<EditorSearchResult>()
        LargeFileSearchEngine(source, codec, chunkSize = 3).search(
            EditorSearchRequest(
                EditorSearchMode.Regex,
                listOf("ABC\\d+"),
                EditorSearchRange(0L, bytes.size.toLong()),
            ),
            regexResults::addAll,
        ).getOrThrow()
        assertEquals(listOf(2L, 10L), regexResults.map { it.startOffset })
    }

    @Test
    fun defaultRegexScanKeepsReadAmplificationAndBuffersBounded() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val size = DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE.toLong() * 3L
        val source = VirtualFileEditorContentSource(size)

        LargeFileSearchEngine(source, codec).search(
            EditorSearchRequest(
                EditorSearchMode.Regex,
                listOf("Z+"),
                EditorSearchRange(0L, size),
            ),
            onBatch = {},
        ).getOrThrow()

        val readBytes = source.reads.sumOf(LongRange::count).toLong()
        assertTrue(source.reads.size <= 3)
        assertTrue(readBytes <= size * 2L)
        assertTrue(
            source.reads.all {
                it.count() <= DEFAULT_FILE_EDITOR_SCAN_CHUNK_SIZE +
                    EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES
            }
        )
    }

    @Test
    fun supportsCaseWholeWordRangesAndOverlappingMultipleKeywords() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val text = "cat scatter CAT cat ababa"
        val source = RecordingFileEditorContentSource(text.encodeToByteArray(), canWrite = false)
        val words = mutableListOf<EditorSearchResult>()

        LargeFileSearchEngine(source, codec, chunkSize = 5).search(
            EditorSearchRequest(
                mode = EditorSearchMode.Text,
                queries = listOf("cat"),
                range = EditorSearchRange(0L, 19L),
                caseSensitive = false,
                wholeWord = true,
            ),
            words::addAll,
        ).getOrThrow()
        assertEquals(listOf(0L, 12L, 16L), words.map { it.startOffset })

        val selected = mutableListOf<EditorSearchResult>()
        LargeFileSearchEngine(source, codec, chunkSize = 2).search(
            EditorSearchRequest(
                EditorSearchMode.Text,
                listOf("CAT"),
                EditorSearchRange(12L, 15L),
            ),
            selected::addAll,
        ).getOrThrow()
        assertEquals(listOf(12L), selected.map { it.startOffset })

        val overlaps = mutableListOf<EditorSearchResult>()
        LargeFileSearchEngine(source, codec, chunkSize = 2).search(
            EditorSearchRequest(
                EditorSearchMode.Text,
                listOf("aba", "bab"),
                EditorSearchRange(20L, text.length.toLong()),
            ),
            overlaps::addAll,
        ).getOrThrow()
        assertEquals(
            setOf(20L to "aba", 21L to "bab", 22L to "aba"),
            overlaps.map { it.startOffset to it.keyword }.toSet(),
        )
    }

    @Test
    fun publishesBatchesProgressAndRejectsChangedSources() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val source = RecordingFileEditorContentSource("aaaaaa".encodeToByteArray(), canWrite = false)
        val batches = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = LargeFileSearchEngine(source, codec, chunkSize = 2, batchSize = 2).search(
            EditorSearchRequest(
                EditorSearchMode.Text,
                listOf("aa"),
                EditorSearchRange(0L, 6L),
            ),
            onBatch = { batches += it.size },
            onProgress = { completed, total ->
                progress += completed to total
                if (completed == 2L) source.simulateExternalChange("bbbbbb".encodeToByteArray())
            },
        )

        assertIs<FileEditorSourceChangedException>(result.exceptionOrNull())
        assertTrue(batches.isNotEmpty())
        assertEquals(6L to 6L, progress.last())
    }

    @Test
    fun regexSpanLimitAndCancellationAreExplicit() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val huge = ByteArray(EDITOR_SEARCH_REGEX_MAX_MATCH_BYTES + 2) { 'a'.code.toByte() }
        val limitError = LargeFileSearchEngine(
            RecordingFileEditorContentSource(huge, canWrite = false),
            codec,
            chunkSize = 4,
        ).search(
            EditorSearchRequest(
                EditorSearchMode.Regex,
                listOf("a+"),
                EditorSearchRange(0L, huge.size.toLong()),
            ),
            onBatch = {},
        ).exceptionOrNull()
        assertIs<EditorRegexMatchLimitException>(limitError)

        assertFailsWith<CancellationException> {
            LargeFileSearchEngine(CancellingSearchSource(), codec, chunkSize = 2).search(
                EditorSearchRequest(
                    EditorSearchMode.Text,
                    listOf("missing"),
                    EditorSearchRange(0L, 4L),
                ),
                onBatch = {},
            )
        }
    }

    @Test
    fun resultStoreCapsDetailsButKeepsTotalsAndNavigatesBothDirections() {
        val store = EditorSearchResultStore(detailLimit = 2)
        store.add(
            listOf(
                EditorSearchResult(1L, 2L, "a", ""),
                EditorSearchResult(2L, 3L, "a", ""),
                EditorSearchResult(3L, 4L, "a", ""),
            )
        )

        assertEquals(3L, store.totalCount)
        assertEquals(2, store.results.size)
        assertTrue(store.detailsTruncated)
        assertEquals(0, store.navigate(null, EditorSearchDirection.Forward, wrap = false))
        assertEquals(1, store.navigate(0, EditorSearchDirection.Forward, wrap = false))
        assertNull(store.navigate(1, EditorSearchDirection.Forward, wrap = false))
        assertEquals(0, store.navigate(1, EditorSearchDirection.Forward, wrap = true))
        assertEquals(1, store.navigate(0, EditorSearchDirection.Backward, wrap = true))
    }

    private class CancellingSearchSource : FileEditorContentSource {
        override val size = 4L
        override val canWrite = false
        private var reads = 0

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> {
            reads += 1
            if (reads == 2) throw CancellationException(AppStrings.ui_cancel_search)
            return Result.success(ByteArray((endOffsetExclusive - startOffset).toInt()))
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(IllegalStateException(AppStrings.ui_read_only))
    }
}
