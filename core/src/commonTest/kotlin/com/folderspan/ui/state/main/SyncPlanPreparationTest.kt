package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPlanPreparationTest {
    private fun task(mode: SyncMode = SyncMode.Bidirectional) = SyncTask(1, "prepare", sourceType = SyncEndpointType.Local,
        sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target", mode = mode)
    private fun file(time: Long, size: Long = 10) = FileSimpleInfo.pathFileSimpleInfo("/a")
        .withCopy(name = "a", isDirectory = false, size = size, updatedDate = time)
    private fun ends(left: FileSimpleInfo, right: FileSimpleInfo) = listOf("source" to left, "target" to right).map { (id, file) ->
        SyncParticipantScan(id, id, SyncDirectoryScan(mapOf("a" to file), mapOf("a" to file), emptySet()))
    }

    @Test fun firstMergeReadsUncertainFilesWithoutWritingAndAccountsForComparedBytes() = runTest {
        for (different in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val progress = mutableListOf<Long>()
            val participants = ends(file(10_000), file(30_000))
            val result = prepareSyncPlan(task(), participants, "run", { emptyList() }, { id, file, onRead ->
                reads += id
                onRead(file.size.toInt())
                Result.success(file.syncEntryState().copy(contentSha256 = (if (different && id == "target") "b" else "a").repeat(64)))
            }, onComparisonRead = { progress += it }).getOrThrow()
            assertEquals(listOf("source", "target"), reads)
            assertEquals(listOf(10L, 20L), progress)
            assertEquals(20L, result.comparisonReadBytes)
            assertTrue(participants.all { it.verifiedStates.isEmpty() })
            if (different) {
                assertTrue(result.plan.paths.single().conflict)
                assertEquals(2, result.plan.operations.count { it.kind == SyncOperationKind.ConflictCopy })
            } else assertTrue(result.plan.operations.isEmpty())
        }
    }

    @Test fun failedComparisonKeepsConflictsAndUnknownMetadataProtected() = runTest {
        val failed = prepareSyncPlan(task(), ends(file(10_000), file(30_000)), "run", { emptyList() }, { _, _, _ ->
            Result.failure(UnsupportedOperationException("reader unavailable"))
        }).getOrThrow()
        assertTrue(failed.plan.paths.single().conflict)
        assertEquals(2, failed.plan.operations.count { it.kind == SyncOperationKind.ConflictCopy })
        for (proof in listOf("failed", "stale", "invalid", "valid")) {
            val prepared = prepareSyncPlan(task(SyncMode.Mirror), ends(file(0), file(30_000, 20)), "run", { emptyList() }, { _, file, onRead ->
                onRead(10)
                when (proof) {
                    "failed" -> Result.failure(IllegalStateException("read failed"))
                    "stale" -> Result.success(file.syncEntryState().copy(size = 11, contentSha256 = "a".repeat(64)))
                    "invalid" -> Result.success(file.syncEntryState().copy(contentSha256 = "not a digest"))
                    else -> Result.success(file.syncEntryState().copy(contentSha256 = "a".repeat(64)))
                }
            }).getOrThrow()
            assertEquals(proof == "valid", prepared.plan.operations.any { it.kind == SyncOperationKind.Overwrite }, proof)
            assertEquals(proof != "valid", prepared.plan.paths.single().skipped, proof)
        }
    }

    @Test fun baselineReadFailureNeverFallsBackToFirstMergeAndPagesAreBounded() = runTest {
        val pages = mutableListOf<Long>()
        var reads = 0
        val failed = prepareSyncPlan(task(), ends(file(10_000), file(30_000)), "run", { offset ->
            pages += offset
            if (offset == 500L) error("database unavailable")
            List(500) { SyncBaseline("old-$it", mapOf("source" to null, "target" to null)) }
        }, { _, file, _ -> reads++; Result.success(file.syncEntryState()) })
        assertTrue(failed.isFailure)
        assertEquals(listOf(0L, 500L), pages)
        assertEquals(0, reads)
        val duplicate = SyncBaseline("a", mapOf("source" to null, "target" to null))
        assertTrue(prepareSyncPlan(task(), emptyList(), "run", { listOf(duplicate, duplicate) }, { _, file, _ ->
            Result.success(file.syncEntryState())
        }).isFailure)
    }

    @Test fun changesCompareEachEndpointWithItsOwnBaselineAndUpdateSkipsBaselineAndContentReads() = runTest {
        val sourceBefore = file(10_000)
        val targetBefore = file(100_000)
        val changed = ends(file(20_000), targetBefore)
        val prepared = prepareSyncPlan(task(), changed, "run", {
            listOf(SyncBaseline("a", mapOf("source" to sourceBefore.syncEntryState(), "target" to targetBefore.syncEntryState())))
        }, { _, _, _ -> error("unchanged metadata needs no read") }).getOrThrow()
        assertFalse(prepared.plan.paths.single().conflict)
        assertEquals("source", prepared.plan.operations.single().sourceParticipant)
        assertEquals("target", prepared.plan.operations.single().participant)
        prepareSyncPlan(task(SyncMode.Update), changed, "run", { error("update must not read baselines") }, { _, _, _ ->
            error("update must not read file contents")
        }).getOrThrow()
    }

    @Test fun cancellationDuringBaselineOrContentReadsEscapesWithoutAPlan() = runTest {
        assertFailsWith<CancellationException> {
            prepareSyncPlan(task(), ends(file(0), file(0)), "run", { throw CancellationException("cancel") }, { _, _, _ -> error("not reached") })
        }
        assertFailsWith<CancellationException> {
            prepareSyncPlan(task(), ends(file(0), file(0)), "run", { emptyList() }, { _, _, _ -> Result.failure(CancellationException("cancel")) })
        }
    }
}
