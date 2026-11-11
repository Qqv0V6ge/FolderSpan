package com.folderspan.ui.state.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRunDetailsTest {
    private val file = SyncEntryState(false, 10, 1000)
    private fun recovery(path: String, action: SyncRecoveryAction = SyncRecoveryAction.Delete) = SyncFileRecovery(
        path, 1, "definition", "device", "group-$path", "target", SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target"),
        path, "/target/$path", "/target/tmp-$path", "/target/.folderspan-recycle/$path", file, null, true,
        action = action, phase = SyncRecoveryPhase.Verified)

    @Test fun fiveDeletedFilesKeepFiveRecycleGroupsAndActualPaths() {
        val rows = (1..5).flatMap { index ->
            val path = "file-$index"
            syncRunPathItems(SyncPathPlan(path, emptyMap(), listOf(SyncPlannedOperation(SyncOperationKind.Delete, "target", path))),
                listOf(recovery(path)), emptyMap())
        }
        assertEquals(5, rows.count { it.kind == SyncRunItemKind.Delete })
        val recycled = rows.filter { it.kind == SyncRunItemKind.Recycle }
        assertEquals(5, recycled.map { it.recoveryGroup }.distinct().size)
        assertEquals((1..5).map { "/target/.folderspan-recycle/file-$it" }, recycled.map { it.recoveryLocations.single().path })
        assertTrue(rows.all { it.success })
    }

    @Test fun conflictReportsActualChosenVersionAndBackupCleanupHasNoRecycleWithoutOldCopy() {
        val path = SyncPathPlan("a", emptyMap(), listOf(SyncPlannedOperation(SyncOperationKind.Overwrite, "target", "a", sourceParticipant = "source")), conflict = true)
        val rows = syncRunPathItems(path, listOf(recovery("a", SyncRecoveryAction.Overwrite)), mapOf("source" to "Chosen source"))
        assertTrue(rows.first().message.contains("Chosen source"))
        assertEquals(listOf(SyncRunItemKind.Conflict, SyncRunItemKind.Overwrite, SyncRunItemKind.Recycle), rows.map { it.kind })
        val clean = syncRunPathItems(path.copy(conflict = false, operations = listOf(SyncPlannedOperation(SyncOperationKind.CleanSource, "source", "a"))),
            listOf(recovery("a").copy(participant = "source", recycleEnabled = false, oldPath = "")), emptyMap())
        assertEquals(listOf(SyncRunItemKind.CleanSource), clean.map { it.kind })
    }

    @Test fun failedPlacementListsOldTemporaryAndFinalPathsWithoutReportingSuccess() {
        val record = recovery("a").copy(phase = SyncRecoveryPhase.OldPreserved)
        val row = record.runRecoveryItem()
        assertFalse(row.success)
        assertEquals("group-a", row.recoveryGroup)
        assertEquals(listOf(record.oldPath, record.temporaryPath, record.finalPath), row.recoveryLocations.map { it.path })
        val rows = syncRunPathItems(SyncPathPlan("a", emptyMap(), listOf(SyncPlannedOperation(SyncOperationKind.Delete, "target", "a"))),
            listOf(record), emptyMap(), success = false, message = "placement failed")
        assertTrue(rows.none { it.success })
        assertEquals(listOf(SyncRunItemKind.Delete, SyncRunItemKind.Recovery), rows.map { it.kind })
    }
}
