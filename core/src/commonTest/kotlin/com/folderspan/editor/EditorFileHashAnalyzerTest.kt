package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorFileHashAnalyzerTest {
    @Test
    fun calculatesAllHashesInOneBoundedPassWithProgress() = runSuspendTest {
        val source = RecordingFileEditorContentSource("123456789".encodeToByteArray(), canWrite = false)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = EditorFileHashAnalyzer(source, chunkSize = 3)
            .analyze { completed, total -> progress += completed to total }
            .getOrThrow()

        assertEquals("25f9e794323b453885f5181f1b624d0b", result.md5)
        assertEquals("f7c3bc1d808e04732adf679965ccc34ca7ae3441", result.sha1)
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", result.sha256)
        assertEquals("cbf43926", result.crc32)
        assertEquals(listOf(3L to 9L, 6L to 9L, 9L to 9L), progress)
        assertEquals(3, source.reads.size)
        assertTrue(source.reads.all { it.last - it.first + 1L <= 3L })
    }

    @Test
    fun rejectsHashesWhenSourceChangesDuringScan() = runSuspendTest {
        val source = ChangingSource("abcdefgh".encodeToByteArray())

        val error = EditorFileHashAnalyzer(source, chunkSize = 4).analyze().exceptionOrNull()

        assertIs<FileEditorSourceChangedException>(error)
    }

    @Test
    fun propagatesCancellationWithoutPublishingAHashResult() = runSuspendTest {
        val source = CancellingSource()

        assertFailsWith<CancellationException> {
            EditorFileHashAnalyzer(source, chunkSize = 2).analyze()
        }
        assertEquals(2, source.readCount)
    }

    private class ChangingSource(initial: ByteArray) : FileEditorContentSource {
        private var data = initial.copyOf()
        private var revision = 1L
        override val size: Long get() = data.size.toLong()
        override val canWrite: Boolean = false

        override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> =
            Result.success(FileEditorSourceSnapshot(size, revision, revision.toString()))

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> =
            runCatching {
                val result = data.copyOfRange(startOffset.toInt(), endOffsetExclusive.toInt())
                if (startOffset == 0L) revision += 1L
                result
            }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_read_only))
    }

    private class CancellingSource : FileEditorContentSource {
        override val size: Long = 4L
        override val canWrite: Boolean = false
        var readCount = 0

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> {
            readCount += 1
            if (readCount == 2) throw CancellationException(AppStrings.ui_test_editor_file_hash_analyzer_remove_hash_calculation)
            return Result.success(ByteArray((endOffsetExclusive - startOffset).toInt()))
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_read_only))
    }
}
