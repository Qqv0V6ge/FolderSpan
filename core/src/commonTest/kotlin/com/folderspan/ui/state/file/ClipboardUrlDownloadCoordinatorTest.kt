package com.folderspan.ui.state.file

import com.folderspan.data.StatusEnum
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadPlatformCapabilities
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadProgress
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadResult
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadTaskConfig
import com.folderspan.service.http.clipboard.ClipboardUrlDownloader
import com.folderspan.service.http.clipboard.ClipboardUrlDraftConfirmResult
import com.folderspan.service.http.clipboard.ClipboardUrlDraftOpenResult
import com.folderspan.ui.state.main.TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY
import com.folderspan.ui.state.main.TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardUrlDownloadCoordinatorTest {
    @Test
    fun filePasteWaitsForSettingsThenDownloadsAndCopiesWithinOneTask() = runTest {
        var requests = 0
        var copies = 0
        var releases = 0
        var defaultPresentations = 0
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val tasks = TaskState(runtimeStore = runtimeStore)
        val coordinator = coordinator(
            dispatcher = StandardTestDispatcher(testScheduler),
            taskState = tasks,
            presenter = ClipboardUrlDownloadResultPresenter { defaultPresentations++; true },
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    assertEquals(TaskType.Download, tasks.tasks.single().taskType)
                    requests++
                    return ClipboardUrlDownloadResult.Success(STAGED.copy(release = { releases++ }))
                }
            },
        )
        val paste = async {
            coordinator.downloadForCopy(URL) { task, staged ->
                assertEquals(tasks.tasks.single().key, task.key)
                assertEquals(STAGED.localPath, staged.localPath)
                assertEquals(0, releases)
                copies++
                Result.success(true)
            }
        }
        runCurrent()
        val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft
        assertTrue(tasks.tasks.isEmpty())
        assertEquals(0, requests)
        assertEquals(0, copies)

        coordinator.confirm(draft)
        awaitState(coordinator) { it is ClipboardUrlDownloadState.Succeeded }
        assertTrue(paste.await())
        assertEquals(1, requests)
        assertEquals(1, copies)
        assertEquals(1, releases)
        assertEquals(0, defaultPresentations)
        assertTrue(tasks.tasks.isEmpty())
        assertTrue(runtimeStore.loadTaskSnapshots().isEmpty())
        coordinator.teardown()
    }

    @Test
    fun failedCopyKeepsTheDownloadTaskAndReleasesStaging() = runTest {
        var releases = 0
        val tasks = taskState()
        val coordinator = coordinator(
            dispatcher = StandardTestDispatcher(testScheduler),
            taskState = tasks,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ) = ClipboardUrlDownloadResult.Success(STAGED.copy(release = { releases++ }))
            },
        )
        try {
            val paste = async {
                coordinator.downloadForCopy(URL) { task, _ ->
                    assertNotNull(tasks.getTask(task.key))
                    Result.success(false)
                }
            }
            runCurrent()
            coordinator.confirm(assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft)
            awaitState(coordinator) { it is ClipboardUrlDownloadState.Fallback }
            assertFalse(paste.await())
            assertEquals(StatusEnum.FAILURE, tasks.tasks.single().status)
            assertEquals(1, releases)
        } finally {
            coordinator.teardown()
        }
    }

    @Test
    fun cancellingFilePasteSettingsDoesNotDownloadOrCreateTask() = runTest {
        val tasks = taskState()
        val coordinator = coordinator(
            dispatcher = StandardTestDispatcher(testScheduler),
            taskState = tasks,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult = error("取消设置不应下载")
            },
        )
        val paste = async {
            coordinator.downloadForCopy(URL) { _, _ -> error("取消设置不应写入目标") }
        }
        runCurrent()
        assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value)
        coordinator.dismiss()
        assertFalse(paste.await())
        assertTrue(tasks.tasks.isEmpty())
        coordinator.teardown()
    }

    @Test
    fun confirmationCreatesVisibleTaskBeforeTheFirstRequest() = runTest {
        var requests = 0
        var taskVisibleBeforeRequest = false
        val downloadStarted = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskState = taskState()
        val coordinator = coordinator(
            dispatcher = dispatcher,
            taskState = taskState,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    requests++
                    taskVisibleBeforeRequest = taskState.tasks.singleOrNull()?.taskType == TaskType.Download
                    downloadStarted.complete(Unit)
                    return ClipboardUrlDownloadResult.Failure(
                        error = com.folderspan.service.http.clipboard.ClipboardUrlDownloadError.HttpFailure,
                        rawText = config.rawText,
                        safeReason = "failed",
                    )
                }
            },
        )

        assertEquals(ClipboardUrlDraftOpenResult.Opened, coordinator.openUrl(URL, URL))
        assertEquals(0, requests)
        assertTrue(taskState.tasks.isEmpty())
        assertEquals(
            ClipboardUrlDraftOpenResult.AlreadyRunning,
            coordinator.openUrl("https://second.example/file", "https://second.example/file"),
        )

        val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft
        assertIs<ClipboardUrlDraftConfirmResult.Confirmed>(coordinator.confirm(draft))
        val task = taskState.tasks.single()
        assertEquals(TaskType.Download, task.taskType)
        assertEquals(StatusEnum.LOADING, task.status)
        assertTrue(task.values.getValue("path").contains("example.com"))
        assertFalse(task.values.getValue("path").contains("secret=1"))

        downloadStarted.await()
        awaitState(coordinator) { it is ClipboardUrlDownloadState.Fallback }

        assertEquals(1, requests)
        assertTrue(taskVisibleBeforeRequest)
        assertIs<ClipboardUrlDownloadState.Fallback>(coordinator.state.value)
        assertEquals(StatusEnum.FAILURE, taskState.getTask(task.key)?.status)
        coordinator.teardown()
    }

    @Test
    fun progressIsPublishedOnTaskAndSuccessIsPresentedToShareList() = runTest {
        val progressSent = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        val presented = CompletableDeferred<ClipboardStagedDownload>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(runtimeStore = runtimeStore)
        var releases = 0
        val coordinator = coordinator(
            dispatcher = dispatcher,
            taskState = taskState,
            presenter = ClipboardUrlDownloadResultPresenter { staged ->
                presented.complete(staged)
                true
            },
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    onProgress(
                        ClipboardUrlDownloadProgress(
                            bytesReceived = 50L,
                            totalBytes = 100L,
                            attempt = 1,
                            maxAttempts = 1,
                        )
                    )
                    progressSent.complete(Unit)
                    finishDownload.await()
                    return ClipboardUrlDownloadResult.Success(STAGED.copy(release = { releases++ }))
                }
            },
        )

        coordinator.openUrl(URL, URL)
        val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft
        coordinator.confirm(draft)
        runCurrent()
        progressSent.await()
        advanceUntilIdle()

        val runningTask = taskState.tasks.single()
        assertEquals("50", runningTask.values[TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY])
        assertEquals("100", runningTask.values[TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY])
        assertEquals(50, runningTask.currentProgressCur())
        assertEquals(100, runningTask.currentProgressMax())
        val message = assertNotNull(runningTask.transientResultMessage())
        assertTrue(message.contains("50.0%"))
        assertTrue(message.contains("50 B / 100 B"))

        finishDownload.complete(Unit)
        runCurrent()
        advanceUntilIdle()
        val presentedFile = presented.await()
        assertEquals(STAGED.displayName, presentedFile.displayName)
        assertEquals(STAGED.localPath, presentedFile.localPath)
        assertIs<ClipboardUrlDownloadState.Succeeded>(coordinator.state.value)
        assertNull(taskState.getTask(runningTask.key))
        assertTrue(runtimeStore.loadTaskSnapshots().isEmpty())
        assertEquals(0, releases)
        presentedFile.release()
        assertEquals(1, releases)
        coordinator.teardown()
    }

    @Test
    fun largeDownloadSetsGenericTaskProgressWithoutByteCountOverflow() = runTest {
        val progressSent = CompletableDeferred<Unit>()
        val tasks = taskState()
        val coordinator = coordinator(
            dispatcher = StandardTestDispatcher(testScheduler),
            taskState = tasks,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    onProgress(ClipboardUrlDownloadProgress(
                        bytesReceived = 3L * 1024 * 1024 * 1024,
                        totalBytes = 12L * 1024 * 1024 * 1024,
                        attempt = 2,
                        maxAttempts = 3,
                        fellBackToSingleThread = true,
                    ))
                    progressSent.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        coordinator.openUrl(URL, URL)
        coordinator.confirm(assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft)
        try {
            progressSent.await()
            val task = tasks.tasks.single()
            assertEquals(25, task.currentProgressCur())
            assertEquals(100, task.currentProgressMax())
            val message = assertNotNull(task.transientResultMessage())
            assertTrue(message.contains("3.0 GB / 12.0 GB"))
            assertTrue(message.contains("25.0%"))
            assertTrue(message.contains("2/3"))
            assertFalse(message.contains("secret=1"))
        } finally {
            coordinator.teardown()
        }
    }

    @Test
    fun rejectedShareListDeliveryReleasesTheStagedFile() = runTest {
        var releases = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskState = taskState()
        val coordinator = coordinator(
            dispatcher = dispatcher,
            taskState = taskState,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ) = ClipboardUrlDownloadResult.Success(STAGED.copy(release = { releases++ }))
            },
        )

        coordinator.openUrl(URL, URL)
        val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft
        coordinator.confirm(draft)
        awaitState(coordinator) { it is ClipboardUrlDownloadState.Fallback }

        assertEquals(1, releases)
        assertEquals(StatusEnum.FAILURE, taskState.tasks.single().status)
        coordinator.teardown()
    }

    @Test
    fun taskCancellationClearsSensitiveTaskConfiguration() = runTest {
        var activeConfig: ClipboardUrlDownloadTaskConfig? = null
        val downloadStarted = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskState = taskState()
        val coordinator = coordinator(
            dispatcher = dispatcher,
            taskState = taskState,
            downloader = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    activeConfig = config
                    downloadStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        coordinator.openUrl(URL, URL)
        val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft.copy(
            cookie = "session=secret",
            userAgent = "Agent/1",
        )
        coordinator.confirm(draft)
        downloadStarted.await()

        val task = assertNotNull(taskState.tasks.singleOrNull())
        taskState.requestCancel(task)
        awaitState(coordinator) { it is ClipboardUrlDownloadState.Cancelled }
        withTimeout(5_000L) {
            while (taskState.getTask(task.key)?.status == StatusEnum.LOADING) {
                runCurrent()
                yield()
            }
        }

        assertIs<ClipboardUrlDownloadState.Cancelled>(coordinator.state.value)
        assertEquals(StatusEnum.FAILURE, taskState.getTask(task.key)?.status)
        assertEquals("", activeConfig?.cookie)
        assertEquals("", activeConfig?.userAgent)
        coordinator.teardown()
    }

    private fun coordinator(
        dispatcher: CoroutineDispatcher,
        taskState: TaskState,
        downloader: ClipboardUrlDownloader,
        presenter: ClipboardUrlDownloadResultPresenter = ClipboardUrlDownloadResultPresenter { false },
    ): ClipboardUrlDownloadCoordinator = ClipboardUrlDownloadCoordinator(
        engine = downloader,
        taskState = taskState,
        resultPresenter = presenter,
        uiDispatcher = dispatcher,
        capabilities = NATIVE,
    )

    private fun taskState() = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())

    private suspend fun TestScope.awaitState(
        coordinator: ClipboardUrlDownloadCoordinator,
        predicate: (ClipboardUrlDownloadState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(coordinator.state.value)) {
                runCurrent()
                yield()
            }
        }
    }

    private companion object {
        const val URL = "https://example.com/file.bin?secret=1"
        val STAGED = ClipboardStagedDownload(
            id = "download-1",
            displayName = "file.bin",
            size = 100L,
            contentType = "application/octet-stream",
            localPath = "/tmp/file.bin",
            release = {},
        )
        val NATIVE = ClipboardUrlDownloadPlatformCapabilities(
            platformName = "Test",
            canSetCookie = true,
            canSetUserAgent = true,
            canControlAutomaticHeaders = true,
            browserCredentialsOmitted = false,
        )
    }
}
