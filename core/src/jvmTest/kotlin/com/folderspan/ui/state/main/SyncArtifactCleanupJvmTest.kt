package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.data.main.Local
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncArtifactCleanupJvmTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val generation = "1b62d672-4189-443a-a3c0-a3a0287a0fc9"
    private fun task() = SyncTask(7, "cleanup", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", ownerDeviceId = "samefirst-owner-a")

    private suspend fun fixture(block: suspend (Path, SyncRecoveryEndpoint, SyncRecoveryEndpointOperations) -> Unit) {
        val root = Files.createTempDirectory("sync-artifact-cleanup")
        try {
            val endpoint = SyncRecoveryEndpoint(SyncEndpointType.Local, "", root.toString())
            val operation = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
            block(root, endpoint, SyncRecoveryEndpointOperations(SyncEndpointFileOperations(), operation, mapOf(endpoint to Local())))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun group(root: Path, task: SyncTask, created: Long, name: String = generation): Path =
        Files.createDirectories(root.resolve(".folderspan-recycle/${syncRecycleOwnerKey(task)}/${task.id}/$created-$name"))

    @Test fun retentionOnlyVisitsThisDeviceAndTaskAndKeepsJournalReferences(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val task = task()
            val foreignOwner = task.copy(ownerDeviceId = "samefirst-owner-b")
            assertNotEquals(syncTaskKey(task), syncTaskKey(foreignOwner))
            val expired = group(root, task, now - 31 * day)
            val nested = Files.createDirectories(expired.resolve("photos/nested"))
            Files.writeString(nested.resolve("old.txt"), "expired version")
            val boundary = group(root, task, now - 30 * day)
            val recent = group(root, task, now - day)
            val pending = group(root, task, now - 40 * day)
            val pendingFile = Files.writeString(pending.resolve("a.txt"), "recovery content")
            val otherTask = group(root, task.copy(id = 8), now - 90 * day)
            val otherOwner = group(root, foreignOwner, now - 90 * day)
            val unrecognized = Files.createDirectory(expired.parent.resolve("unknown-format"))
            val record = SyncFileRecovery("pending", task.id, "definition", task.ownerDeviceId, "group", "target", endpoint,
                "a.txt", root.resolve("a.txt").toString(), "", pendingFile.toString(), null, null, true)
            var reads = 0
            val removed = cleanupSyncTaskArtifacts(task, mapOf(endpoint to "/"), io, now, { reads++; listOf(record) })
            assertEquals(listOf(expired.toString()), removed)
            assertTrue(reads >= 2)
            assertFalse(Files.exists(expired))
            for (kept in listOf(boundary, recent, pending, otherTask, otherOwner, unrecognized)) assertTrue(Files.isDirectory(kept))
            assertEquals("recovery content", Files.readString(pendingFile))
        }
    }

    @Test fun onlyExpiredUnreferencedNewStagesCanBeCleaned(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val task = task()
            fun stage(owner: SyncTask, created: Long, suffix: String = "new") =
                Files.writeString(root.resolve(".folderspan-tmp-${syncTaskKey(owner)}-$created-$generation-$suffix"), "staged bytes")
            val expired = stage(task, now - 2 * day)
            val recent = stage(task, now - 60_000)
            Files.setLastModifiedTime(recent, FileTime.fromMillis(1))
            val pending = stage(task, now - 3 * day)
            val oldVersion = stage(task, now - 5 * day, "old")
            val foreign = stage(task.copy(id = 8), now - 5 * day)
            val legacy = Files.writeString(root.resolve(".folderspan-tmp-${syncTaskKey(task)}-$generation-new"), "unclassified")
            val record = SyncFileRecovery("pending", task.id, "definition", task.ownerDeviceId, "group", "target", endpoint,
                "a", root.resolve("a").toString(), pending.toString(), "", null, null, true)
            assertEquals(listOf(expired.toString()), cleanupSyncTaskArtifacts(task, mapOf(endpoint to "/"), io, now, { listOf(record) }))
            for (kept in listOf(recent, pending, oldVersion, foreign, legacy)) assertTrue(Files.exists(kept))
        }
    }

    @Test fun linkedGroupAndLinkedContentNeverDeleteOutsideTheNamespace(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val task = task()
            val outside = Files.createDirectory(root.resolve("outside"))
            val original = Files.writeString(outside.resolve("keep"), "user data")
            val own = Files.createDirectories(root.resolve(".folderspan-recycle/${syncRecycleOwnerKey(task)}/${task.id}"))
            val linkedGroup = Files.createSymbolicLink(own.resolve("${now - 40 * day}-$generation"), outside)
            assertTrue(cleanupSyncTaskArtifacts(task, mapOf(endpoint to "/"), io, now, { emptyList() }).isEmpty())
            assertTrue(Files.isSymbolicLink(linkedGroup))
            Files.delete(linkedGroup)
            val group = group(root, task, now - 40 * day)
            Files.createSymbolicLink(group.resolve("escape"), outside)
            assertFailsWith<IllegalStateException> { cleanupSyncTaskArtifacts(task, mapOf(endpoint to "/"), io, now, { emptyList() }) }
            assertTrue(Files.isDirectory(group))
            assertEquals("user data", Files.readString(original))
        }
    }
}
