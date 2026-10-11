package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TaskRuntimeMetricModeTest : ChineseLocalizationTest() {
    @Test
    fun ensureRuntimeByteMetricsDoesNotResetExistingTaskTotal() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 10_000L)
        taskState.ensureRuntimeByteMetrics(task, 1_000L)

        val storedTask = taskState.tasks.first { item -> item.key == task.key }
        assertEquals("bytes", storedTask.values["__runtime_metric_kind"])
        assertEquals("10000", storedTask.values["__runtime_metric_total"])
    }

    @Test
    fun itemRuntimeMetricsIgnoreNestedByteItemUpdates() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeItemMetrics(task, completed = 0, total = 4)
        taskState.startRuntimeByteItem(task, "/target/a.txt", 128L)
        taskState.finishRuntimeByteItem(task, "/target/a.txt", 128L)
        delay(250.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 1, total = 4)

        val storedTask = taskState.tasks.first { item -> item.key == task.key }
        assertEquals("items", storedTask.values["__runtime_metric_kind"])
        assertEquals("1", storedTask.values["__runtime_metric_completed"])
        assertEquals("4", storedTask.values["__runtime_metric_total"])
        assertEquals(true, storedTask.runtimeMetricSpeedText()?.endsWith(AppStrings.ui_test_task_runtime_metric_mode_item))
    }

    @Test
    fun deleteExecuteStageUsesExplicitRuntimeParallelCount() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Delete,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.FILES,
        )
        taskState.putRuntimeParallelCount(task, 4)

        val storedTask = taskState.tasks.first { item -> item.key == task.key }
        assertEquals(4, storedTask.activeParallelCount())
    }

    @Test
    fun directoryRuntimeMetricsOverrideByteMetricsAndRestoreByteMetricsForFiles() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.beginRuntimeByteMetrics(task, 10_000L)
        val taskStartedAt = taskState.tasks
            .first { item -> item.key == task.key }
            .values["__runtime_metric_started_at"]
            ?.toLongOrNull()
        assertNotNull(taskStartedAt)

        delay(250.milliseconds)
        taskState.beginRuntimeDirectoryItemMetrics(task, completed = 0, total = 4, startedAt = taskStartedAt)
        delay(250.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 1, total = 4)

        val directoryTask = taskState.tasks.first { item -> item.key == task.key }
        assertEquals("items", directoryTask.values["__runtime_metric_kind"])
        assertEquals("1", directoryTask.values["__runtime_metric_completed"])
        assertEquals("4", directoryTask.values["__runtime_metric_total"])
        assertEquals(true, directoryTask.runtimeMetricSpeedText()?.endsWith(AppStrings.ui_test_task_runtime_metric_mode_item))
        assertNull(directoryTask.runtimeMetricRemainingText())
        assertEquals(taskStartedAt.toString(), directoryTask.values["__runtime_metric_started_at"])

        delay(250.milliseconds)
        taskState.restoreRuntimeByteMetrics(task, totalBytes = 10_000L, startedAt = taskStartedAt)
        taskState.startRuntimeByteItem(task, "/target/a.bin", 10_000L)
        delay(250.milliseconds)
        taskState.putRuntimeByteProgress(task, "/target/a.bin", 5_000L)

        val fileTask = taskState.tasks.first { item -> item.key == task.key }
        assertEquals("bytes", fileTask.values["__runtime_metric_kind"])
        assertEquals("10000", fileTask.values["__runtime_metric_total"])
        assertTrue(fileTask.runtimeMetricSpeedText()?.endsWith(AppStrings.ui_test_task_runtime_metric_mode_item) != true)
        assertEquals(taskStartedAt.toString(), fileTask.values["__runtime_metric_started_at"])
    }
}
