package com.folderspan.editor

import strings.AppStrings

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorLineIndexCacheTest {
    @Test
    fun buildsCachesAndReusesMatchingIndexWithoutRescanning() = runSuspendTest {
        val source = RecordingFileEditorContentSource("a\nb\nc".encodeToByteArray(), canWrite = false)
        val cache = RecordingLineIndexCache()
        val firstCoordinator = FileEditorTaskCoordinator(Dispatchers.Unconfined)
        val first = EditorBackgroundLineIndexer(
            source,
            EditorTextEncoding.UTF8,
            hasBom = false,
            cache,
            firstCoordinator,
        )

        val built = first.await().getOrThrow()
        assertEquals(3L, built.totalLineCount)
        assertEquals(EditorLineIndexBuildStatus.Complete, first.state.value.status)
        assertTrue(cache.entries.isNotEmpty())
        assertEquals(1, cache.cleanupCalls)
        val readsAfterBuild = source.reads.size

        val secondCoordinator = FileEditorTaskCoordinator(Dispatchers.Unconfined)
        val second = EditorBackgroundLineIndexer(
            source,
            EditorTextEncoding.UTF8,
            hasBom = false,
            cache,
            secondCoordinator,
        )
        val restored = second.await().getOrThrow()

        assertEquals(built, restored)
        assertTrue(second.state.value.loadedFromCache)
        assertEquals(readsAfterBuild, source.reads.size)
        firstCoordinator.close()
        secondCoordinator.close()
    }

    @Test
    fun rejectsStaleCachedIndexAndRebuildsIt() = runSuspendTest {
        val source = RecordingFileEditorContentSource("new\ncontent".encodeToByteArray(), canWrite = false)
        val snapshot = source.currentSnapshot().getOrThrow()
        val key = editorLineIndexCacheKey(snapshot, EditorTextEncoding.UTF8)
        val stale = EditorSparseLineIndex(
            sourceSnapshot = snapshot.copy(revision = "stale"),
            encoding = EditorTextEncoding.UTF8,
            checkpoints = listOf(EditorLineCheckpoint(1L, 0L)),
            indexedThroughOffset = snapshot.size,
            totalLineCount = 1L,
            complete = true,
        )
        val cache = RecordingLineIndexCache(mutableMapOf(key to stale.encode()))
        val coordinator = FileEditorTaskCoordinator(Dispatchers.Unconfined)
        val indexer = EditorBackgroundLineIndexer(
            source,
            EditorTextEncoding.UTF8,
            false,
            cache,
            coordinator,
        )

        val rebuilt = indexer.await().getOrThrow()

        assertEquals(2L, rebuilt.totalLineCount)
        assertEquals(listOf(key), cache.removed)
        assertTrue(source.reads.isNotEmpty())
        coordinator.close()
    }

    @Test
    fun cancellationStopsBackgroundIndexingAndPublishesCancelledState() = runSuspendTest {
        val source = SuspendedLineIndexSource()
        val cache = RecordingLineIndexCache()
        val coordinator = FileEditorTaskCoordinator()
        val indexer = EditorBackgroundLineIndexer(
            source,
            EditorTextEncoding.UTF8,
            false,
            cache,
            coordinator,
        )

        indexer.start()
        while (source.readCount == 0) yield()
        indexer.cancel()
        val result = indexer.await()

        assertIs<CancellationException>(result.exceptionOrNull())
        assertEquals(EditorLineIndexBuildStatus.Cancelled, indexer.state.value.status)
        assertTrue(cache.entries.isEmpty())
        coordinator.close()
    }

    private class RecordingLineIndexCache(
        val entries: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : EditorLineIndexCacheStore {
        val removed = mutableListOf<String>()
        var cleanupCalls = 0

        override suspend fun read(key: String): ByteArray? = entries[key]?.copyOf()

        override suspend fun write(key: String, bytes: ByteArray) {
            entries[key] = bytes.copyOf()
        }

        override suspend fun remove(key: String) {
            removed += key
            entries.remove(key)
        }

        override suspend fun cleanup(maxEntries: Int) {
            cleanupCalls += 1
        }
    }

    private class SuspendedLineIndexSource : FileEditorContentSource {
        override val size = 1024L
        override val canWrite = false
        var readCount = 0

        override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> {
            readCount += 1
            awaitCancellation()
        }

        override suspend fun replaceContent(
            newSize: Long,
            content: Flow<ByteArray>,
            expectedSnapshot: FileEditorSourceSnapshot?,
            onProgress: suspend (Long, Long) -> Unit,
        ): Result<Unit> = Result.failure(IllegalStateException(AppStrings.ui_read_only))
    }
}
