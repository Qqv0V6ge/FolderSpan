package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileEditorSaveSafetyTest {
    @Test
    fun equalLengthChangesUseVersionCheckedRangeWrites() = runSuspendTest {
        val source = RecordingFileEditorContentSource("abcdef".encodeToByteArray())
        val document = FileEditorDocument(
            source = source,
            pageSize = 3,
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("aXc").isSuccess)
        assertTrue(document.goToPage(1L).isSuccess)
        assertTrue(document.updateText("dYf").isSuccess)

        assertTrue(document.save().isSuccess)
        assertContentEquals("aXcdYf".encodeToByteArray(), source.bytes())
        assertEquals(listOf(1L, 4L), source.writes.map { it.first })
        assertTrue(source.replacementChunkSizes.isEmpty())
        assertFalse(document.state.value.dirty)
    }

    @Test
    fun failedRangeWriteRollsBackEveryAttemptedRangeAndKeepsEdits() = runSuspendTest {
        val original = "abcdef".encodeToByteArray()
        val source = RecordingFileEditorContentSource(original).apply {
            failWriteAfterMutationAtCall = 2
        }
        val document = FileEditorDocument(
            source = source,
            pageSize = 3,
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("aXc").isSuccess)
        assertTrue(document.goToPage(1L).isSuccess)
        assertTrue(document.updateText("dYf").isSuccess)

        val failed = document.save()
        assertTrue(failed.isFailure)
        val error = failed.exceptionOrNull()
        assertTrue(error is EditorSaveRollbackException)
        assertEquals(null, error.rollbackFailure)
        assertContentEquals(original, source.bytes())
        assertTrue(document.state.value.dirty)

        assertTrue(document.save().isSuccess)
        assertContentEquals("aXcdYf".encodeToByteArray(), source.bytes())
    }

    @Test
    fun saveAsStreamsEditedDocumentWithoutReplacingSource() = runSuspendTest {
        val source = RecordingFileEditorContentSource("source".encodeToByteArray())
        val destination = RecordingFileEditorContentSource(byteArrayOf())
        val document = FileEditorDocument(source)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("saved copy").isSuccess)

        assertTrue(document.saveAs(destination).isSuccess)
        assertContentEquals("source".encodeToByteArray(), source.bytes())
        assertContentEquals("saved copy".encodeToByteArray(), destination.bytes())
        assertTrue(document.state.value.dirty)
        assertEquals(1f, document.state.value.progress)
    }

    @Test
    fun normalSaveCreatesCompleteBackupAndCleansRetention() = runSuspendTest {
        val backupStore = RecordingBackupStore()
        val source = RecordingFileEditorContentSource("original".encodeToByteArray())
        val document = FileEditorDocument(
            source = source,
            backupStore = backupStore,
            editorSessionKey = "local:test",
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("changed content").isSuccess)
        assertTrue(document.save().isSuccess)

        assertContentEquals("original".encodeToByteArray(), backupStore.completeBytes)
        assertTrue(backupStore.rangeBackups.isEmpty())
        assertEquals(1, backupStore.cleanupCalls)
    }

    @Test
    fun oversizedSaveBacksUpOnlyModifiedRanges() = runSuspendTest {
        val backupStore = RecordingBackupStore()
        val source = HugeWritableSource()
        val document = FileEditorDocument(
            source = source,
            pageSize = 4,
            backupStore = backupStore,
            editorSessionKey = "device:huge",
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.state.value.isOversized)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("ABAA").isSuccess)

        val preview = document.buildSavePreview().getOrThrow()
        assertEquals(EditorBackupMode.ModifiedRanges, preview.backupMode)
        assertEquals(1L, preview.estimatedBackupBytes)
        assertTrue(document.save().isSuccess)

        val range = backupStore.rangeBackups.single()
        assertEquals(1L, range.startOffset)
        assertContentEquals(byteArrayOf(0x41), range.originalBytes)
        assertEquals(1L, source.writes.single().first)
        assertContentEquals(byteArrayOf(0x42), source.writes.single().second)
        assertEquals(null, backupStore.completeBytes)
    }

    @Test
    fun insufficientBackupSpaceAbortsBeforeWriting() = runSuspendTest {
        val backupStore = RecordingBackupStore(available = 1L)
        val source = RecordingFileEditorContentSource("original".encodeToByteArray())
        val document = FileEditorDocument(
            source = source,
            backupStore = backupStore,
            editorSessionKey = "local:test",
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("changed").isSuccess)
        assertFalse(document.buildSavePreview().getOrThrow().hasEnoughBackupSpace)

        assertTrue(document.save().isFailure)
        assertContentEquals("original".encodeToByteArray(), source.bytes())
        assertTrue(source.writes.isEmpty())
        assertTrue(source.replacementChunkSizes.isEmpty())
        assertTrue(document.state.value.dirty)
    }

    @Test
    fun streamedVerificationFailurePreservesOriginalAndPendingEdits() = runSuspendTest {
        val original = "original".encodeToByteArray()
        val source = RecordingFileEditorContentSource(
            initial = original,
            supportsRangeWrite = false,
        ).apply {
            failReplacementVerification = true
        }
        val document = FileEditorDocument(source, pageSize = 8)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("changed content").isSuccess)

        assertTrue(document.save(backupEnabled = false).isFailure)
        assertContentEquals(original, source.bytes())
        assertTrue(document.state.value.dirty)
        assertEquals("changed content", document.state.value.text)

        source.failReplacementVerification = false
        assertTrue(document.save(backupEnabled = false).isSuccess)
        assertContentEquals("changed content".encodeToByteArray(), source.bytes())
    }

    @Test
    fun interruptedSaveAsUploadPreservesDestinationAndPendingEdits() = runSuspendTest {
        val source = RecordingFileEditorContentSource("source".encodeToByteArray())
        val destinationOriginal = "target".encodeToByteArray()
        val destination = RecordingFileEditorContentSource(destinationOriginal).apply {
            failReplacementAfterChunks = 2
        }
        val document = FileEditorDocument(source, pageSize = 8)

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.unlockEditing(confirmed = true).isSuccess)
        assertTrue(document.updateText("long saved copy").isSuccess)

        assertTrue(document.saveAs(destination).isFailure)
        assertContentEquals(destinationOriginal, destination.bytes())
        assertContentEquals("source".encodeToByteArray(), source.bytes())
        assertTrue(document.state.value.dirty)
        assertEquals("long saved copy", document.state.value.text)
    }

    private class RecordingBackupStore(
        private val available: Long = Long.MAX_VALUE,
    ) : EditorBackupStore {
        var completeBytes: ByteArray? = null
        val rangeBackups = mutableListOf<EditorBackupRange>()
        var cleanupCalls = 0

        override fun availableBytes(): Long = available

        override suspend fun createCompleteBackup(
            key: String,
            snapshot: FileEditorSourceSnapshot,
            size: Long,
            content: Flow<ByteArray>,
        ): Result<EditorBackupHandle> = runCatching {
            val output = ArrayList<Byte>()
            content.collect { chunk -> chunk.forEach(output::add) }
            completeBytes = ByteArray(output.size) { output[it] }
            check(completeBytes?.size?.toLong() == size)
            EditorBackupHandle(key, "memory://complete", EditorBackupMode.CompleteFile, size)
        }

        override suspend fun createRangeBackup(
            key: String,
            snapshot: FileEditorSourceSnapshot,
            ranges: List<EditorBackupRange>,
        ): Result<EditorBackupHandle> {
            rangeBackups += ranges.map { EditorBackupRange(it.startOffset, it.originalBytes.copyOf()) }
            return Result.success(
                EditorBackupHandle(
                    key,
                    "memory://ranges",
                    EditorBackupMode.ModifiedRanges,
                    ranges.sumOf { it.originalBytes.size.toLong() },
                )
            )
        }

        override suspend fun cleanup(maxEntries: Int) {
            cleanupCalls += 1
        }
    }

    private class HugeWritableSource : FileEditorContentSource {
        override val size: Long = FILE_EDITOR_OVERSIZED_THRESHOLD_BYTES
        override val canWrite: Boolean = true
        override val capabilities = FileEditorSourceCapabilities(
            supportsRangeWrite = true,
            supportsStreamedReplace = false,
        )
        private var revision = 1L
        val writes = mutableListOf<Pair<Long, ByteArray>>()

        override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> = Result.success(
            FileEditorSourceSnapshot(size = size, revision = revision.toString())
        )

        override suspend fun readRange(
            startOffset: Long,
            endOffsetExclusive: Long,
        ): Result<ByteArray> = Result.success(
            ByteArray((endOffsetExclusive - startOffset).toInt()) { 0x41 }
        )

        override suspend fun writeRange(
            startOffset: Long,
            data: ByteArray,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> {
            val actual = currentSnapshot().getOrThrow()
            if (expectedSnapshot != null && expectedSnapshot != actual) {
                return Result.failure(FileEditorSourceChangedException(expectedSnapshot, actual))
            }
            writes += startOffset to data.copyOf()
            revision += 1L
            onProgress(data.size.toLong(), data.size.toLong())
            return Result.success(Unit)
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_test_file_editor_content_source_fixtures_flow_replacement))
    }
}
