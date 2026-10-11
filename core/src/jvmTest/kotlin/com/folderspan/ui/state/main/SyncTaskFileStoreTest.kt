package com.folderspan.ui.state.main

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTaskFileStoreTest {
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
