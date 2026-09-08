package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.exception.AuthorityException
import com.folderspan.test.ChineseLocalizationTest
import strings.AppStrings
import kotlin.test.*

class TaskFailureSupportTest : ChineseLocalizationTest() {
    @Test
    fun resolveTaskFailureMessageIgnoresTransientProgressMessages() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        val progress = taskProgress(AppStrings.ui_transfer_progress, "50%", "1", "2", "1 MB/s", AppStrings.ui_test_task_failure_support_1_sec)
        taskState.putResult(task, "/target/file.txt", AppStrings.ui_start_transfer)
        taskState.putResult(task, "/target/file.txt", progress)

        assertEquals(true, taskState.getTask(task.key)?.result?.isEmpty())
        assertEquals(progress, taskState.getTask(task.key)?.transientResultMessage())
        assertEquals(
            mapOf("/target/file.txt" to progress),
            taskState.getTask(task.key)?.activeResults,
        )
        assertNull(taskState.getMeaningfulResult(task, "/target/file.txt"))
        assertEquals(
            AppStrings.ui_file_copy_failed,
            taskState.resolveTaskFailureMessage(
                task = task,
                preferredPath = "/target/file.txt",
                fallback = AppStrings.ui_file_copy_failed,
            )
        )
    }

    @Test
    fun resolveTaskFailureMessageFallsBackToOtherMeaningfulErrors() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putResult(task, "/target/file.txt", taskProgress(AppStrings.ui_download_progress, "50%", "1", "2", "1 MB/s", AppStrings.ui_test_task_failure_support_1_sec))
        taskState.putResult(task, "/target", AppStrings.message_task_device_offline)

        assertEquals(
            AppStrings.message_task_device_offline,
            taskState.resolveTaskFailureMessage(
                task = task,
                preferredPath = "/target/file.txt",
                fallback = AppStrings.ui_file_copy_failed,
            )
        )
    }

    @Test
    fun resolveTaskFailureMessageHighlightsPermissionErrors() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        assertEquals(
            AppStrings.ui_test_task_failure_support_no_permission_no_permission_to_write_to_that_path,
            taskState.resolveTaskFailureMessage(
                task = task,
                preferredPath = "/target/.codex",
                error = AuthorityException(AppStrings.ui_no_permission_to_write_to_that_path),
                fallback = AppStrings.ui_file_copy_failed,
            )
        )
    }

    @Test
    fun recordRetryFailureHighlightsPermissionErrors() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val retryEntry = TaskRetryEntry(
            entryKey = "COPY|Local||/source/.codex|Device|jvm|/target/.codex",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/source/.codex",
            srcProtocol = FileProtocol.Local,
            destPath = "/target/.codex",
            destProtocol = FileProtocol.Device,
            destProtocolId = "jvm",
        )
        taskState.addOrUpdate(task)

        val message = taskState.recordRetryFailure(task, retryEntry, AppStrings.message_task_permission_denied, AppStrings.ui_copy_failed)

        assertEquals(
            AppStrings.ui_test_task_failure_support_no_permissions_no_permissions_please_check_remote,
            message
        )
    }

    @Test
    fun failureRevisionChangesOnlyForAffectedTask() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val affectedTask = Task(
            taskType = TaskType.Copy,
            key = 711L,
            status = StatusEnum.FAILURE,
        )
        val unaffectedTask = Task(
            taskType = TaskType.Copy,
            key = 712L,
            status = StatusEnum.FAILURE,
        )
        val retryEntry = TaskRetryEntry(
            entryKey = "COPY|Local||/source.txt|Local||/target.txt",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/source.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/target.txt",
            destProtocol = FileProtocol.Local,
        )
        taskState.addOrUpdate(affectedTask)
        taskState.addOrUpdate(unaffectedTask)

        taskState.recordRetryFailure(affectedTask, retryEntry, AppStrings.ui_copy_failed, AppStrings.ui_copy_failed)

        assertEquals(1, taskState.getFailureRevision(affectedTask.key))
        assertEquals(0, taskState.getFailureRevision(unaffectedTask.key))

        taskState.clearRetryFailureForRetry(affectedTask, retryEntry)

        assertEquals(2, taskState.getFailureRevision(affectedTask.key))
        assertEquals(0, taskState.getFailureRevision(unaffectedTask.key))

        taskState.delete(affectedTask)

        assertEquals(0, taskState.getFailureRevision(affectedTask.key))
    }

    @Test
    fun buildBatchTaskFailureMessageHighlightsPermissionErrors() {
        assertEquals(
            AppStrings.ui_test_task_failure_support_batch_copy_failed_2_entries_failed_the_first,
            buildBatchTaskFailureMessage(
                operation = AppStrings.ui_copy,
                failureCount = 2,
                firstFailedPath = "/target/.codex",
                firstError = AppStrings.message_task_permission_denied,
            )
        )
    }

    @Test
    fun runningTaskDisplayResultsIgnorePendingRuntimeQueue() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val task = Task(
            taskType = TaskType.Copy,
            key = 701L,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            listOf(
                TaskRuntimeQueueEntry(
                    entryId = "copy-a",
                    stage = TaskRuntimeStage.COPY,
                    kind = TaskRuntimeEntryKind.FILE_COPY,
                    src = TaskRuntimeEndpointRef(path = "/source/a.txt"),
                    dest = TaskRuntimeEndpointRef(path = "/target/a.txt"),
                    order = 0,
                ),
                TaskRuntimeQueueEntry(
                    entryId = "copy-b",
                    stage = TaskRuntimeStage.COPY,
                    kind = TaskRuntimeEntryKind.FILE_COPY,
                    src = TaskRuntimeEndpointRef(path = "/source/b.txt"),
                    dest = TaskRuntimeEndpointRef(path = "/target/b.txt"),
                    order = 1,
                ),
            ),
        )
        runtimeStore.saveRunState(
            TaskRuntimeRunState(
                taskKey = task.key,
                runId = "run-701",
                currentStage = TaskRuntimeStage.COPY,
                currentQueueCategory = TaskRuntimeQueueCategory.FILES,
                currentQueueFile = "000001.pb64l",
                currentEntryPath = "/target/a.txt",
            )
        )
        val progress = taskProgress(AppStrings.ui_download_progress, "50%", "1", "2", "1 MB/s", AppStrings.ui_test_task_failure_support_1_sec)
        taskState.putTransientResult(task, "/target/a.txt", progress)

        assertEquals(
            listOf(
                "/target/a.txt" to progress,
            ),
            taskState.loadTaskDisplayResults(task),
        )
    }

    @Test
    fun runningDeleteTaskDisplayResultsFallBackToRuntimeQueueWhenOnlyScanMessageIsActive() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val task = Task(
            taskType = TaskType.Delete,
            key = 702L,
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/target/delete.txt"),
        )
        taskState.addOrUpdate(task)
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.FILES,
            listOf(
                TaskRuntimeQueueEntry(
                    entryId = "delete-a",
                    stage = TaskRuntimeStage.DELETE,
                    kind = TaskRuntimeEntryKind.TARGET_DELETE,
                    src = TaskRuntimeEndpointRef(path = "/target/delete.txt"),
                    order = 0,
                ),
            ),
        )
        runtimeStore.saveRunState(
            TaskRuntimeRunState(
                taskKey = task.key,
                runId = "run-702",
                currentStage = TaskRuntimeStage.DELETE,
                currentQueueCategory = TaskRuntimeQueueCategory.FILES,
                currentQueueFile = "000001.pb64l",
                currentEntryPath = "/target/delete.txt",
            )
        )
        taskState.putDeleteScanProgress(task)

        assertEquals(
            listOf(
                "/target/delete.txt" to AppStrings.ui_test_task_failure_support_executing_deleting_file,
            ),
            taskState.loadTaskDisplayResults(task),
        )
    }

    @Test
    fun runningTaskDisplayResultsShowLatestActiveTransientMessage() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        taskState.putTransientResult(task, "/target/a.txt", taskProgress(AppStrings.ui_download_progress, "50%", "1", "2", "1 MB/s", AppStrings.ui_test_task_failure_support_1_sec))
        taskState.putTransientResult(task, "/target/b.txt", taskProgress(AppStrings.ui_download_progress, "25%", "1", "4", "1 MB/s", AppStrings.ui_test_task_failure_support_3_seconds))
        val latestProgress = taskProgress(AppStrings.ui_download_progress, "75%", "3", "4", "1 MB/s", AppStrings.ui_test_task_failure_support_1_sec)
        taskState.putTransientResult(task, "/target/a.txt", latestProgress)

        assertEquals(
            listOf(
                "/target/a.txt" to latestProgress,
            ),
            taskState.loadTaskDisplayResults(task),
        )
    }

    @Test
    fun putResultKeepsFileTaskLifecycleMessagesOutOfFinalResult() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        val path = "/target/file.txt"
        listOf(
            AppStrings.ui_downgrading_long_term_downloads,
            AppStrings.ui_remote_pause_request_failed,
            AppStrings.ui_remote_continued_request_failed,
            AppStrings.ui_replacement,
            AppStrings.ui_copy_completed,
        ).forEach { message ->
            taskState.putResult(task, path, message)
            val latestTask = assertNotNull(taskState.getTask(task.key))
            assertTrue(latestTask.result.isEmpty())
            assertEquals(message, latestTask.activeResults[path])
            assertNull(taskState.getMeaningfulResult(latestTask, path))
        }
    }

    @Test
    fun removeResultClearsTransientEntry() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putTransientResult(task, "/target/file.txt", AppStrings.ui_start_transfer)
        taskState.removeResult(task, "/target/file.txt")

        val latestTask = assertNotNull(taskState.getTask(task.key))
        assertNull(latestTask.transientResultMessage())
        assertTrue(latestTask.activeResults.isEmpty())
    }

    @Test
    fun failureStatusClearsTransientEntry() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)

        taskState.putTransientResult(task, "/target/file.txt", AppStrings.ui_test_task_failure_support_transmission_progress_50_1_2_speed_1_mb_s)
        taskState.updateStatus(task, StatusEnum.FAILURE)

        val latestTask = assertNotNull(taskState.getTask(task.key))
        assertNull(latestTask.transientResultMessage())
        assertTrue(latestTask.activeResults.isEmpty())
        assertEquals(StatusEnum.FAILURE, latestTask.status)
    }

    @Test
    fun loadFailureDisplayResultsFallsBackToStoredSummaryWhenFailureFileIsMissing() {
        val store = TestTaskFailureResultStore().apply { fileAvailable = false }
        val taskState = TaskState(store, TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.FAILURE,
        ).withFailureSummary(
            failureCount = 1,
            firstFailurePath = "/target/file.txt",
            firstFailureMessage = AppStrings.message_task_device_offline,
        )
        taskState.addOrUpdate(task)

        val displayResults = taskState.loadFailureDisplayResults(task)

        assertEquals(listOf("" to AppStrings.message_task_device_offline), displayResults)
    }

    @Test
    fun inMemoryFailureMarkersDoNotLoadFailureStore() {
        val store = TestTaskFailureResultStore()
        val taskState = TaskState(store, TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Delete,
            status = StatusEnum.LOADING,
        )
        val retryEntry = TaskRetryEntry(
            entryKey = "delete|local|/tmp/remove.txt",
            taskType = TaskType.Delete,
            stage = TaskRetryStage.DELETE,
            srcPath = "/tmp/remove.txt",
            srcProtocol = FileProtocol.Local,
        )
        taskState.addOrUpdate(task)

        assertFalse(taskState.hasInMemoryFailureMarkers(task))
        assertEquals(0, store.loadFailuresCount)

        taskState.recordRetryFailure(task, retryEntry, AppStrings.ui_delete_failed, AppStrings.ui_delete_failed)

        assertTrue(taskState.hasInMemoryFailureMarkers(task))
        assertEquals(0, store.loadFailuresCount)
    }

    @Test
    fun completedTaskKeepsFailureSummaryAfterRuntimeCleanup() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val retryEntry = TaskRetryEntry(
            entryKey = "COPY|Local||/source/file.txt|Device|jvm|/target/file.txt",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/source/file.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/target/file.txt",
            destProtocol = FileProtocol.Device,
            destProtocolId = "jvm",
        )
        taskState.addOrUpdate(task)

        val message = taskState.recordRetryFailure(task, retryEntry, AppStrings.message_task_permission_denied, AppStrings.ui_copy_failed)
        taskState.updateStatus(task, StatusEnum.SUCCESS)
        taskState.getTask(task.key)
            ?.clearCompletedRuntimeState()
            ?.let(taskState::update)

        val latestTask = assertNotNull(taskState.getTask(task.key))
        assertEquals(StatusEnum.SUCCESS, latestTask.status)
        assertEquals(1, taskState.getFailureCount(latestTask))
        assertEquals("/target/file.txt" to message, taskState.getFailureSummary(latestTask))
        assertEquals(
            listOf("/target/file.txt" to message),
            taskState.loadFailureDisplayResults(latestTask),
        )
    }

    @Test
    fun clearRetryFailureForRetryHidesOldErrorWhileEntryIsRunningAgain() {
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
        )
        val retryEntry = TaskRetryEntry(
            entryKey = "COPY|Local||/source.bin|Local||/target.bin",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/source.bin",
            srcProtocol = FileProtocol.Local,
            destPath = "/target.bin",
            destProtocol = FileProtocol.Local,
        )
        taskState.addOrUpdate(task)
        taskState.recordRetryFailure(task, retryEntry, AppStrings.ui_test_task_failure_support_401_unauthorized_request_terminated, AppStrings.ui_copy_failed)
        taskState.putResult(task, retryEntry.resultPath, AppStrings.ui_test_task_failure_support_401_unauthorized_request_terminated)

        taskState.clearRetryFailureForRetry(task, retryEntry)
        taskState.putTransientResult(task, retryEntry.resultPath, AppStrings.ui_test_task_failure_support_transmission_progress_50_15_30_speed_1_mb_s)

        val latestTask = assertNotNull(taskState.getTask(task.key))
        assertEquals(0, taskState.getFailureCount(latestTask))
        assertNull(taskState.getFailureSummary(latestTask))
        assertTrue(taskState.loadFailureDisplayResults(latestTask).isEmpty())
        assertEquals(AppStrings.ui_test_task_failure_support_transmission_progress_50_15_30_speed_1_mb_s, latestTask.transientResultMessage())
    }

    @Test
    fun formatTaskResultDisplayOmitsPathForEndpointLevelErrors() {
        assertEquals(
            AppStrings.message_task_device_offline,
            formatTaskResultDisplay("/storage/emulated/0/node_modules", AppStrings.message_task_device_offline)
        )
        assertEquals(
            AppStrings.message_task_request_timeout,
            formatTaskResultDisplay("/storage/emulated/0/node_modules", AppStrings.message_task_request_timeout)
        )
    }

    @Test
    fun formatTaskResultDisplayKeepsPathForFileSpecificErrors() {
        assertEquals(
            AppStrings.ui_test_task_failure_support_write_error,
            formatTaskResultDisplay("/storage/emulated/0/node_modules", AppStrings.ui_write_failed)
        )
    }

    @Test
    fun formatTaskResultDisplayOmitsPackingPath() {
        assertEquals(
            AppStrings.ui_test_task_failure_support_downloading_file_storage_emulated_0_download_file,
            formatTaskResultDisplay(
                "/storage/emulated/0/Download/file.txt",
                AppStrings.ui_test_task_failure_support_downloading_file_from_storage_emulated_0_download,
            )
        )
        assertEquals(
            AppStrings.ui_test_task_failure_support_downloading_file_storage_emulated_0_download_file,
            formatTaskResultDisplay(
                "/storage/emulated/0/Download/file.txt",
                AppStrings.ui_test_task_failure_support_the_file_is_being_packed,
            )
        )
    }
}

private fun taskProgress(
    progress: String,
    percent: String,
    current: String,
    total: String,
    speed: String,
    remaining: String,
): String = AppStrings.message_task_progress_with_metrics.format(
    progress = progress,
    percent = percent,
    current = current,
    total = total,
    speed = speed,
    remaining = remaining,
)
