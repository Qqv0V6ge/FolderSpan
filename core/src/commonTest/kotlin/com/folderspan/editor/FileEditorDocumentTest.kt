package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileEditorDocumentTest : ChineseLocalizationTest() {
    @Test
    fun writableDocumentStartsEditableAndCanReturnToEditing() = runSuspendTest {
        val source = ByteArrayContentSource("content".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.state.value.sourceCanWrite)
        assertTrue(document.state.value.canWrite)
        assertEquals(FileEditorAccessMode.Editable, document.state.value.accessMode)

        assertTrue(document.lockEditing().isSuccess)
        assertFalse(document.state.value.canWrite)
        assertTrue(document.updateText("changed").isFailure)

        assertTrue(document.unlockEditing(confirmed = false).isFailure)
        assertFalse(document.state.value.canWrite)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.state.value.canWrite)
        assertEquals(FileEditorAccessMode.Editable, document.state.value.accessMode)
    }

    @Test
    fun writableDocumentDefaultsToEditingForEverySession() = runSuspendTest {
        val source = ByteArrayContentSource("content".encodeToByteArray(), canWrite = true)
        val first = FileEditorDocument(source)

        assertTrue(first.initialize().isSuccess)
        assertTrue(first.state.value.canWrite)
        assertTrue(first.lockEditing().isSuccess)
        assertFalse(first.state.value.canWrite)
        first.close()

        val second = FileEditorDocument(source)
        assertTrue(second.initialize().isSuccess)
        assertTrue(second.state.value.canWrite)
        assertEquals(FileEditorAccessMode.Editable, second.state.value.accessMode)
    }

    @Test
    fun oneGiBFileKeepsTextEditsBoundedToTheLoadedPage() = runSuspendTest {
        val source = VirtualContentSource(
            size = FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES,
            canWrite = true,
        )
        val document = FileEditorDocument(
            source = source,
            pageSize = 4,
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.state.value.isOversized)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("BBBB").isSuccess)
        assertEquals("BBBB", document.state.value.text)
        assertEquals(1, document.state.value.modifications.size)
    }

    @Test
    fun readOnlyDocumentLoadsOneBoundedPageAndRejectsChanges() = runSuspendTest {
        val source = ByteArrayContentSource(
            initial = ByteArray(DEFAULT_FILE_EDITOR_PAGE_SIZE * 2) { index -> (index % 127).toByte() },
            canWrite = false,
        )
        val document = FileEditorDocument(source)

        document.initialize()
        assertFalse(document.state.value.canWrite)
        val loaded = document.state.value
        document.updateText(AppStrings.ui_test_file_editor_document_cannot_write_into)
        val save = document.save()

        assertTrue(source.reads.isNotEmpty())
        assertTrue(source.reads.all { (start, end) -> end - start <= DEFAULT_FILE_EDITOR_PAGE_SIZE })
        assertFalse(loaded.canWrite)
        assertEquals(FileEditorAccessMode.ReadOnly, loaded.accessMode)
        assertFalse(document.state.value.isDirty)
        assertTrue(save.isFailure)
    }

    @Test
    fun documentPrefetchesAdjacentPagesAndKeepsTheCacheBounded() = runSuspendTest {
        val source = VirtualContentSource(size = 1024L * 1024 * 1024)
        val document = FileEditorDocument(source, maxCachedPages = 3)
        val pageSize = DEFAULT_FILE_EDITOR_PAGE_SIZE.toLong()

        assertTrue(document.initialize().isSuccess)
        withTimeout(5_000L) {
            while (source.reads.none { (start, end) -> start == pageSize && end - start == pageSize }) {
                yield()
            }
        }
        val prefetchedNextPageReads = source.reads.count { (start, end) ->
            start == pageSize && end - start == pageSize
        }

        assertTrue(document.goToPage(1L).isSuccess)

        assertEquals(
            prefetchedNextPageReads,
            source.reads.count { (start, end) -> start == pageSize && end - start == pageSize },
        )
        assertTrue(source.reads.all { (start, end) -> end - start <= DEFAULT_FILE_EDITOR_PAGE_SIZE })
        assertEquals(1L, document.state.value.pageIndex)
        assertEquals(32_768L, document.state.value.pageCount)
    }

    @Test
    fun jumpsToValidatedByteOffsetAndPercentage() = runSuspendTest {
        val source = VirtualContentSource(size = 1_000L)
        val document = FileEditorDocument(source, pageSize = 100)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.goToByteOffset(250L).isSuccess)
        assertEquals(2L, document.state.value.currentPage)
        assertEquals(250L, document.state.value.selectionStartOffset)

        assertTrue(document.goToPercentage(100.0).isSuccess)
        assertEquals(9L, document.state.value.currentPage)
        assertEquals(999L, document.state.value.selectionStartOffset)
        assertTrue(document.goToByteOffset(1_000L).isFailure)
        assertTrue(document.goToPercentage(100.1).isFailure)
    }

    @Test
    fun jumpsToValidatedLineAndLoadsContainingPage() = runSuspendTest {
        val source = ByteArrayContentSource("first\nsecond\nthird\nfourth".encodeToByteArray(), canWrite = false)
        val document = FileEditorDocument(source, pageSize = 8)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.goToLine(3L).isSuccess)

        assertEquals(13L, document.state.value.selectionStartOffset)
        assertEquals(1L, document.state.value.currentPage)
        assertEquals(3L, document.state.value.currentLineNumber)
        assertFalse(document.state.value.lineNumberProvisional)
        assertFalse(document.state.value.isLineIndexing)
        assertEquals(1f, document.state.value.lineIndexProgress)

        assertTrue(document.goToLine(0L).isFailure)
        assertTrue(document.goToLine(5L).isFailure)
        assertFalse(document.state.value.isLineIndexing)
    }

    @Test
    fun textReplacementStreamsUnchangedPagesAndSupportsLengthChanges() = runSuspendTest {
        val first = "a".repeat(DEFAULT_FILE_EDITOR_PAGE_SIZE).encodeToByteArray()
        val second = "b".repeat(DEFAULT_FILE_EDITOR_PAGE_SIZE).encodeToByteArray()
        val third = "tail".encodeToByteArray()
        val source = ByteArrayContentSource(first + second + third, canWrite = true)
        val document = FileEditorDocument(source)

        document.initialize()
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        document.goToPage(1)
        document.updateText("replacement")
        val save = document.save()

        assertTrue(save.isSuccess)
        assertContentEquals(first + "replacement".encodeToByteArray() + third, source.snapshot())
        assertFalse(document.state.value.isDirty)
        assertEquals(1f, document.state.value.saveProgress)
        assertTrue(source.writtenChunkSizes.all { size -> size <= DEFAULT_FILE_EDITOR_PAGE_SIZE })
    }

    @Test
    fun explicitNewlineConversionStreamsWholeFileAcrossPageBoundaries() = runSuspendTest {
        val source = ByteArrayContentSource("a\r\nb\nc\rd".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source, pageSize = 2)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.setNewlineConversion(EditorNewlineKind.CRLF).isSuccess)
        assertTrue(document.state.value.dirty)
        assertTrue(document.save().isSuccess)

        assertContentEquals("a\r\nb\r\nc\r\nd".encodeToByteArray(), source.snapshot())
        assertEquals(EditorNewlineKind.CRLF, document.state.value.newlineKind)
        assertEquals(null, document.state.value.newlineConversion)
        assertFalse(document.state.value.dirty)
    }

    @Test
    fun ordinarySavePreservesMixedNewlineBytes() = runSuspendTest {
        val original = "a\r\nb\nc\rd".encodeToByteArray()
        val source = ByteArrayContentSource(original, canWrite = true)
        val document = FileEditorDocument(source, pageSize = 3)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("A\r\n").isSuccess)
        assertTrue(document.save().isSuccess)

        assertContentEquals("A\r\nb\nc\rd".encodeToByteArray(), source.snapshot())
    }

    @Test
    fun oversizedSinglePageTextEditIsRejectedToKeepMemoryBounded() = runSuspendTest {
        val source = ByteArrayContentSource("text".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source, pageSize = 4)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        val result = document.updateText("a".repeat(17))

        assertTrue(result.isFailure)
        assertEquals("text", document.state.value.text)
        assertFalse(document.state.value.dirty)
    }

    @Test
    fun multibyteCharactersSplitAcrossPagesAreDisplayedExactlyOnce() = runSuspendTest {
        val codec = EditorTextCodec.load()
        val samples = listOf(
            Triple(EditorTextEncoding.UTF8, "aaa€tail", 4),
            Triple(EditorTextEncoding.UTF16_LE, "A😀tail", 4),
            Triple(EditorTextEncoding.UTF16_BE, "A😀tail", 4),
            Triple(EditorTextEncoding.GBK, AppStrings.ui_test_file_editor_document_tail, 4),
        )

        samples.forEach { (encoding, expected, pageSize) ->
            val source = ByteArrayContentSource(codec.encode(expected, encoding).bytes, canWrite = true)
            val document = FileEditorDocument(source, pageSize = pageSize)

            assertTrue(document.initialize().isSuccess, encoding.name)
            assertTrue(document.setEncoding(encoding).isSuccess, encoding.name)
            val pageTexts = buildList {
                repeat(document.state.value.pageCount.toInt()) { page ->
                    assertTrue(document.goToPage(page.toLong()).isSuccess, encoding.name)
                    add(document.state.value.text)
                }
            }
            assertEquals(expected, pageTexts.joinToString(""), "$encoding pages=$pageTexts")

            assertTrue(document.goToPage(0L).isSuccess)
            assertTrue(document.unlockEditing(confirmed = true).isSuccess)
            assertTrue(document.updateText(document.state.value.text).isFailure, encoding.name)
        }
    }

    @Test
    fun modificationListUndoAndRedoTrackTextReplacementAndCleanState() = runSuspendTest {
        val source = ByteArrayContentSource("abcdef".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("abXYef").isSuccess)

        val modified = document.state.value
        assertTrue(modified.dirty)
        assertTrue(modified.canUndo)
        assertFalse(modified.canRedo)
        assertEquals(1, modified.modifications.size)
        assertEquals(2L, modified.modifications.single().startOffset)
        assertEquals(4L, modified.modifications.single().endOffsetExclusive)
        assertEquals(EditorModificationKind.TextReplacement, modified.modifications.single().kind)

        assertTrue(document.undo().isSuccess)
        assertContentEquals("abcdef".encodeToByteArray(), document.state.value.bytes)
        assertFalse(document.state.value.dirty)
        assertTrue(document.state.value.modifications.isEmpty())
        assertFalse(document.state.value.canUndo)
        assertTrue(document.state.value.canRedo)

        assertTrue(document.redo().isSuccess)
        assertContentEquals("abXYef".encodeToByteArray(), document.state.value.bytes)
        assertTrue(document.state.value.dirty)
        assertTrue(document.state.value.canUndo)
        assertFalse(document.state.value.canRedo)
    }

    @Test
    fun consecutiveTextChangesUndoAndRedoOneStepAtATime() = runSuspendTest {
        val source = ByteArrayContentSource("a".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("ab").isSuccess)
        assertTrue(document.updateText("abc").isSuccess)
        assertTrue(document.updateText("abcd").isSuccess)
        assertEquals(3, document.state.value.editHistoryCommandCount)

        assertTrue(document.undo().isSuccess)
        assertEquals("abc", document.state.value.text)
        assertTrue(document.undo().isSuccess)
        assertEquals("ab", document.state.value.text)
        assertTrue(document.undo().isSuccess)
        assertEquals("a", document.state.value.text)
        assertFalse(document.state.value.canUndo)

        assertTrue(document.redo().isSuccess)
        assertEquals("ab", document.state.value.text)
        assertTrue(document.redo().isSuccess)
        assertEquals("abc", document.state.value.text)
        assertTrue(document.redo().isSuccess)
        assertEquals("abcd", document.state.value.text)
        assertFalse(document.state.value.canRedo)

        assertTrue(document.undo().isSuccess)
        assertTrue(document.updateText("abc!").isSuccess)
        assertFalse(document.state.value.canRedo)
    }

    @Test
    fun textAndRegexReplacementUseAtomicHistoryTransactions() = runSuspendTest {
        val textSource = ByteArrayContentSource("cat cat aaa".encodeToByteArray(), canWrite = true)
        val textDocument = FileEditorDocument(textSource)
        assertTrue(textDocument.initialize().isSuccess)
        assertTrue(textDocument.unlockEditing(confirmed = true).isSuccess)

        val textSummary = textDocument.replaceAll(
            EditorReplaceRequest(
                search = EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf("cat"),
                    range = EditorSearchRange(0L, textSource.size),
                ),
                replacement = "dog",
            )
        ).getOrThrow()
        assertEquals(2, textSummary.replacedCount)
        assertEquals("dog dog aaa", textDocument.state.value.text)
        assertEquals(1, textDocument.state.value.editHistoryCommandCount)
        assertTrue(textDocument.undo().isSuccess)
        assertEquals("cat cat aaa", textDocument.state.value.text)
        assertTrue(textDocument.redo().isSuccess)
        assertEquals("dog dog aaa", textDocument.state.value.text)

        assertTrue(textDocument.undo().isSuccess)
        val regexSummary = textDocument.replaceCurrent(
            request = EditorReplaceRequest(
                search = EditorSearchRequest(
                    mode = EditorSearchMode.Regex,
                    queries = listOf("(cat)"),
                    range = EditorSearchRange(0L, textSource.size),
                ),
                replacement = "[$1]",
            ),
            result = EditorSearchResult(0L, 3L, "(cat)", "cat"),
        ).getOrThrow()
        assertEquals(1, regexSummary.replacedCount)
        assertEquals("[cat] cat aaa", textDocument.state.value.text)
        assertTrue(
            textDocument.startSearch(
                EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf("[cat]"),
                    range = EditorSearchRange(0L, textSource.size),
                )
            ).isSuccess
        )
        while (textDocument.state.value.isSearching) yield()
        assertEquals(1L, textDocument.state.value.searchTotalCount)
        assertEquals(0L, textDocument.state.value.searchResults.single().startOffset)
        assertTrue(
            textDocument.replaceCurrent(
                request = EditorReplaceRequest(
                    search = EditorSearchRequest(
                        mode = EditorSearchMode.Text,
                        queries = listOf("dog"),
                        range = EditorSearchRange(0L, textSource.size),
                    ),
                    replacement = "stale",
                ),
                result = textDocument.state.value.searchResults.single(),
            ).isFailure
        )
        assertEquals("[cat] cat aaa", textDocument.state.value.text)

    }

    @Test
    fun replaceAllAcrossPagesIsOneUndoAndRedoStep() = runSuspendTest {
        val source = ByteArrayContentSource("cat cat".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source, pageSize = 4)
        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)

        val summary = document.replaceAll(
            EditorReplaceRequest(
                search = EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf("cat"),
                    range = EditorSearchRange(0L, source.size),
                ),
                replacement = "dog",
            )
        ).getOrThrow()

        assertEquals(2, summary.replacedCount)
        assertEquals(1, document.state.value.editHistoryCommandCount)
        assertEquals("dog ", document.state.value.text)
        assertTrue(document.goToPage(1L).isSuccess)
        assertEquals("dog", document.state.value.text)

        assertTrue(document.undo().isSuccess)
        assertEquals(0L, document.state.value.currentPage)
        assertEquals("cat ", document.state.value.text)
        assertTrue(document.goToPage(1L).isSuccess)
        assertEquals("cat", document.state.value.text)

        assertTrue(document.redo().isSuccess)
        assertEquals(0L, document.state.value.currentPage)
        assertEquals("dog ", document.state.value.text)
        assertTrue(document.goToPage(1L).isSuccess)
        assertEquals("dog", document.state.value.text)
    }

    @Test
    fun replaceAllSkipsOverlapsAndStatisticsUseEditedRanges() = runSuspendTest {
        val source = ByteArrayContentSource("aaa\nrest".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source, pageSize = 4)
        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)

        val summary = document.replaceAll(
            EditorReplaceRequest(
                search = EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf("aa"),
                    range = EditorSearchRange(0L, source.size),
                ),
                replacement = "b",
            )
        ).getOrThrow()
        assertEquals(1, summary.replacedCount)
        assertEquals(1, summary.skippedOverlappingCount)
        assertEquals("ba\n", document.state.value.text)

        assertTrue(
            document.startStatistics(
                EditorStatisticsRequest(scope = EditorStatisticsScope.CurrentPage)
            ).isSuccess
        )
        while (document.state.value.isAnalyzing) yield()
        assertEquals(3L, document.state.value.statistics?.byteDistribution?.sum())
        assertEquals(EditorStatisticsScope.CurrentPage, document.state.value.statisticsScope)

        assertTrue(document.updateTextSelection(0, 2).isSuccess)
        assertTrue(
            document.startStatistics(
                EditorStatisticsRequest(scope = EditorStatisticsScope.Selection)
            ).isSuccess
        )
        while (document.state.value.isAnalyzing) yield()
        assertEquals(2L, document.state.value.statistics?.byteDistribution?.sum())
        assertEquals(EditorStatisticsScope.Selection, document.state.value.statisticsScope)

        assertTrue(
            document.startStatistics(
                EditorStatisticsRequest(scope = EditorStatisticsScope.WholeFile)
            ).isSuccess
        )
        while (document.state.value.isAnalyzing) yield()
        assertEquals(7L, document.state.value.statistics?.byteDistribution?.sum())
    }

    @Test
    fun editHistoryStaysBoundedAcrossModifiedPages() = runSuspendTest {
        val source = ByteArrayContentSource("abcdefgh".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(
            source = source,
            pageSize = 2,
            maxEditHistoryCommands = 2,
            maxEditHistoryBytes = 128L,
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        repeat(3) { page ->
            assertTrue(document.goToPage(page.toLong()).isSuccess)
            assertTrue(document.updateText("${('A'.code + page).toChar()}${('a'.code + page).toChar()}").isSuccess)
        }

        val state = document.state.value
        assertEquals(3, state.modifications.size)
        assertEquals(2, state.editHistoryCommandCount)
        assertTrue(state.editHistoryStoredBytes <= 128L)

        assertTrue(document.undo().isSuccess)
        assertTrue(document.undo().isSuccess)
        assertTrue(document.undo().isFailure)
        assertEquals(1, document.state.value.modifications.size)
        assertTrue(document.state.value.dirty)
    }

    @Test
    fun savePreviewListsRangesConversionsBackupAndEncodingReplacementsWithoutWriting() = runSuspendTest {
        val source = ByteArrayContentSource("A\nB".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.setEncoding(EditorTextEncoding.ASCII).isSuccess)
        assertTrue(document.updateText(AppStrings.ui_test_file_editor_document_a).isSuccess)
        assertTrue(document.setNewlineConversion(EditorNewlineKind.CRLF).isSuccess)

        val preview = document.buildSavePreview().getOrThrow()
        assertEquals(1, preview.changedRanges.size)
        assertEquals(EditorTextEncoding.ASCII, preview.encoding)
        assertEquals(EditorNewlineKind.CRLF, preview.newlineConversion)
        assertEquals(EditorBackupMode.CompleteFile, preview.backupMode)
        assertEquals(source.size, preview.estimatedBackupBytes)
        assertEquals(1, preview.replacementCharacterCount)
        assertEquals('中', preview.replacementCharacterSamples.single().character)
        assertEquals(1L, preview.sizeDelta)
        assertEquals(0, source.replaceCalls)
        assertTrue(document.state.value.dirty)
    }

    @Test
    fun saveAbortsOnExternalSnapshotConflictThenSupportsReload() = runSuspendTest {
        val source = ByteArrayContentSource("before".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("edited").isSuccess)
        source.externalReplace("beyond".encodeToByteArray())

        val conflict = document.save()
        assertTrue(conflict.isFailure)
        assertTrue(conflict.exceptionOrNull() is FileEditorSourceChangedException)
        assertEquals(0, source.replaceCalls)
        assertTrue(document.state.value.dirty)
        assertTrue(document.state.value.saveConflict != null)
        assertTrue(document.reload(discardChangesConfirmed = false).isFailure)

        assertTrue(document.reload(discardChangesConfirmed = true).isSuccess)
        assertContentEquals("beyond".encodeToByteArray(), document.state.value.bytes)
        assertFalse(document.state.value.dirty)
        assertEquals(null, document.state.value.saveConflict)
    }

    @Test
    fun explicitlyConfirmedForcedOverwriteUsesLatestSnapshot() = runSuspendTest {
        val source = ByteArrayContentSource("before".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("edited").isSuccess)
        source.externalReplace("beyond".encodeToByteArray())

        assertTrue(document.save().isFailure)
        assertTrue(document.save(forceOverwriteConfirmed = true).isSuccess)
        assertContentEquals("edited".encodeToByteArray(), source.snapshot())
        assertFalse(document.state.value.dirty)
        assertEquals(null, document.state.value.saveConflict)
    }

    @Test
    fun revertingPageToOriginalContentClearsDirtyWithoutWriting() = runSuspendTest {
        val source = ByteArrayContentSource("original".encodeToByteArray(), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("changed").isSuccess)
        assertTrue(document.state.value.dirty)

        assertTrue(document.updateText("original").isSuccess)
        assertFalse(document.state.value.dirty)
        assertTrue(document.save().isSuccess)
        assertEquals(0, source.replaceCalls)
    }

    @Test
    fun binaryPageOpensInTextModeByDefault() = runSuspendTest {
        val source = ByteArrayContentSource(byteArrayOf(0, 1, 2, 3), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)

        assertFalse(document.state.value.supportsTextMode)
        assertTrue(document.state.value.text.isNotEmpty())
    }

    @Test
    fun pageNavigationKeepsTextModeForBinaryPages() = runSuspendTest {
        val source = ByteArrayContentSource(
            "text".encodeToByteArray() + byteArrayOf(0, 1, 2, 3),
            canWrite = true,
        )
        val document = FileEditorDocument(source, pageSize = 4)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.state.value.supportsTextMode)

        assertTrue(document.goToPage(1L).isSuccess)
        assertFalse(document.state.value.supportsTextMode)
        assertTrue(document.state.value.text.isNotEmpty())
    }

    @Test
    fun binaryPageUsesLossyTextPresentation() = runSuspendTest {
        val source = ByteArrayContentSource(byteArrayOf(0xC3.toByte(), 0x28), canWrite = true)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertEquals(EditorTextEncoding.ISO_8859_1, document.state.value.encoding)
        assertEquals(EditorEncodingConfidence.Low, document.state.value.encodingConfidence)
        assertEquals("Ã(", document.state.value.text)
    }

    @Test
    fun failedSaveKeepsDirtyReplacementForRetry() = runSuspendTest {
        val source = ByteArrayContentSource("before".encodeToByteArray(), canWrite = true).apply {
            replaceFailure = IllegalStateException(AppStrings.ui_test_file_editor_document_disk_space_is_insufficient)
        }
        val document = FileEditorDocument(source)

        document.initialize()
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        document.updateText("after")
        val save = document.save()

        assertTrue(save.isFailure)
        assertTrue(document.state.value.isDirty)
        assertEquals(AppStrings.ui_test_file_editor_document_disk_space_is_insufficient, document.state.value.error)

        source.replaceFailure = null
        val retry = document.save()

        assertTrue(retry.isSuccess)
        assertFalse(document.state.value.dirty)
        assertContentEquals("after".encodeToByteArray(), source.snapshot())
    }

    @Test
    fun thrownReadFailureIsReportedWithoutLeavingLoadingStateStuck() = runSuspendTest {
        val source = ByteArrayContentSource("content".encodeToByteArray(), canWrite = true).apply {
            readFailure = IllegalStateException(AppStrings.ui_test_file_editor_document_read_the_interruption)
            throwReadFailure = true
        }
        val document = FileEditorDocument(source)

        val result = document.initialize()

        assertTrue(result.isFailure)
        assertFalse(document.state.value.isLoading)
        assertEquals(AppStrings.ui_test_file_editor_document_read_the_interruption, document.state.value.error)
    }

    @Test
    fun thrownSaveFailureKeepsEditsAndAllowsRetry() = runSuspendTest {
        val source = ByteArrayContentSource("before".encodeToByteArray(), canWrite = true).apply {
            replaceFailure = IllegalStateException(AppStrings.ui_test_file_editor_document_insert_interrupt)
            throwReplaceFailure = true
        }
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("after").isSuccess)
        val failure = document.save()

        assertTrue(failure.isFailure)
        assertFalse(document.state.value.isSaving)
        assertTrue(document.state.value.dirty)
        assertEquals(AppStrings.ui_test_file_editor_document_insert_interrupt, document.state.value.error)

        source.replaceFailure = null
        assertTrue(document.save().isSuccess)
        assertContentEquals("after".encodeToByteArray(), source.snapshot())
    }

    @Test
    fun advancedSearchPublishesResultsAndJumpsUsingAbsoluteByteOffsets() = runSuspendTest {
        val source = ByteArrayContentSource(AppStrings.ui_test_file_editor_document_a_b_c.encodeToByteArray(), canWrite = false)
        val document = FileEditorDocument(source, pageSize = 4)

        assertTrue(document.initialize().isSuccess)
        assertTrue(
            document.startSearch(
                EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf(AppStrings.ui_test_editor_file_statistics_analyzer_chinese),
                    range = EditorSearchRange(0L, source.size),
                    encoding = EditorTextEncoding.UTF8,
                ),
            ).isSuccess,
        )
        while (document.state.value.isSearching) yield()

        val searchState = document.state.value
        assertTrue(searchState.searchCompleted)
        assertEquals(2L, searchState.searchTotalCount)
        assertEquals(listOf(1L, 5L), searchState.searchResults.map { it.startOffset })
        assertEquals(1f, searchState.searchProgress)

        assertTrue(document.goToSearchResult(searchState.searchResults.last()).isSuccess)
        assertEquals(1L, document.state.value.currentPage)
        assertEquals(5L, document.state.value.selectionStartOffset)
        assertEquals(8L, document.state.value.selectionEndOffsetExclusive)
        assertEquals(1..2, document.selectedTextRange())
    }

    @Test
    fun deletingAndClearingSearchHistoryUpdatesDocumentStateAndStore() = runSuspendTest {
        val source = ByteArrayContentSource("first second".encodeToByteArray(), canWrite = false)
        val store = EditorSearchHistoryStore(createInMemorySettings())
        val document = FileEditorDocument(source, searchHistoryStore = store)

        assertTrue(document.initialize().isSuccess)
        for (query in listOf("first", "second")) {
            assertTrue(
                document.startSearch(
                    EditorSearchRequest(
                        mode = EditorSearchMode.Text,
                        queries = listOf(query),
                        range = EditorSearchRange(0L, source.size),
                    ),
                ).isSuccess,
            )
            while (document.state.value.isSearching) yield()
        }
        val first = document.state.value.searchHistory.first { it.queries == listOf("first") }

        assertTrue(document.deleteSearchHistory(first).isSuccess)
        assertEquals(listOf(listOf("second")), document.state.value.searchHistory.map { it.queries })
        assertEquals(document.state.value.searchHistory, store.list())

        assertTrue(document.clearSearchHistory().isSuccess)
        assertTrue(document.state.value.searchHistory.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun cancellingSearchClearsTheRunningState() = runSuspendTest {
        val source = VirtualContentSource(size = 16L * 1024 * 1024)
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(
            document.startSearch(
                EditorSearchRequest(
                    mode = EditorSearchMode.Text,
                    queries = listOf("needle"),
                    range = EditorSearchRange(0L, source.size),
                    encoding = EditorTextEncoding.UTF8,
                ),
            ).isSuccess,
        )
        assertTrue(document.cancelSearch().isSuccess)

        assertFalse(document.state.value.isSearching)
        assertFalse(document.state.value.searchCompleted)
        assertEquals(null, document.state.value.searchError)
    }

    @Test
    fun statisticsPublishesBaseAndKeywordResultsThroughDocumentState() = runSuspendTest {
        val source = ByteArrayContentSource("cat\n\ncat".encodeToByteArray(), canWrite = false)
        val document = FileEditorDocument(source, pageSize = 3)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.startStatistics(listOf("cat")).isSuccess)
        while (document.state.value.isAnalyzing) yield()

        val result = document.state.value
        assertTrue(result.analysisCompleted)
        assertEquals(3L, result.statistics?.lineCount)
        assertEquals(1L, result.statistics?.blankLineCount)
        assertEquals(2L, result.keywordStatistics?.occurrences?.get("cat"))
        assertEquals(1f, result.analysisProgress)
    }

    @Test
    fun closeReleasesContentSource() = runSuspendTest {
        val source = ByteArrayContentSource(byteArrayOf(), canWrite = false)
        val document = FileEditorDocument(source)

        document.initialize()
        document.close()

        assertTrue(source.closed)
    }

    private class VirtualContentSource(
        override val size: Long,
        override val canWrite: Boolean = false,
    ) : FileEditorContentSource {
        val reads = mutableListOf<Pair<Long, Long>>()

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> {
            reads += startOffset to endOffsetExclusive
            return Result.success(ByteArray((endOffsetExclusive - startOffset).toInt()) { 0x41 })
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(IllegalStateException(AppStrings.ui_read_only))
    }

    private class ByteArrayContentSource(
        initial: ByteArray,
        override val canWrite: Boolean,
    ) : FileEditorContentSource {
        private var data = initial.copyOf()
        override val size: Long
            get() = data.size.toLong()

        val reads = mutableListOf<Pair<Long, Long>>()
        val writtenChunkSizes = mutableListOf<Int>()
        var replaceFailure: Throwable? = null
        var readFailure: Throwable? = null
        var throwReadFailure: Boolean = false
        var throwReplaceFailure: Boolean = false
        var replaceCalls: Int = 0
        var closed: Boolean = false
        private var revision: Long = 0L

        override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> = Result.success(
            FileEditorSourceSnapshot(
                size = size,
                revision = revision.toString(),
            )
        )

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> {
            reads += startOffset to endOffsetExclusive
            readFailure?.let { error ->
                if (throwReadFailure) throw error
                return Result.failure(error)
            }
            return runCatching {
                data.copyOfRange(startOffset.toInt(), endOffsetExclusive.toInt())
            }
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> {
            replaceCalls += 1
            replaceFailure?.let { error ->
                if (throwReplaceFailure) throw error
                return Result.failure(error)
            }
            return runCatching {
                val output = ArrayList<Byte>()
                var completed = 0L
                content.collect { chunk ->
                    writtenChunkSizes += chunk.size
                    chunk.forEach(output::add)
                    completed += chunk.size
                    onProgress(completed, newSize)
                }
                data = ByteArray(output.size) { index -> output[index] }
                revision += 1L
                assertEquals(newSize, data.size.toLong())
                if (newSize == 0L) onProgress(0L, 0L)
            }
        }

        override suspend fun close() {
            closed = true
        }

        fun snapshot(): ByteArray = data.copyOf()

        fun externalReplace(bytes: ByteArray) {
            data = bytes.copyOf()
            revision += 1L
        }
    }
}
