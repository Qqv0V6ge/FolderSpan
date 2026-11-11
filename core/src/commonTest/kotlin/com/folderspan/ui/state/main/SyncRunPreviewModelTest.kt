package com.folderspan.ui.state.main

import kotlin.test.*

class SyncRunPreviewModelTest {
    private val task = SyncTask(1, "preview", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", mode = SyncMode.Mirror)
    private val file = SyncEntryState(false, 42, 10_000)
    private val directory = SyncEntryState(true, 4096, 10_000)

    @Test fun summaryAndPagesAgreeAndDirectoriesHaveNoFilePayloadBytes() {
        val operations = listOf(
            SyncPlannedOperation(SyncOperationKind.Copy, "target", "new", "source", expectedSource = file),
            SyncPlannedOperation(SyncOperationKind.CreateDirectory, "target", "directory", "source", expectedSource = directory, expectedTarget = file),
            SyncPlannedOperation(SyncOperationKind.Overwrite, "target", "overwrite", "source", expectedSource = file, expectedTarget = file.copy(size = 7)),
            SyncPlannedOperation(SyncOperationKind.Delete, "target", "old-directory", expectedTarget = directory),
            SyncPlannedOperation(SyncOperationKind.CleanEmptyDirectory, "target", "empty", expectedTarget = directory),
            SyncPlannedOperation(SyncOperationKind.CleanSource, "source", "clean", expectedTarget = file),
            SyncPlannedOperation(SyncOperationKind.ConflictCopy, "target", "conflict", "source", expectedSource = file),
        )
        val plan = SyncPlan(operations.map { SyncPathPlan(it.relativePath, emptyMap(), listOf(it), conflict = it.kind == SyncOperationKind.Overwrite) } +
            listOf(SyncPathPlan("skipped", emptyMap(), listOf(operations.first()), conflict = true, skipped = true),
                SyncPathPlan("unchanged", emptyMap(), emptyList()), SyncPathPlan("parent/child", emptyMap(), emptyList(), removedWithParent = "parent")))
        val recycled = mapOf(("target" to "old-directory") to 105L)
        val summary = plan.previewSummary(task, recycled)
        val rows = plan.previewItems(task, recycleDirectoryBytes = recycled).toList()
        assertEquals(rows.size, summary.itemCount)
        assertEquals(rows.count { it.conflict }, summary.conflictItemCount)
        assertEquals(rows.groupBy { it.action }.mapValues { (_, items) -> SyncPreviewCount(items.size, items.sumOf { it.size }) }, summary.counts)
        assertEquals(0L, summary.counts.getValue(SyncPreviewAction.CreateDirectory).bytes)
        assertEquals(0L, summary.counts.getValue(SyncPreviewAction.CleanEmptyDirectory).bytes)
        assertEquals(154L, summary.counts.getValue(SyncPreviewAction.Recycle).bytes)
        assertEquals(2, plan.previewItems(task, filter = SyncPreviewAction.Recycle, offset = 1).count())
        assertEquals(1, plan.previewItems(task, filter = SyncPreviewAction.Recycle, conflictsOnly = true).count())
    }

    @Test fun hundredThousandPathsSummarizeWithoutBuildingRowsAndOnlyRequestedPagesAreMaterialized() {
        val expected = mapOf("source" to file, "target" to file)
        val plan = SyncPlan(List(100_000) { index -> SyncPathPlan("file-$index", expected,
            listOf(SyncPlannedOperation(SyncOperationKind.Overwrite, "target", "file-$index", "source", expectedSource = file, expectedTarget = file)),
            conflict = index % 2 == 0) })
        val summary = plan.previewSummary(task)
        assertEquals(200_000, summary.itemCount)
        assertEquals(100_000, summary.conflictItemCount)
        assertEquals(SyncPreviewCount(100_000, 4_200_000), summary.counts.getValue(SyncPreviewAction.Overwrite))
        assertEquals(SyncPreviewCount(100_000, 4_200_000), summary.counts.getValue(SyncPreviewAction.Recycle))
        val lastPage = plan.previewItems(task, offset = 99_990, limit = 10, filter = SyncPreviewAction.Overwrite).toList()
        assertEquals((99_990 until 100_000).map { "file-$it" }, lastPage.map { it.relativePath })
        assertEquals(100, plan.previewItems(task, limit = 100, conflictsOnly = true).count())
        assertTrue(plan.previewItems(task, offset = 200_000, limit = 100).none())
    }

    @Test fun totalsSaturateWithoutOverflowAndDisabledRecycleHasNoExtraRows() {
        val large = file.copy(size = Long.MAX_VALUE)
        val plan = SyncPlan(List(2) { index -> SyncPathPlan("$index", emptyMap(), listOf(
            SyncPlannedOperation(SyncOperationKind.Overwrite, "target", "$index", "source", expectedSource = large, expectedTarget = large))) })
        val summary = plan.previewSummary(task.copy(recycleEnabled = false))
        assertEquals(2, summary.itemCount)
        assertEquals(Long.MAX_VALUE, summary.counts.getValue(SyncPreviewAction.Overwrite).bytes)
        assertFalse(SyncPreviewAction.Recycle in summary.counts)
    }
}
