package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskResidualTest {
    private class Fixture(val root: Path) {
        val runtime = TestTaskRuntimePersistenceStore()
        val cleanup = TempTaskCleanupStore(root.resolve("journal").toString())
        val states = mutableListOf<TaskState>()

        fun newState(cleanupStore: TaskCleanupStore = cleanup): TaskState =
            TaskState(TestTaskFailureResultStore(), runtime, cleanupStore).also(states::add)

        fun task(key: Long = 72001L): Task = Task(
            taskType = TaskType.Copy,
            key = key,
            status = StatusEnum.LOADING,
            values = mapOf("path" to root.resolve("target.txt").toString()),
        )

        fun persistRecoveryQueue(task: Task) {
            runtime.saveMeta(
                TaskRuntimeMeta(
                    taskKey = task.key,
                    taskType = task.taskType,
                    source = TaskRuntimeEndpointRef(path = root.resolve("source.txt").toString()),
                    target = TaskRuntimeEndpointRef(path = root.resolve("target.txt").toString()),
                    currentPhase = TaskRuntimePhase.EXECUTING,
                    scanCompleted = true,
                    totalEntries = 1,
                    remainingEntries = 1,
                    createdAt = 1L,
                ),
            )
            runtime.appendQueueEntries(
                taskKey = task.key,
                stage = TaskRuntimeStage.COPY,
                category = TaskRuntimeQueueCategory.FILES,
                entries = listOf(
                    TaskRuntimeQueueEntry(
                        entryId = "partial",
                        stage = TaskRuntimeStage.COPY,
                        kind = TaskRuntimeEntryKind.FILE_COPY,
                        src = TaskRuntimeEndpointRef(path = root.resolve("source.txt").toString()),
                        dest = TaskRuntimeEndpointRef(path = root.resolve("target.txt").toString()),
                        order = 0,
                    ),
                ),
            )
        }

        fun recordCopyFailure(state: TaskState, task: Task) {
            state.recordRetryFailure(
                task = task,
                entry = TaskRetryEntry(
                    entryKey = "partial",
                    taskType = task.taskType,
                    stage = TaskRetryStage.COPY,
                    srcPath = root.resolve("source.txt").toString(),
                    destPath = root.resolve("partial.txt").toString(),
                    state = TaskRetryState.FAILURE,
                ),
                message = "injected transfer failure",
                fallback = "copy failed",
            )
        }

        suspend fun partial(state: TaskState, task: Task, id: String = "partial"): Path {
            var temporary: Path? = null
            state.withTaskArtifact(task, id, TaskRuntimeEndpointRef(path = root.resolve("$id.txt").toString())) { path ->
                temporary = Path.of(path)
                Files.writeString(temporary, "partial")
                Result.failure(IllegalStateException("injected transfer failure"))
            }
            return assertNotNull(temporary)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("folderspan-residual-test").toRealPath())
        try {
            withTimeout(10_000) { fixture.block() }
        } finally {
            fixture.states.forEach { state -> withTimeout(5_000) { state.stop() } }
            fixture.root.toFile().deleteRecursively()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(1)
    }

    private suspend fun awaitResidual(state: TaskState, taskKey: Long): TaskResidualSummary = withTimeout(5_000) {
        val summary = state.residuals.first { taskKey in it }.getValue(taskKey)
        state.residualNoticeKeys.first { taskKey in it }
        summary
    }

    @Test
    fun recoverableFailureReportsActualTemporaryPathAndSizeWithoutDeletingIt() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        persistRecoveryQueue(task)
        val temporary = CompletableDeferred<Path>()
        state.registerTaskHandler(task) {
            temporary.complete(partial(state, task))
            error("injected transfer failure")
        }

        val path = temporary.await()
        val summary = awaitResidual(state, task.key)
        assertEquals(task.key, summary.taskKey)
        assertEquals(path.toString(), summary.files.single().path)
        assertEquals(7L, summary.files.single().sizeBytes)
        assertFalse(summary.unknown)
        assertFalse(summary.recovered)
        assertTrue(Files.exists(path))
        assertTrue(state.canContinueTask(assertNotNull(state.getTask(task.key))))
        assertTrue(task.key in state.residualNoticeKeys.value)

        state.dismissResidualNotice(task.key)
        assertFalse(task.key in state.residualNoticeKeys.value)
        assertEquals(summary, state.residuals.value[task.key])

        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertFalse(Files.exists(path))
        assertNull(state.getTask(task.key))
        assertFalse(task.key in state.residuals.value)
        assertFalse(task.key in state.residualNoticeKeys.value)
    }

    @Test
    fun nonrecoverableFailureCleansTemporaryFileAndPreservesTheExistingTarget() = fixture {
        val state = newState()
        val task = task()
        val target = root.resolve("partial.txt")
        Files.writeString(target, "original")
        state.addOrUpdate(task)
        val temporary = CompletableDeferred<Path>()
        state.registerTaskHandler(task) {
            temporary.complete(partial(state, task))
            error("injected transfer failure")
        }

        val path = temporary.await()
        awaitCondition { state.getTask(task.key)?.status == StatusEnum.FAILURE && !Files.exists(path) }
        assertEquals("original", Files.readString(target))
        assertFalse(task.key in state.residuals.value)
        assertFalse(task.key in state.residualNoticeKeys.value)
        assertTrue(state.cleanupFailures.value.isEmpty())
    }

    @Test
    fun cancellationWaitsForWriterTeardownThenCleansOnlyThePartialFile() = fixture {
        val state = newState()
        val task = task()
        val existingTarget = root.resolve("partial.txt")
        val completedTarget = root.resolve("completed.txt")
        Files.writeString(existingTarget, "original")
        state.addOrUpdate(task)
        persistRecoveryQueue(task)
        val writing = CompletableDeferred<Path>()
        val tearingDown = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        state.registerTaskHandler(task) {
            state.withTaskArtifact(task, "completed", TaskRuntimeEndpointRef(path = completedTarget.toString())) { path ->
                Files.writeString(Path.of(path), "completed")
                Result.success(true)
            }
            state.withTaskArtifact(task, "partial", TaskRuntimeEndpointRef(path = existingTarget.toString())) { path ->
                val temporary = Path.of(path)
                Files.writeString(temporary, "partial")
                writing.complete(temporary)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        tearingDown.complete(Unit)
                        releaseWriter.await()
                        Files.writeString(temporary, "last buffered write")
                    }
                }
            }
        }

        val temporary = writing.await()
        try {
            state.requestCancel(task)
            tearingDown.await()
            assertTrue(Files.exists(temporary), "cleanup must wait until the writer has finished")
            assertEquals("original", Files.readString(existingTarget))
        } finally {
            releaseWriter.complete(Unit)
        }

        awaitCondition { state.getTask(task.key)?.status == StatusEnum.FAILURE && !Files.exists(temporary) }
        assertEquals("completed", Files.readString(completedTarget))
        assertEquals("original", Files.readString(existingTarget))
        assertFalse(runtime.hasPendingEntries(task.key))
        assertFalse(task.key in state.residuals.value)
        assertTrue(state.cleanupFailures.value.isEmpty())
    }

    @Test
    fun startupReportsActualRemnantsAndIgnoresAnAlreadyDeliveredTemporaryPath() = fixture {
        val task = task()
        val original = newState()
        original.addOrUpdate(task)
        persistRecoveryQueue(task)
        val partial = partial(original, task)
        val delivered = root.resolve("delivered.txt")
        val temporary = root.resolve("delivered.part")
        val identity = LocalTaskArtifacts.create(temporary.toString())
        Files.writeString(temporary, "complete")
        cleanup.save(
            task.key,
            TaskArtifact(
                entryId = "delivered",
                endpoint = TaskRuntimeEndpointRef(path = temporary.toString()),
                finalPath = delivered.toString(),
                purpose = TaskArtifactPurpose.TEMPORARY_FILE,
                identity = identity.identity,
                fingerprint = identity.fingerprint,
            ),
        )
        LocalTaskArtifacts.deliver(temporary.toString(), delivered.toString(), identity.identity)
        original.stop()

        val restored = newState()
        val summary = awaitResidual(restored, task.key)
        assertTrue(summary.recovered)
        assertFalse(summary.unknown)
        assertEquals(listOf(partial.toString()), summary.files.map { it.path })
        assertEquals(7L, summary.files.single().sizeBytes)
        assertTrue(task.key in restored.residualNoticeKeys.value)
        assertTrue(Files.exists(partial))
        assertEquals("complete", Files.readString(delivered))
        assertTrue(restored.canContinueTask(assertNotNull(restored.getTask(task.key))))
    }

    @Test
    fun externalContentAndIdentityChangesAreRetainedAndImmediatelyReportedAsCleanupFailures() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        val changedFiles = CompletableDeferred<List<Path>>()
        state.registerTaskHandler(task) {
            val changed = partial(state, task, "changed-content")
            val replaced = partial(state, task, "changed-identity")
            Files.writeString(changed, "user edited content")
            val replacement = root.resolve("replacement.txt")
            Files.writeString(replacement, "replacement content")
            Files.move(replacement, replaced, StandardCopyOption.REPLACE_EXISTING)
            changedFiles.complete(listOf(changed, replaced))
            error("injected transfer failure")
        }

        val paths = changedFiles.await()
        awaitCondition { state.cleanupFailures.value.count { it.taskKey == task.key } == 2 }
        val summary = awaitResidual(state, task.key)
        assertEquals(paths.map { it.toString() }.toSet(), summary.files.map { it.path }.toSet())
        assertEquals("user edited content", Files.readString(paths[0]))
        assertEquals("replacement content", Files.readString(paths[1]))
        assertNotNull(state.getTask(task.key))
        assertTrue(task.key in state.residualNoticeKeys.value)
        assertEquals(paths.map { it.toString() }.toSet(), state.cleanupFailures.value.mapNotNull { it.path }.toSet())
    }

    @Test
    fun unreadableStartupJournalReportsUnknownResidualsAndRecordDeletionClearsTheNotice() = fixture {
        val task = task().copy(status = StatusEnum.FAILURE)
        runtime.saveTaskSnapshot(task)
        val unreadable = object : TaskCleanupStore {
            override fun load(taskKey: Long): List<TaskArtifact>? = error("injected journal read failure")
            override fun save(taskKey: Long, artifact: TaskArtifact) = Unit
            override fun remove(taskKey: Long) = Unit
        }
        val state = newState(unreadable)

        val summary = awaitResidual(state, task.key)
        assertTrue(summary.unknown)
        assertTrue(summary.recovered)
        assertTrue(summary.files.isEmpty())
        assertTrue(task.key in state.residualNoticeKeys.value)

        assertTrue(state.deleteTaskRecordOnly(task.key))
        assertFalse(task.key in state.residuals.value)
        assertFalse(task.key in state.residualNoticeKeys.value)
        assertNull(state.getTask(task.key))
    }

    @Test
    fun applicationShutdownKeepsThePartialAndRecoveryQueueForTheNextStartup() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        persistRecoveryQueue(task)
        val writing = CompletableDeferred<Path>()
        state.registerTaskHandler(task) {
            state.withTaskArtifact(task, "partial", TaskRuntimeEndpointRef(path = root.resolve("partial.txt").toString())) { path ->
                val temporary = Path.of(path)
                Files.writeString(temporary, "partial")
                writing.complete(temporary)
                awaitCancellation()
            }
        }

        val temporary = writing.await()
        state.stop()
        assertTrue(Files.exists(temporary))
        assertTrue(runtime.hasPendingEntries(task.key))
        assertTrue(state.cleanupFailures.value.isEmpty())

        val restored = newState()
        val summary = awaitResidual(restored, task.key)
        assertTrue(summary.recovered)
        assertEquals(temporary.toString(), summary.files.single().path)
        assertTrue(restored.canContinueTask(assertNotNull(restored.getTask(task.key))))
    }

    @Test
    fun completedBatchWithFailedEntriesStillReportsRetainedPartialFiles() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        val temporary = CompletableDeferred<Path>()
        val completedTarget = root.resolve("completed.txt")
        state.registerTaskHandler(task) {
            temporary.complete(partial(state, task))
            recordCopyFailure(state, task)
            state.withTaskArtifact(task, "completed", TaskRuntimeEndpointRef(path = completedTarget.toString())) { path ->
                Files.writeString(Path.of(path), "completed")
                Result.success(true)
            }
            state.updateStatus(task, StatusEnum.SUCCESS)
        }

        val path = temporary.await()
        val summary = awaitResidual(state, task.key)
        assertEquals(StatusEnum.SUCCESS, state.getTask(task.key)?.status)
        assertTrue(state.hasFailedRetryEntries(assertNotNull(state.getTask(task.key))))
        assertEquals(path.toString(), summary.files.single().path)
        assertEquals(7L, summary.files.single().sizeBytes)
        assertTrue(Files.exists(path))
        assertEquals("completed", Files.readString(completedTarget))
    }

    @Test
    fun startupAlsoReportsResidualsOfAnAlreadyCompletedBatchWithFailedEntries() = fixture {
        val original = newState()
        val task = task()
        original.addOrUpdate(task)
        val temporary = partial(original, task)
        recordCopyFailure(original, task)
        original.updateStatus(task, StatusEnum.SUCCESS)
        original.stop()

        val restored = newState()
        val summary = awaitResidual(restored, task.key)
        assertEquals(StatusEnum.SUCCESS, restored.getTask(task.key)?.status)
        assertTrue(summary.recovered)
        assertEquals(temporary.toString(), summary.files.single().path)
        assertTrue(Files.exists(temporary))
    }

    @Test
    fun successfulRetryCleansAnOlderStagingArtifactBeforeRemovingTheTaskRecord() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        val oldStage = partial(state, task, "old-stage")
        val completedTarget = root.resolve("completed.txt")
        state.registerTaskHandler(task) {
            state.withTaskArtifact(task, "completed", TaskRuntimeEndpointRef(path = completedTarget.toString())) { path ->
                Files.writeString(Path.of(path), "completed")
                Result.success(true)
            }
            state.completeTask(task)
        }

        awaitCondition { state.getTask(task.key) == null }
        assertFalse(Files.exists(oldStage))
        assertEquals("completed", Files.readString(completedTarget))
        assertNull(cleanup.load(task.key))
        assertFalse(task.key in state.residuals.value)
    }

    @Test
    fun successfulRetryKeepsAVisibleRecordWhenAnOlderStageCannotBeSafelyCleaned() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        val oldStage = partial(state, task, "old-stage")
        Files.writeString(oldStage, "externally edited staging data")
        val completedTarget = root.resolve("completed.txt")
        state.registerTaskHandler(task) {
            state.withTaskArtifact(task, "completed", TaskRuntimeEndpointRef(path = completedTarget.toString())) { path ->
                Files.writeString(Path.of(path), "completed")
                Result.success(true)
            }
            state.completeTask(task)
        }

        val summary = awaitResidual(state, task.key)
        awaitCondition { state.cleanupFailures.value.any { it.taskKey == task.key } }
        assertEquals(StatusEnum.SUCCESS, state.getTask(task.key)?.status)
        assertEquals(oldStage.toString(), summary.files.single().path)
        assertEquals("externally edited staging data", Files.readString(oldStage))
        assertEquals("completed", Files.readString(completedTarget))
        assertNotNull(cleanup.load(task.key))
    }

    @Test
    fun retryAfterAutomaticCleanupCreatesAFreshTemporaryArtifact() = fixture {
        val state = newState()
        val task = task()
        state.addOrUpdate(task)
        val firstTemporary = CompletableDeferred<Path>()
        state.registerTaskHandler(task) {
            firstTemporary.complete(partial(state, task))
            error("injected transfer failure")
        }
        val oldPath = firstTemporary.await()
        awaitCondition { cleanup.load(task.key)?.singleOrNull()?.cleaned == true }

        awaitCondition { state.updateStatus(task, StatusEnum.LOADING) }
        val retryTemporary = CompletableDeferred<Path>()
        state.registerTaskHandler(task) {
            state.withTaskArtifact(task, "partial", TaskRuntimeEndpointRef(path = root.resolve("partial.txt").toString())) { path ->
                val temporary = Path.of(path)
                retryTemporary.complete(temporary)
                Files.writeString(temporary, "completed after retry")
                Result.success(true)
            }
            state.completeTask(task)
        }

        val retryPath = retryTemporary.await()
        awaitCondition { state.getTask(task.key) == null }
        assertFalse(oldPath == retryPath)
        assertFalse(Files.exists(oldPath))
        assertEquals("completed after retry", Files.readString(root.resolve("partial.txt")))
        assertNull(cleanup.load(task.key))
    }

    @Test
    fun unscheduledWriterCancellationDefersCleanupUntilItsOutermostFinallyFinishes() = fixture {
        coroutineScope {
            val state = newState()
            val task = task()
            val target = root.resolve("partial.txt")
            Files.writeString(target, "original")
            state.addOrUpdate(task)
            state.beginTaskOutputs(task)
            val writing = CompletableDeferred<Path>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writer = launch(Dispatchers.Default) {
                try {
                    state.withTaskArtifact(task, "partial", TaskRuntimeEndpointRef(path = target.toString())) { path ->
                        val temporary = Path.of(path)
                        Files.writeString(temporary, "partial")
                        writing.complete(temporary)
                        releaseWriter.await()
                        Files.writeString(temporary, "last buffered write")
                        Result.failure(CancellationException("user cancelled external copy"))
                    }
                } finally {
                    state.taskOutputsFinished(task)
                }
            }

            val temporary = writing.await()
            try {
                state.requestCancel(task)
                assertTrue(state.isTaskCancelled(task.key))
                assertEquals(StatusEnum.LOADING, state.getTask(task.key)?.status)
                assertTrue(Files.exists(temporary))
                assertFalse(assertNotNull(cleanup.load(task.key)).single().cleaned)
                assertTrue(state.cleanupFailures.value.isEmpty())
            } finally {
                releaseWriter.complete(Unit)
            }
            writer.join()

            awaitCondition { state.getTask(task.key)?.status == StatusEnum.FAILURE && !Files.exists(temporary) }
            assertEquals("original", Files.readString(target))
            assertTrue(state.cleanupFailures.value.isEmpty())
            assertFalse(task.key in state.residuals.value)
        }
    }

    @Test
    fun interruptedUnscheduledWriterKeepsItsPartialUntilTheNextStartupReportsIt() = fixture {
        coroutineScope {
            val original = newState()
            val task = task()
            original.addOrUpdate(task)
            original.beginTaskOutputs(task)
            val writing = CompletableDeferred<Path>()
            val writer = launch(Dispatchers.Default) {
                try {
                    original.withTaskArtifact(task, "partial", TaskRuntimeEndpointRef(path = root.resolve("partial.txt").toString())) { path ->
                        val temporary = Path.of(path)
                        Files.writeString(temporary, "partial")
                        writing.complete(temporary)
                        awaitCancellation()
                    }
                } catch (cancel: CancellationException) {
                    original.updateStatus(task, StatusEnum.FAILURE)
                    throw cancel
                } finally {
                    original.taskOutputsFinished(task, interrupted = !currentCoroutineContext().isActive)
                }
            }

            val temporary = writing.await()
            writer.cancelAndJoin()
            assertFalse(original.isTaskCancelled(task.key))
            assertFalse(assertNotNull(cleanup.load(task.key)).single().cleaned)
            original.stop()
            assertTrue(Files.exists(temporary))

            val restored = newState()
            val summary = awaitResidual(restored, task.key)
            assertTrue(summary.recovered)
            assertEquals(temporary.toString(), summary.files.single().path)
            assertEquals(7L, summary.files.single().sizeBytes)
            assertTrue(Files.exists(temporary))
        }
    }
}
