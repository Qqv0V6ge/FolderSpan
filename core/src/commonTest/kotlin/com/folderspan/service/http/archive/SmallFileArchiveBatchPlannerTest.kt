package com.folderspan.service.http.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmallFileArchiveBatchPlannerTest {
    @Test
    fun keepsSingleSmallFileOnOriginalPath() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(TestItem("/src/a.txt", "a.txt", 12L)),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertTrue(selection.batches.isEmpty())
        assertEquals(listOf("a.txt"), selection.remainingItems.map { it.relativePath })
    }

    @Test
    fun groupsSmallFilesIntoArchiveBatch() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/a.txt", "a.txt", 12L),
                TestItem("/src/b.txt", "nested/b.txt", 32L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(1, selection.batches.size)
        assertTrue(selection.remainingItems.isEmpty())
        assertEquals(listOf("a.txt", "nested/b.txt"), selection.batches.first().requestEntries.map { it.relativePath })
    }

    @Test
    fun mixedLargeFilesStayOnOriginalPathWithoutSplittingSmallFileBatch() {
        val largeSize = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES + 1L
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/a.txt", "a.txt", 12L),
                TestItem("/src/large.bin", "large.bin", largeSize),
                TestItem("/src/b.txt", "b.txt", 32L),
                TestItem("/src/c.txt", "c.txt", 48L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(listOf("large.bin"), selection.remainingItems.map { it.relativePath })
        assertEquals(
            listOf("a.txt", "b.txt", "c.txt"),
            selection.batches.single().requestEntries.map { it.relativePath },
        )
    }

    @Test
    fun invalidEntryDoesNotSplitEligibleFilesAcrossNestedDirectories() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/a.txt", "folder-a/a.txt", 12L),
                TestItem("/src/invalid.txt", "../invalid.txt", 16L),
                TestItem("/src/b.txt", "folder-b/nested/b.txt", 32L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(listOf("../invalid.txt"), selection.remainingItems.map { it.relativePath })
        assertEquals(
            listOf("folder-a/a.txt", "folder-b/nested/b.txt"),
            selection.batches.single().requestEntries.map { it.relativePath },
        )
    }

    @Test
    fun includesFilesUpToOneMiBInArchiveBatch() {
        val oneMiB = 1024L * 1024L
        assertEquals(oneMiB, FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES)

        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/a.bin", "a.bin", oneMiB),
                TestItem("/src/b.bin", "b.bin", 1L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertTrue(selection.remainingItems.isEmpty())
        assertEquals(listOf("a.bin", "b.bin"), selection.batches.single().requestEntries.map { it.relativePath })
    }

    @Test
    fun includesZeroByteFilesInArchiveBatch() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/empty.txt", "empty.txt", 0L),
                TestItem("/src/a.txt", "a.txt", 12L),
                TestItem("/src/b.txt", "b.txt", 32L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertTrue(selection.remainingItems.isEmpty())
        assertEquals(listOf("empty.txt", "a.txt", "b.txt"), selection.batches.single().requestEntries.map { it.relativePath })
    }

    @Test
    fun leavesNegativeSizeFilesOnOriginalPath() {
        val selection = selectSmallFileArchiveBatches(
            items = listOf(
                TestItem("/src/unknown.txt", "unknown.txt", -1L),
                TestItem("/src/a.txt", "a.txt", 12L),
                TestItem("/src/b.txt", "b.txt", 32L),
            ),
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(listOf("unknown.txt"), selection.remainingItems.map { it.relativePath })
        assertEquals(listOf("a.txt", "b.txt"), selection.batches.single().requestEntries.map { it.relativePath })
    }

    @Test
    fun splitsBatchesByEntryLimit() {
        val items = List(FOLDER_SPAN_ARCHIVE_MAX_ENTRIES + 2) { index ->
            TestItem("/src/$index.txt", "$index.txt", 1L)
        }

        val selection = selectSmallFileArchiveBatches(
            items = items,
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
            maxBatchEntries = FOLDER_SPAN_ARCHIVE_MAX_ENTRIES,
        )

        assertEquals(2, selection.batches.size)
        assertEquals(FOLDER_SPAN_ARCHIVE_MAX_ENTRIES, selection.batches.first().items.size)
        assertEquals(2, selection.batches.last().items.size)
        assertTrue(selection.remainingItems.isEmpty())
    }

    @Test
    fun splitsBatchesByTargetEntryLimitForParallelArchiveStreams() {
        val items = List(FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES + 2) { index ->
            TestItem("/src/$index.txt", "$index.txt", 1L)
        }

        val selection = selectSmallFileArchiveBatches(
            items = items,
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(2, selection.batches.size)
        assertEquals(FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES, selection.batches.first().items.size)
        assertEquals(2, selection.batches.last().items.size)
        assertTrue(selection.remainingItems.isEmpty())
    }

    @Test
    fun splitsBatchesByTargetPayloadForParallelArchiveStreams() {
        val fileSize = FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES
        val entriesPerBatch = (FOLDER_SPAN_ARCHIVE_TARGET_BATCH_PAYLOAD_BYTES / fileSize).toInt()
        val items = List(entriesPerBatch + 2) { index ->
            TestItem("/src/$index.txt", "$index.txt", fileSize)
        }

        val selection = selectSmallFileArchiveBatches(
            items = items,
            sourcePath = { it.sourcePath },
            relativePath = { it.relativePath },
            size = { it.size },
        )

        assertEquals(2, selection.batches.size)
        assertEquals(entriesPerBatch, selection.batches.first().items.size)
        assertEquals(2, selection.batches.last().items.size)
        assertTrue(selection.remainingItems.isEmpty())
    }

    private data class TestItem(
        val sourcePath: String,
        val relativePath: String,
        val size: Long,
    )
}
