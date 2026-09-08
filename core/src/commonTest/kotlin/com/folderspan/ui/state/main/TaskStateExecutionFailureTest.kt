package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.test.ChineseLocalizationTest
import strings.AppStrings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class TaskStateExecutionFailureTest : ChineseLocalizationTest() {
    @Test
    fun uncaughtTaskExceptionMarksTaskFailedAndReleasesQueue() = runSuspendTest {
        withTimeout(5_000.milliseconds) {
            val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val events = Channel<String>(Channel.UNLIMITED)

            val failedTask = Task(
                taskType = TaskType.Copy,
                key = 301L,
                status = StatusEnum.LOADING,
                values = mapOf("path" to "/target"),
            )
            val nextTask = Task(
                taskType = TaskType.Copy,
                key = 302L,
                status = StatusEnum.LOADING,
            )

            taskState.addOrUpdate(failedTask)
            taskState.registerTaskHandler(failedTask) {
                throw DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
            }

            taskState.addOrUpdate(nextTask)
            taskState.registerTaskHandler(nextTask) {
                events.send("next:start")
                events.send("next:done")
            }

            while (taskState.getTask(failedTask.key)?.status != StatusEnum.FAILURE) {
                delay(10.milliseconds)
            }

            val latestFailedTask = assertNotNull(taskState.getTask(failedTask.key))
            assertEquals(StatusEnum.FAILURE, latestFailedTask.status)
            assertEquals(
                AppStrings.message_task_target_device_disconnected,
                taskState.getMeaningfulResult(latestFailedTask, "/target"),
            )

            assertEquals("next:start", events.receive())
            assertEquals("next:done", events.receive())
        }
    }
}
