package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class TaskStateCompletionCleanupTest {
    @Test
    fun successfulTaskCompletionClearsRuntimeStateButKeepsPath() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val task = Task(
                taskType = TaskType.Copy,
                key = 401L,
                status = StatusEnum.LOADING,
                values = mapOf("path" to "/target"),
            )
            taskState.addOrUpdate(task)
            taskState.replaceRetryEntries(
                task,
                listOf(
                    TaskRetryEntry(
                        entryKey = "retry-1",
                        taskType = TaskType.Copy,
                        stage = TaskRetryStage.COPY,
                        srcPath = "/source/file.txt",
                        srcProtocol = task.protocol,
                        srcProtocolId = task.protocolId,
                        destPath = "/target/file.txt",
                        destProtocol = task.protocol,
                        destProtocolId = task.protocolId,
                        state = TaskRetryState.FAILURE,
                        failureMessage = AppStrings.ui_test_task_state_completion_cleanup_old_failure,
                        isDirectory = false,
                        size = 1L,
                    )
                )
            )

            taskState.registerTaskHandler(task) {
                taskState.beginRuntimeByteMetrics(task, 1024L)
                taskState.startRuntimeByteItem(task, "/target/file.txt", 1024L)
                taskState.putValues(
                    task,
                    listOf(
                        "progressCur" to "3",
                        "progressMax" to "10",
                        "__runtime_phase" to "execute",
                        "__runtime_stage" to TaskRuntimeStage.COPY.name,
                        "__runtime_queue_category" to TaskRuntimeQueueCategory.FILES.name,
                        "__runtime_queue_file" to "000001.pb64l",
                        "__runtime_remaining" to "7",
                        "__runtime_resumed" to "true",
                        "protocolLabel" to "S3",
                        "endpoint" to "device://peer",
                        "sourcePath" to "/source",
                        "targetPath" to "/target",
                    )
                )
                taskState.putResult(task, "/target/file.txt", AppStrings.ui_test_task_state_completion_cleanup_completed_validation)
                taskState.putTransientResult(task, "/target/file.txt", AppStrings.ui_test_task_state_completion_cleanup_upload_progress_100)
                taskState.updateStatus(task, StatusEnum.SUCCESS)
            }

            while (true) {
                val storedTask = taskState.getTask(task.key)
                val completed = storedTask != null &&
                    storedTask.status == StatusEnum.SUCCESS &&
                    storedTask.result.isEmpty() &&
                    storedTask.retryEntries.isEmpty() &&
                    storedTask.activeResults.isEmpty() &&
                    storedTask.values["progressCur"] == null &&
                    storedTask.values["progressMax"] == null &&
                    storedTask.transientResultMessage() == null
                if (completed) {
                    break
                }
                delay(10.milliseconds)
            }

            val storedTask = assertNotNull(taskState.getTask(task.key))
            assertEquals(StatusEnum.SUCCESS, storedTask.status)
            assertEquals("/target", storedTask.values["path"])
            assertNull(storedTask.values["progressCur"])
            assertNull(storedTask.values["progressMax"])
            assertNull(storedTask.values["__runtime_phase"])
            assertNull(storedTask.values["__runtime_stage"])
            assertNull(storedTask.values["__runtime_queue_category"])
            assertNull(storedTask.values["__runtime_queue_file"])
            assertNull(storedTask.values["__runtime_remaining"])
            assertNull(storedTask.values["__runtime_resumed"])
            assertNull(storedTask.values["protocolLabel"])
            assertNull(storedTask.values["endpoint"])
            assertNull(storedTask.values["sourcePath"])
            assertNull(storedTask.values["targetPath"])
            assertNull(storedTask.runtimeMetricSpeedText())
            assertNull(storedTask.runtimeMetricRemainingText())
            assertNull(storedTask.transientResultPath())
            assertNull(storedTask.transientResultMessage())
            assertTrue(storedTask.result.isEmpty())
            assertTrue(storedTask.retryEntries.isEmpty())
            assertTrue(storedTask.activeResults.isEmpty())
        }
    }

    @Test
    fun withValuesReturnsSameTaskWhenEntriesAreUnchanged() {
        val task = Task(
            taskType = TaskType.Copy,
            key = 402L,
            status = StatusEnum.LOADING,
            values = mapOf(
                "progressCur" to "1",
                "progressMax" to "10",
            ),
        )

        val updated = task.withValues(
            listOf(
                "progressCur" to "1",
                "progressMax" to "10",
            )
        )

        assertSame(task, updated)
    }

    @Test
    fun taskUsesOverallProgressWhenAvailable() {
        val task = Task(
            taskType = TaskType.Copy,
            key = 403L,
            status = StatusEnum.LOADING,
            values = mapOf(
                "progressCur" to "7",
                "progressMax" to "12",
                "__overall_progress_cur" to "3",
                "__overall_progress_max" to "20",
            ),
        )

        assertEquals(3, task.currentProgressCur())
        assertEquals(20, task.currentProgressMax())
    }
}
