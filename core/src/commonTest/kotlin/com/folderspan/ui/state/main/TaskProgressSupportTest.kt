package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.test.runSuspendTest
import com.folderspan.test.ChineseLocalizationTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class TaskProgressSupportTest : ChineseLocalizationTest() {
    @Test
    fun rateSamplerSmoothsShortFluctuationsAndStillReportsSustainedStall() {
        val sampler = TaskProgressRateSampler(
            initialCompleted = 0L,
            initialAt = 0L,
            sampleIntervalMs = 1_000L,
        )

        val first = sampler.update(completed = 100L, total = 1_000L, now = 1_000L)
        val burst = sampler.update(completed = 400L, total = 1_000L, now = 2_000L)
        val shortStall = sampler.update(completed = 400L, total = 1_000L, now = 3_000L)
        val secondStall = sampler.update(completed = 400L, total = 1_000L, now = 4_000L)
        val sustainedStall = sampler.update(completed = 400L, total = 1_000L, now = 5_000L)

        assertEquals(100.0, first.speedPerSecond)
        assertTrue(burst.speedPerSecond in 165.0..175.0)
        assertTrue(shortStall.speedPerSecond in 105.0..115.0)
        assertTrue(secondStall.speedPerSecond in 65.0..75.0)
        assertEquals(0.0, sustainedStall.speedPerSecond)
        assertEquals(-1L, sustainedStall.etaMs)
    }

    @Test
    fun progressUpdateGateThrottlesIntermediateUpdatesAndAllowsForcedCompletion() {
        var now = 1_000L
        val gate = TaskProgressUpdateGate(
            minIntervalMs = 250L,
            nowMs = { now },
        )

        assertTrue(gate.shouldPublish())

        now += 100L
        assertFalse(gate.shouldPublish())

        now += 150L
        assertTrue(gate.shouldPublish())

        now += 1L
        assertTrue(gate.shouldPublish(force = true))
    }

    @Test
    fun putCreatingFolderProgressRecordsConcretePath() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putCreatingFolderProgress(task, "/target/new-folder")

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals("/target/new-folder", storedTask.values["path"])
        assertEquals("", storedTask.transientResultPath())
        assertEquals(AppStrings.ui_test_task_progress_support_creating_folder_target_new_folder, storedTask.transientResultMessage())
        assertNull(storedTask.result[""])
    }

    @Test
    fun buildCreatingFolderMessageFallsBackWhenPathIsBlank() {
        assertEquals(AppStrings.ui_creating_folder, buildCreatingFolderMessage(""))
    }

    @Test
    fun concreteTransferProgressClearsTaskLevelCreatingFolderProgress() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putCreatingFolderProgress(task, "/target/new-folder")
        taskState.putResult(task, "/target/file.txt", AppStrings.ui_start_transfer)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals("/target/file.txt", storedTask.transientResultPath())
        assertEquals(AppStrings.ui_start_transfer, storedTask.transientResultMessage())
        assertNull(storedTask.activeResults[""])
        assertEquals(AppStrings.ui_start_transfer, storedTask.activeResults["/target/file.txt"])
        assertEquals(
            listOf("/target/file.txt" to AppStrings.ui_start_transfer),
            taskState.loadTaskDisplayResults(task),
        )
    }

    @Test
    fun copyScanProgressWritesRuntimeMetricsOutsideResultText() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
        )

        taskState.putCopyScanProgress(
            task = task,
            discoveredEntries = 128,
            speedEntriesPerSecond = 24.4,
            currentParallelism = 6,
        )

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals(128, storedTask.currentProgressMax())
        assertEquals(
            AppStrings.ui_test_task_progress_support_the_data_to_be_copied_is_being_retrieved_128,
            storedTask.transientResultMessage(),
        )
        assertEquals(AppStrings.ui_test_task_progress_support_24_4_items_s, storedTask.runtimeMetricSpeedText())
        assertNull(storedTask.runtimeMetricRemainingText())
        assertEquals(6, storedTask.activeParallelCount())
        assertNull(storedTask.result[""])

        delay(1_100.milliseconds)
        taskState.putCopyScanProgress(
            task = task,
            discoveredEntries = 160,
            speedEntriesPerSecond = 30.0,
            currentParallelism = 8,
        )

        val updatedScanTask = taskState.tasks.first { it.key == task.key }
        assertNull(updatedScanTask.runtimeMetricRemainingText())

        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
        )
        taskState.beginRuntimeItemMetrics(task, completed = 0, total = 10)

        val executingTask = taskState.tasks.first { it.key == task.key }
        assertNull(executingTask.runtimeMetricRemainingText())
        assertEquals(0, executingTask.activeParallelCount())
    }

    @Test
    fun deleteScanProgressMessageKeepsRuntimeMetricsOutOfResultText() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Delete,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
        )

        taskState.putDeleteScanProgress(
            task = task,
            discoveredEntries = 64,
            speedEntriesPerSecond = 10.49,
            currentParallelism = 4,
        )

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals(AppStrings.ui_test_task_progress_support_the_data_to_be_deleted_is_being_retrieved_64, storedTask.transientResultMessage())
        assertEquals(AppStrings.ui_test_task_progress_support_10_5_items_s, storedTask.runtimeMetricSpeedText())
        assertNull(storedTask.runtimeMetricRemainingText())
        assertEquals(4, storedTask.activeParallelCount())
        assertNull(storedTask.result[""])
    }

    @Test
    fun deleteScanProgressUsesTaskPathForTransientDisplay() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Delete,
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/target/old-folder"),
        )
        taskState.addOrUpdate(task)

        taskState.putDeleteScanProgress(task)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals("/target/old-folder", storedTask.transientResultPath())
        assertEquals(AppStrings.message_task_scanning_delete_data, storedTask.transientResultMessage())
        assertEquals(AppStrings.message_task_scanning_delete_data, storedTask.activeResults["/target/old-folder"])
        assertNull(storedTask.activeResults[""])
    }

    @Test
    fun directoryExecutionParallelCountUsesRuntimeParallelValue() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.DIRECTORIES,
        )
        taskState.putCreatingFolderProgress(task, "/target/a")
        taskState.putRuntimeParallelCount(task, 3)

        val directoryTask = taskState.tasks.first { it.key == task.key }
        assertEquals(3, directoryTask.activeParallelCount())

        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
        )
        val fileTask = taskState.tasks.first { it.key == task.key }
        assertEquals(1, fileTask.activeParallelCount())
    }

    @Test
    fun fileExecutionParallelCountUsesExplicitRuntimeParallelValue() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
        )

        taskState.putRuntimeParallelCount(task, 12)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals(12, storedTask.activeParallelCount())
    }

    @Test
    fun runtimeProgressUpdatesAreSafeFromWorkerThread() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.EXECUTING,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
        )

        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(32) { index ->
                        taskState.putRuntimeParallelCount(task, worker + 1)
                        taskState.putValue(task, "path", "/storage/emulated/0/_codex/$worker/$index")
                    }
                }
            }
        }

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertTrue(storedTask.values["path"].orEmpty().startsWith("/storage/emulated/0/_codex/"))
        assertTrue(storedTask.activeParallelCount() in 1..8)
    }

    @Test
    fun copyScanProgressPublisherWritesSpeedAndParallelismForTraversalProgress() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putRuntimeStatus(
            task = task,
            phase = TaskRuntimePhase.SCANNING,
        )
        val publisher = taskState.buildCopyScanProgressPublisher(task)

        publisher(
            TraversalScanProgress(
                discoveredEntries = 1,
                currentParallelism = 3,
                maxParallelism = 8,
                activeRequests = 1,
                scannedDirectories = 1,
            )
        )
        delay(350.milliseconds)
        publisher(
            TraversalScanProgress(
                discoveredEntries = 4,
                currentParallelism = 5,
                maxParallelism = 8,
                activeRequests = 2,
                scannedDirectories = 2,
            )
        )

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals("5", storedTask.values["__runtime_parallel_count"])
        assertNotEquals(AppStrings.ui_test_task_progress_support_0_0_items_s, storedTask.runtimeMetricSpeedText())
        assertNull(storedTask.result[""])
    }

    @Test
    fun removeResultAlsoClearsTransientMessageForMatchingPath() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putResult(task, "/target/a.txt", AppStrings.ui_start_transfer)
        taskState.removeResult(task, "/target/a.txt")

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertNull(storedTask.transientResultPath())
        assertNull(storedTask.transientResultMessage())
    }

    @Test
    fun byteRuntimeMetricsExposeSpeedEtaAndParallelCount() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 1_000L)
        taskState.startRuntimeByteItem(task, "/target/a.txt", 1_000L)
        taskState.putTransientResult(task, "/target/a.txt", AppStrings.ui_start_transfer)
        delay(250.milliseconds)
        taskState.putRuntimeByteProgress(task, "/target/a.txt", 500L)
        taskState.updateRuntimeByteMetrics(task, 2_000L)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertNotNull(storedTask.runtimeMetricSpeedText())
        assertNull(storedTask.runtimeMetricRemainingText())
        assertEquals(1, storedTask.activeParallelCount())
    }

    @Test
    fun byteRuntimeMetricsKeepEtaUnknownBeforeSamplingWindowForSmallFiles() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val path = "/target/small.txt"
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 1_000L)
        taskState.startRuntimeByteItem(task, path, 1_000L)
        taskState.putRuntimeByteProgress(task, path, 100L)

        val storedTask = taskState.tasks.first { it.key == task.key }
        val speed = storedTask.values["__runtime_metric_speed"]?.toDoubleOrNull() ?: -1.0
        assertEquals(0.0, speed)
        assertNull(storedTask.runtimeMetricRemainingText())
    }

    @Test
    fun byteRuntimeMetricsExposeEtaAfterSamplingWindow() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val path = "/target/large.bin"
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 1_000L)
        taskState.startRuntimeByteItem(task, path, 1_000L)
        delay(1_100.milliseconds)
        taskState.putRuntimeByteProgress(task, path, 250L)

        val storedTask = taskState.tasks.first { it.key == task.key }
        val speed = storedTask.values["__runtime_metric_speed"]?.toDoubleOrNull()
        val eta = storedTask.values["__runtime_metric_eta_ms"]?.toLongOrNull()
        assertNotNull(speed)
        assertNotNull(eta)
        assertTrue(speed > 0.0)
        assertTrue(eta > 0L)
        assertNotNull(storedTask.runtimeMetricRemainingText())
    }

    @Test
    fun byteRuntimeMetricsShowZeroEtaForFastSmallFileCompletion() {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val path = "/target/small.txt"
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 128L)
        taskState.startRuntimeByteItem(task, path, 128L)
        taskState.finishRuntimeByteItem(task, path, 128L)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertEquals("00:00", storedTask.runtimeMetricRemainingText())
    }

    @Test
    fun itemRuntimeMetricsExposeSpeedAndEta() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Delete,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeItemMetrics(task, completed = 0, total = 4)
        delay(1_100.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 1, total = 4)

        val storedTask = taskState.tasks.first { it.key == task.key }
        assertNotNull(storedTask.runtimeMetricSpeedText())
        assertNotNull(storedTask.runtimeMetricRemainingText())
    }

    @Test
    fun itemRuntimeMetricsSampleSpeedAndEtaOncePerSecondFromDelta() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeItemMetrics(task, completed = 0, total = 10)
        taskState.putRuntimeItemProgress(task, completed = 2, total = 10)

        val earlyTask = taskState.tasks.first { it.key == task.key }
        assertEquals("0.0", earlyTask.values["__runtime_metric_speed"])
        assertEquals("-1", earlyTask.values["__runtime_metric_eta_ms"])
        assertNull(earlyTask.runtimeMetricRemainingText())

        delay(1_100.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 4, total = 10)

        val sampledTask = taskState.tasks.first { it.key == task.key }
        val sampledSpeed = sampledTask.values["__runtime_metric_speed"]?.toDoubleOrNull()
        val sampledEta = sampledTask.values["__runtime_metric_eta_ms"]?.toLongOrNull()
        assertNotNull(sampledSpeed)
        assertNotNull(sampledEta)
        assertTrue(sampledSpeed in 3.0..4.5)
        assertTrue(sampledEta in 1_000L..3_000L)
    }

    @Test
    fun itemRuntimeMetricsSmoothOneSecondWithoutProgress() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeItemMetrics(task, completed = 0, total = 10)
        delay(1_100.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 4, total = 10)
        delay(1_100.milliseconds)
        taskState.putRuntimeItemProgress(task, completed = 4, total = 10)

        val stalledTask = taskState.tasks.first { it.key == task.key }
        assertTrue(stalledTask.values["__runtime_metric_speed"]?.toDoubleOrNull()?.let { it > 0.0 } == true)
        assertTrue(stalledTask.values["__runtime_metric_eta_ms"]?.toLongOrNull()?.let { it > 0L } == true)
        assertNotNull(stalledTask.runtimeMetricRemainingText())
    }

    @Test
    fun byteRuntimeMetricsSampleSpeedAndEtaOncePerSecondFromDelta() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 1_000L)
        taskState.putRuntimeByteProgress(task, "/target/a.bin", 250L)

        val earlyTask = taskState.tasks.first { it.key == task.key }
        assertEquals("0.0", earlyTask.values["__runtime_metric_speed"])
        assertEquals("-1", earlyTask.values["__runtime_metric_eta_ms"])

        delay(1_100.milliseconds)
        taskState.putRuntimeByteProgress(task, "/target/a.bin", 500L)

        val sampledTask = taskState.tasks.first { it.key == task.key }
        val sampledSpeed = sampledTask.values["__runtime_metric_speed"]?.toDoubleOrNull()
        val sampledEta = sampledTask.values["__runtime_metric_eta_ms"]?.toLongOrNull()
        assertNotNull(sampledSpeed)
        assertNotNull(sampledEta)
        assertTrue(sampledSpeed in 350.0..600.0)
        assertTrue(sampledEta in 500L..2_500L)
    }

    @Test
    fun byteRuntimeMetricsKeepCompletedBaselineWhenTotalChanges() = runSuspendTest {
        val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.beginRuntimeByteMetrics(task, 1_000L)
        taskState.putRuntimeByteProgress(task, "/target/a.bin", 500L)
        taskState.updateRuntimeByteMetrics(task, 2_000L)

        delay(1_100.milliseconds)
        taskState.putRuntimeByteProgress(task, "/target/a.bin", 700L)

        val sampledTask = taskState.tasks.first { it.key == task.key }
        val sampledSpeed = sampledTask.values["__runtime_metric_speed"]?.toDoubleOrNull()
        assertNotNull(sampledSpeed)
        assertTrue(sampledSpeed in 140.0..260.0)
    }
}
