package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class TaskStateSchedulerTest {
    @Test
    fun pausedTaskAllowsNextQueuedTaskToRunAndResumeAfterCurrentTask() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val events = Channel<String>(Channel.UNLIMITED)

            val taskA = task(key = 101L)
            val taskB = task(key = 102L)
            val taskC = task(key = 103L)

            val allowAPauseCheck = CompletableDeferred<Unit>()
            val allowBFinish = CompletableDeferred<Unit>()
            val allowAFinish = CompletableDeferred<Unit>()

            taskState.addOrUpdate(taskA)
            taskState.registerTaskHandler(taskA) {
                events.send("A:start")
                allowAPauseCheck.await()
                if (!taskState.awaitIfPaused(taskA.key)) return@registerTaskHandler
                events.send("A:resume")
                allowAFinish.await()
                events.send("A:done")
            }

            assertEquals("A:start", events.receive())

            taskState.requestPause(taskA)
            allowAPauseCheck.complete(Unit)

            taskState.addOrUpdate(taskB)
            taskState.registerTaskHandler(taskB) {
                events.send("B:start")
                allowBFinish.await()
                events.send("B:done")
            }

            taskState.addOrUpdate(taskC)
            taskState.registerTaskHandler(taskC) {
                events.send("C:start")
                events.send("C:done")
            }

            assertEquals(StatusEnum.PAUSE, taskState.getTask(taskA.key)?.status)
            assertEquals("B:start", events.receive())

            taskState.requestResume(taskA)
            allowBFinish.complete(Unit)

            assertEquals("B:done", events.receive())
            assertEquals("A:resume", events.receive())

            allowAFinish.complete(Unit)

            assertEquals("A:done", events.receive())
            assertEquals("C:start", events.receive())
            assertEquals("C:done", events.receive())
        }
    }

    @Test
    fun resumedTasksPreserveResumeOrderAheadOfNormalQueue() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val events = Channel<String>(Channel.UNLIMITED)

            val taskA = task(key = 201L)
            val taskB = task(key = 202L)
            val taskC = task(key = 203L)

            val allowAPauseCheck = CompletableDeferred<Unit>()
            val allowBPauseCheck = CompletableDeferred<Unit>()
            val allowCFinish = CompletableDeferred<Unit>()

            taskState.addOrUpdate(taskA)
            taskState.registerTaskHandler(taskA) {
                events.send("A:start")
                allowAPauseCheck.await()
                if (!taskState.awaitIfPaused(taskA.key)) return@registerTaskHandler
                events.send("A:resume")
                events.send("A:done")
            }

            assertEquals("A:start", events.receive())

            taskState.requestPause(taskA)
            allowAPauseCheck.complete(Unit)

            taskState.addOrUpdate(taskB)
            taskState.registerTaskHandler(taskB) {
                events.send("B:start")
                allowBPauseCheck.await()
                if (!taskState.awaitIfPaused(taskB.key)) return@registerTaskHandler
                events.send("B:resume")
                events.send("B:done")
            }

            assertEquals("B:start", events.receive())

            taskState.requestPause(taskB)
            allowBPauseCheck.complete(Unit)

            taskState.addOrUpdate(taskC)
            taskState.registerTaskHandler(taskC) {
                events.send("C:start")
                allowCFinish.await()
                events.send("C:done")
            }

            assertEquals("C:start", events.receive())

            taskState.requestResume(taskA)
            taskState.requestResume(taskB)
            allowCFinish.complete(Unit)

            assertEquals("C:done", events.receive())
            assertEquals("A:resume", events.receive())
            assertEquals("A:done", events.receive())
            assertEquals("B:resume", events.receive())
            assertEquals("B:done", events.receive())
        }
    }

    @Test
    fun cancelStopsPausedRunningTaskWithoutWaitingForCooperativeCheckpoint() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val runningTask = task(key = 301L)
            val nextTask = task(key = 302L)
            val runningStarted = CompletableDeferred<Unit>()
            val runningCancelled = CompletableDeferred<Unit>()
            val nextStarted = CompletableDeferred<Unit>()

            taskState.addOrUpdate(runningTask)
            taskState.registerTaskHandler(runningTask) {
                runningStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    taskState.clearSignals(runningTask.key)
                } finally {
                    runningCancelled.complete(Unit)
                }
            }

            runningStarted.await()
            taskState.requestPause(taskState.getTask(runningTask.key)!!)
            assertEquals(StatusEnum.PAUSE, taskState.getTask(runningTask.key)?.status)

            taskState.addOrUpdate(nextTask)
            taskState.registerTaskHandler(nextTask) {
                nextStarted.complete(Unit)
            }

            taskState.requestCancel(taskState.getTask(runningTask.key)!!)

            runningCancelled.await()
            nextStarted.await()
            while (taskState.getTask(runningTask.key)?.status != StatusEnum.FAILURE) {
                delay(10.milliseconds)
            }

            assertEquals(StatusEnum.FAILURE, taskState.getTask(runningTask.key)?.status)
        }
    }

    @Test
    fun cancelDeletesPausedTaskWithoutRegisteredHandler() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val detachedTask = task(key = 401L).withStatus(StatusEnum.PAUSE)
            taskState.addOrUpdate(detachedTask)

            taskState.requestCancel(detachedTask)

            while (taskState.getTask(detachedTask.key) != null) {
                delay(10.milliseconds)
            }
            assertNull(taskState.getTask(detachedTask.key))
        }
    }

    private fun task(key: Long): Task {
        return Task(
            taskType = TaskType.Copy,
            key = key,
            status = StatusEnum.LOADING,
        )
    }
}
