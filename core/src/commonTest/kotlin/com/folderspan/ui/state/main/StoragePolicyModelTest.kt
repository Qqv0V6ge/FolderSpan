package com.folderspan.ui.state.main

import com.folderspan.utils.ProtoBufCodec
import kotlin.io.encoding.Base64
import kotlin.test.*

class StoragePolicyModelTest {
    private fun destination(id: Long) = StorageDestination(ref = "account-$id", path = "/backup", networkEntryId = id)
    private fun metadata(id: Long, writable: Boolean = true, max: Long? = null, bytes: Long? = null) =
        StorageTargetMetadata(destination(id), true, writable, max, bytes, 10)

    @Test fun legacyTasksHaveNoEnabledPolicyOrGroup() {
        val task = ProtoBufCodec.decode<SyncTask>(Base64.decode(
            "CCoSC2xlZ2FjeS1zeW5jIAIqDmxlZ2FjeS1uZXR3b3JrMgUvZnJvbTgASgMvdG+wAQu4AQw="))
        assertFalse(task.backup)
        assertTrue(task.destinations.isEmpty())
        assertTrue(task.selectedSources.isEmpty())
        assertEquals(1L, task.policyVersion)
        assertEquals(task, ProtoBufCodec.decode<SyncTask>(ProtoBufCodec.encode(task)))
    }

    @Test fun rankingIsStableAndPreferenceCannotOverrideKnownLimits() {
        val candidates = listOf(metadata(3), metadata(2, max = 10), metadata(1))
        val ranked = recommendStorageTargets(candidates, listOf(20), listOf(2, 3), 10)
        assertEquals(listOf(3L, 1L, 2L), ranked.map { it.metadata.destination.networkEntryId })
        assertTrue(ranked.first().reasons.contains(StorageRecommendationReason.UnknownCapacity))
        assertFalse(ranked.last().eligible)
        assertTrue(StorageRecommendationReason.FileTooLarge in ranked.last().reasons)
        assertEquals(ranked, recommendStorageTargets(candidates.reversed(), listOf(20), listOf(2, 3), 10))
    }

    @Test fun spaceAndWriteChecksExcludeTargetsAndStaleDataIsExplicit() {
        val ranked = recommendStorageTargets(listOf(metadata(1, bytes = 15), metadata(2, writable = false)), listOf(10, 10), now = 90_000)
        assertTrue(ranked.none { it.eligible })
        assertTrue(ranked.all { StorageRecommendationReason.Stale in it.reasons })
        assertTrue(StorageRecommendationReason.InsufficientSpace in ranked.first().reasons)
    }

    @Test fun duplicatedAndNestedDestinationsAreRejected() {
        val first = destination(1)
        assertFailsWith<IllegalArgumentException> { requireDistinctStorageDestinations(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { requireDistinctStorageDestinations(listOf(first, first.copy(path = "/backup/child"))) }
        requireDistinctStorageDestinations(listOf(first, destination(2)))
    }

    @Test fun targetsRoundTripAndPartialSuccessIsNeverReportedAsWholeSuccess() {
        val targets = listOf(
            StorageTargetRun(destination(1), 1, SyncRunStatus.Success),
            StorageTargetRun(destination(2), 2, SyncRunStatus.Failure))
        val record = SyncRunRecord(1, 2, "manual", 1, 2, SyncRunStatus.PartialSuccess, 2, 1, 1, targets = targets)
        assertEquals(record, ProtoBufCodec.decode<SyncRunRecord>(ProtoBufCodec.encode(record)))
        assertEquals(SyncRunStatus.PartialSuccess, storageGroupStatus(targets))
        assertEquals(SyncRunStatus.Success, storageGroupStatus(targets.take(1)))
        assertEquals(SyncRunStatus.Queued, storageGroupStatus(targets + StorageTargetRun(destination(3), 3)))
    }
}
