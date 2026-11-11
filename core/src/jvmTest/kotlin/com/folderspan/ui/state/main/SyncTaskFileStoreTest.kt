package com.folderspan.ui.state.main

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTaskFileStoreTest {
    @Test fun runHistoryKeepsItsModeAndChosenParticipantsAfterTheTaskChanges() {
        val root = Files.createTempDirectory("sync-participant-history")
        try {
            val store = TempSyncTaskStore(root.absolutePathString())
            val task = store.saveTask(syncTask(name = "group", updatedAt = 100).withMode(SyncMode.Mirror))
            val record = SyncRunRecord(1, task.id, "manual", 10, 20, SyncRunStatus.Success, 1, 1, 0,
                policySnapshot = task, participants = listOf(
                    SyncRunParticipant("source", "Source", SyncEndpointType.Local, "", listOf("/selected")),
                    SyncRunParticipant("target-0", "Chosen archive", SyncEndpointType.Network, "chosen", listOf("/archive"))))
            store.appendRun(record)
            store.saveTask(task.withMode(SyncMode.Bidirectional))
            assertEquals(record, store.loadRuns(task.id).single())
            assertEquals(SyncMode.Mirror, store.loadRuns(task.id).single().policySnapshot!!.mode)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun saveTaskAssignsIncrementingIdsAndUpdatesExistingTask() {
        val store = TempSyncTaskStore(Files.createTempDirectory("sync-task-store").absolutePathString())

        val first = store.saveTask(syncTask(name = "first", updatedAt = 100))
        val second = store.saveTask(syncTask(name = "second", updatedAt = 200))
        val renamedFirst = store.saveTask(first.copy(name = "first-renamed", updatedAt = 300))

        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
        assertEquals(1L, renamedFirst.id)
        assertEquals(
            listOf("first-renamed", "second"),
            store.loadTasks().map { task -> task.name },
        )
    }

    @Test
    fun loadTasksOrdersByUpdatedAtThenIdDescending() {
        val store = TempSyncTaskStore(Files.createTempDirectory("sync-task-store-order").absolutePathString())

        val older = store.saveTask(syncTask(name = "older", updatedAt = 100))
        val sameTimestampLowId = store.saveTask(syncTask(name = "same-low", updatedAt = 300))
        val newest = store.saveTask(syncTask(name = "newest", updatedAt = 500))
        val sameTimestampHighId = store.saveTask(syncTask(name = "same-high", updatedAt = 300))

        assertEquals(
            listOf(newest.id, sameTimestampHighId.id, sameTimestampLowId.id, older.id),
            store.loadTasks().map { task -> task.id },
        )
    }

    @Test
    fun deleteTaskRemovesTaskAndRunHistory() {
        val store = TempSyncTaskStore(Files.createTempDirectory("sync-task-store-delete").absolutePathString())
        val task = store.saveTask(syncTask(name = "delete-me", updatedAt = 100))

        store.appendRun(
            SyncRunRecord(
                runId = 1,
                taskId = task.id,
                trigger = "manual",
                startedAt = 10,
                endedAt = 20,
                status = SyncRunStatus.Success,
                totalCount = 1,
                successCount = 1,
                failureCount = 0,
            )
        )
        store.deleteTask(task.id)

        assertTrue(store.loadTasks().isEmpty())
        assertTrue(store.loadRuns(task.id).isEmpty())
    }

    @Test
    fun saveTaskPersistsProtobufPayloadInsteadOfJsonFile() {
        val root = Files.createTempDirectory("sync-task-store-protobuf")
        val store = TempSyncTaskStore(root.absolutePathString())

        store.saveTask(syncTask(name = "protobuf", updatedAt = 100))

        assertFalse(root.resolve("tasks/task-1.json").exists())
        val payload = root.resolve("tasks/task-1.pb64").readText()
        assertFalse(payload.trimStart().startsWith("{"))
        assertEquals(listOf("protobuf"), store.loadTasks().map { task -> task.name })
    }

    @Test
    fun appendRunKeepsPersistedHistoryWithinLimit() {
        val root = Files.createTempDirectory("sync-task-store-runs-limit")
        val store = TempSyncTaskStore(root.absolutePathString())
        val task = store.saveTask(syncTask(name = "limited-runs", updatedAt = 100))

        repeat(35) { index ->
            store.appendRun(syncRun(taskId = task.id, runId = index + 1L))
        }

        val persistedLines = root.resolve("runs/${task.id}.pb64l").readLines()
        assertEquals(30, persistedLines.size)
        assertEquals((35L downTo 6L).toList(), store.loadRuns(task.id).map { item -> item.runId })
    }

    @Test
    fun groupProgressKeepsOneLatestDurableReceiptPerRun() {
        val root = Files.createTempDirectory("sync-group-receipt")
        try {
            val store = TempSyncTaskStore(root.absolutePathString())
            val original = syncRun(7, 100).copy(status = SyncRunStatus.Running)
            store.appendRun(original)
            store.appendRun(original.copy(status = SyncRunStatus.PartialSuccess, successCount = 2, failureCount = 1))
            val restored = TempSyncTaskStore(root.absolutePathString()).loadRuns(7)
            assertEquals(1, restored.size)
            assertEquals(SyncRunStatus.PartialSuccess, restored.single().status)
            assertEquals(2, restored.single().successCount)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun failedItemsAreBoundedLazyAndRemovedWithHistory() {
        val root = Files.createTempDirectory("sync-failed-items")
        try {
            val store = TempSyncTaskStore(root.absolutePathString())
            val task = store.saveTask(syncTask("failures", 100))
            val failures = List(10_002) { SyncFailureItem("file-$it", false, "failed") }
            assertEquals(2, store.saveFailedItems(task.id, 1, failures))
            assertEquals(failures.take(10_000), store.loadFailedItems(task.id, 1))
            store.appendRun(syncRun(task.id, 1))
            val failurePath = root.resolve("runs/${task.id}/1.failed.pb64l")
            Files.writeString(failurePath, "invalid payload")
            assertEquals(listOf(task), store.loadTasks())
            assertEquals(1, store.loadRuns(task.id).size)
            assertNull(store.loadFailedItems(task.id, 1))
            repeat(30) { store.appendRun(syncRun(task.id, it + 2L)) }
            assertFalse(failurePath.exists())
            store.saveFailedItems(task.id, 31, failures.take(1))
            store.deleteRuns(task.id)
            assertFalse(root.resolve("runs/${task.id}").exists())
            store.saveFailedItems(task.id, 32, failures.take(1))
            store.deleteTask(task.id)
            assertFalse(root.resolve("runs/${task.id}").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun syncTask(name: String, updatedAt: Long): SyncTask {
        return SyncTask(
            id = 0,
            name = name,
            sourceType = SyncEndpointType.Local,
            sourceRef = "",
            sourcePath = "/source",
            targetType = SyncEndpointType.Local,
            targetRef = "",
            targetPath = "/target",
            conflictPolicy = SyncConflictPolicy.Replace,
            scheduleType = SyncScheduleType.Manual,
            lastStatus = SyncRunStatus.Idle,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )
    }

    private fun syncRun(taskId: Long, runId: Long): SyncRunRecord {
        return SyncRunRecord(
            runId = runId,
            taskId = taskId,
            trigger = "manual",
            startedAt = runId,
            endedAt = runId,
            status = SyncRunStatus.Success,
            totalCount = 1,
            successCount = 1,
            failureCount = 0,
        )
    }
}
