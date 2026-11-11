package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncModelDefectsTest {
    private fun task(id: Long = 1) = SyncTask(
        id, "Documents", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", ownerDeviceId = "local",
    )

    private fun file(path: String, size: Long = 10, updated: Long = 10_000, directory: Boolean = false) =
        FileSimpleInfo.pathFileSimpleInfo(path).withCopy(size = size, updatedDate = updated, isDirectory = directory)

    @Test
    fun messagesRemoveUrlUserInfoQueryCredentialsAndAuthorizationSecrets() {
        val message = sanitizeSyncMessage(
            "Unable to connect ftp://alice:secret@host/path?token=private&file=photo " +
                "https://bob:p%40ss@host/ password=hunter2; Authorization: Bearer eyJ.secret.token " +
                "authorization=Basic YWxpY2U6c2VjcmV0 access_token=access-secret&refresh_token=refresh-secret",
        )
        for (secret in listOf("alice", "secret", "bob", "p%40ss", "private", "hunter2", "eyJ", "YWxpY2U")) {
            assertFalse(message.contains(secret), message)
        }
        assertTrue(message.contains("ftp://***@host/path?token=***&file=photo"), message)
        assertTrue(message.contains("https://***@host/"), message)
        assertEquals("Bearer ***", sanitizeSyncMessage("Bearer token-value"))
        assertEquals("authorization: ***", sanitizeSyncMessage("authorization: legacy-secret"))
        assertEquals("Connection refused at host:21", sanitizeSyncMessage("Connection refused at host:21"))
        assertEquals("", sanitizeSyncMessage(null))
    }

    @Test
    fun timestampsUseTwoSecondToleranceAndUnknownTimesAreNotChanges() {
        val source = file("/source/a")
        assertFalse(syncFileChanged(source, file("/target/a", updated = 9_200)))
        assertFalse(syncFileChanged(source, file("/target/a", updated = 8_000)))
        assertTrue(syncFileChanged(source, file("/target/a", updated = 7_999)))
        assertFalse(syncFileChanged(source, file("/target/a", updated = 0)))
        assertFalse(syncFileChanged(source.copy(updatedDate = 0), file("/target/a")))
        assertTrue(syncFileChanged(source, file("/target/a", size = 11)))
        assertTrue(syncFileChanged(source, file("/target/a", directory = true)))
    }

    @Test
    fun reverseDetectionChecksBothEndpointsNestedPathsAndOwner() {
        val candidate = task()
        val reverse = task(2).copy(sourcePath = "/target/sub", targetPath = "/source")
        assertEquals(listOf(reverse), findReverseSyncTasks(candidate, listOf(candidate, reverse), separatorOf = { _, _ -> "/" }))
        assertTrue(findReverseSyncTasks(candidate, listOf(reverse.copy(ownerDeviceId = "other")), separatorOf = { _, _ -> "/" }).isEmpty())
        assertTrue(findReverseSyncTasks(candidate, listOf(reverse.copy(sourceRef = "other")), separatorOf = { _, _ -> "/" }).isEmpty())
        assertTrue(findReverseSyncTasks(candidate, listOf(reverse.copy(targetPath = "/source-other")), separatorOf = { _, _ -> "/" }).isEmpty())
        assertTrue(findReverseSyncTasks(candidate.copy(mode = SyncMode.Bidirectional), listOf(reverse), separatorOf = { _, _ -> "/" }).isEmpty())
        assertTrue(findReverseSyncTasks(candidate, listOf(reverse.copy(mode = SyncMode.Bidirectional)), separatorOf = { _, _ -> "/" }).isEmpty())
    }

    @Test
    fun snapshotRetainsLocalRunAndScheduleButTakesRemoteDefinition() {
        val local = task().copy(scheduleType = SyncScheduleType.Interval, intervalMinutes = 60, lastRunAt = 50,
            nextRunAt = 100, lastStatus = SyncRunStatus.Running, lastMessage = "running")
        val result = mergeSyncTaskSnapshot(listOf(local), listOf(local.copy(name = "Renamed", lastRunAt = 0,
            nextRunAt = 0, lastStatus = SyncRunStatus.Idle, lastMessage = "")), "local", 90)
        assertEquals(local.copy(name = "Renamed"), result.tasks.single())
        assertTrue(result.clearedRunTaskIds.isEmpty())
    }

    @Test
    fun snapshotClearsRemovedAndReassignedRunsAndRestoresChangedSchedules() {
        val local = task().copy(scheduleType = SyncScheduleType.Interval, intervalMinutes = 60, nextRunAt = 100)
        val result = mergeSyncTaskSnapshot(listOf(local, task(2), task(3)), listOf(
            local.copy(intervalMinutes = 120), task(2).copy(ownerDeviceId = "other"), task(4).copy(
                scheduleType = SyncScheduleType.Interval, intervalMinutes = 60)), "local", 1_000)
        assertEquals(setOf(3L), result.deletedTaskIds)
        assertEquals(setOf(2L, 3L), result.clearedRunTaskIds)
        assertEquals(listOf(7_201_000L, 0L, 3_601_000L), result.tasks.map { it.nextRunAt })
        assertEquals(0L, computeNextRun(local.copy(enabled = false), 1_000))
    }

    @Test
    fun directoryPlanKeepsUnchangedFilesAndReservesRenamePathsWithoutMutatingSnapshot() {
        val source = SyncSourceSnapshot(listOf(
            SyncSourceFileEntry("same", file("/source/same")),
            SyncSourceFileEntry("new.txt", file("/source/new.txt", size = 20)),
            SyncSourceFileEntry("new.txt", file("/source/other/new.txt", size = 30)),
        ), emptyList())
        val targetFiles = mutableMapOf("/target/same" to file("/target/same"), "/target/new.txt" to file("/target/new.txt"))
        val target = SyncTargetSnapshot(null, mutableMapOf(), targetFiles, mutableSetOf("/target"), targetFiles.keys.toMutableSet())
        val plan = planDirectorySync(source, target, task().copy(conflictPolicy = SyncConflictPolicy.Rename))
        assertEquals(listOf(SyncPlanAction.Unchanged, SyncPlanAction.RenameCopy, SyncPlanAction.RenameCopy), plan.map { it.action })
        assertEquals(listOf("/target/same", "/target/new(1).txt", "/target/new(2).txt"), plan.map { it.targetPath })
        assertEquals(setOf("/target/same", "/target/new.txt"), target.occupiedPaths)
        assertFalse(plan.last().targetExisted)
    }

    @Test
    fun directoryPlanHonorsConflictPoliciesAndIncludesOnlyRequiredDirectories() {
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("nested/file", file("/source/nested/file"))), listOf("empty", "nested"))
        val target = SyncTargetSnapshot(null, mutableMapOf(), mutableMapOf(), mutableSetOf("/target/nested/file"), mutableSetOf("/target/nested/file"))
        val replace = planDirectorySync(source, target, task().copy(includeEmptyDirectories = false))
        assertEquals(listOf(SyncPlanAction.CreateDirectory, SyncPlanAction.Copy), replace.map { it.action })
        assertTrue(replace.last().targetIsDirectory)
        assertTrue(replace.last().targetExisted)
        val skip = planDirectorySync(source, target, task().copy(conflictPolicy = SyncConflictPolicy.Skip, includeEmptyDirectories = false))
        assertEquals(listOf(SyncPlanAction.Skip), skip.map { it.action })
        assertTrue(planDirectorySync(source, target, task().copy(includeSubdirectories = false, includeEmptyDirectories = false)).isEmpty())
    }
}
