package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TaskConcurrentProgressTest {
    @Test fun byteObserversFollowArtifactAliasesAndStopOnDisposal(): Unit = runBlocking {
        val root = Files.createTempDirectory("copy-feedback")
        val state = TaskState(runtimeStore = TestTaskRuntimePersistenceStore(),
            cleanupStore = TempTaskCleanupStore(root.resolve("cleanup").toString()))
        try {
            val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
            state.addOrUpdate(task)
            state.saveTaskArtifact(task.key, TaskArtifact("feedback", TaskRuntimeEndpointRef(path = "/temporary"), finalPath = "/target"))
            val deltas = mutableListOf<Long>()
            val unsubscribe = state.observeRuntimeByteProgress(task.key, "/target") { deltas += it }
            try {
                for (bytes in listOf(0L, 10L, 10L, 5L, 20L, Long.MIN_VALUE)) state.recordRuntimeByteProgress(task, "/temporary", bytes)
                state.recordRuntimeByteProgress(task, "/unrelated", 100L)
                assertEquals(listOf(10L, 10L), deltas)
            } finally { unsubscribe() }
            state.recordRuntimeByteProgress(task, "/temporary", 30L)
            assertEquals(listOf(10L, 10L), deltas)
        } finally { state.stop(); root.toFile().deleteRecursively() }
    }

    @Test
    fun repeatedUnchangedProgressDoesNotExtendTheStallDeadline() {
        val state = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
        state.addOrUpdate(task)
        state.beginRuntimeByteMetrics(task, 1000)
        state.startRuntimeByteItem(task, "/target", 1000)
        state.putRuntimeByteProgress(task, "/target", 100)
        val progressedAt = state.lastRuntimeProgressAt(task.key, "/target")
        Thread.sleep(10)
        state.startRuntimeByteItem(task, "/target", 1000)
        state.putRuntimeByteProgress(task, "/target", 100)
        assertEquals(progressedAt, state.lastRuntimeProgressAt(task.key, "/target"))
        state.clearRuntimeByteItem(task, "/target")
        state.startRuntimeByteItem(task, "/target", 1000)
        state.putRuntimeByteProgress(task, "/target", 10)
        kotlin.test.assertTrue(state.lastRuntimeProgressAt(task.key, "/target")!! > progressedAt!!)
    }

    @Test
    fun parallelFilesKeepAllByteProgressAndIndependentWatchdogs() = runBlocking {
        val state = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
        state.addOrUpdate(task)
        state.beginRuntimeByteMetrics(task, 8000)
        (0 until 8).map { index -> launch(Dispatchers.Default) {
            val path = "/target/$index"
            state.startRuntimeByteItem(task, path, 1000)
            for (bytes in 1..100) state.putRuntimeByteProgress(task, path, bytes * 10L)
            assertNotNull(state.lastRuntimeProgressAt(task.key, path))
            state.finishRuntimeByteItem(task, path, 1000)
        } }.joinAll()
        assertEquals("8000", state.getTask(task.key)?.values?.get("__runtime_metric_completed"))
    }
}
