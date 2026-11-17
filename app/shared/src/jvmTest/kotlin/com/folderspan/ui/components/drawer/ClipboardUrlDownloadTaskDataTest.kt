package com.folderspan.ui.components.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadProgress
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadResult
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadTaskConfig
import com.folderspan.service.http.clipboard.ClipboardUrlDownloader
import com.folderspan.service.http.clipboard.ClipboardUrlDraftConfirmResult
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.dialog.TaskInfoDialog
import com.folderspan.ui.components.dialog.TaskInfoDialogUiState
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import com.folderspan.ui.state.file.ClipboardUrlDownloadResultPresenter
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskRuntimePersistenceStore
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TempTaskRuntimePersistenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ClipboardUrlDownloadTaskDataTest : ChineseLocalizationTest() {
    @Test
    fun downloadObjectPopulatesTheUnmodifiedTaskDialogUntilSuccess() = runComposeUiTest {
        val finishDownload = CompletableDeferred<Unit>()
        val tasks = TaskState(
            runtimeStore = object : TaskRuntimePersistenceStore by TempTaskRuntimePersistenceStore() {
                override fun loadTaskSnapshots(): List<Task> = emptyList()
                override fun saveTaskSnapshot(task: Task) = Unit
                override fun deleteTaskSnapshot(taskKey: Long) = Unit
            },
        )
        val coordinator = ClipboardUrlDownloadCoordinator(
            engine = object : ClipboardUrlDownloader {
                override suspend fun download(
                    config: ClipboardUrlDownloadTaskConfig,
                    onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
                    onRetry: suspend (Int, Long) -> Unit,
                ): ClipboardUrlDownloadResult {
                    onProgress(ClipboardUrlDownloadProgress(
                        bytesReceived = 3L * 1024 * 1024 * 1024,
                        totalBytes = 12L * 1024 * 1024 * 1024,
                        attempt = 1,
                        maxAttempts = 3,
                    ))
                    finishDownload.await()
                    return ClipboardUrlDownloadResult.Success(ClipboardStagedDownload(
                        id = "completed-download",
                        displayName = "file.bin",
                        size = 12L * 1024 * 1024 * 1024,
                        contentType = "application/octet-stream",
                        localPath = "/test/file.bin",
                        release = {},
                    ))
                }
            },
            taskState = tasks,
            resultPresenter = ClipboardUrlDownloadResultPresenter { true },
            uiDispatcher = Dispatchers.Unconfined,
        )
        try {
            setContent {
                MaterialTheme {
                    tasks.tasks.singleOrNull()?.let { task ->
                        TaskInfoDialog(
                            uiState = TaskInfoDialogUiState(task, false, 0, null, emptyList()),
                            onDismiss = {}, onToResult = {}, onContinue = {}, onDelete = {},
                            onCancelTask = {}, onPauseTask = {}, onResumeTask = {},
                        )
                    }
                }
            }
            runOnIdle {
                val url = "https://example.test/file.bin"
                coordinator.openUrl(url, url)
                assertIs<ClipboardUrlDraftConfirmResult.Confirmed>(
                    coordinator.confirm(assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft),
                )
            }
            // 后台任务不由 Compose 测试时钟驱动，等待其状态发布后再断言界面。
            val downloading = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    coordinator.state.first {
                        it is ClipboardUrlDownloadState.Downloading || it is ClipboardUrlDownloadState.Fallback
                    }
                }
            }
            assertIs<ClipboardUrlDownloadState.Downloading>(downloading)
            assertEquals(25, tasks.tasks.single().currentProgressCur())
            onNodeWithText(AppStrings.ui_arg0_total.format(arg0 = "100")).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_arg0_complete.format(arg0 = "25")).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_arg0_remaining.format(arg0 = "75")).assertIsDisplayed()
            onNodeWithText("3.0 GB / 12.0 GB", substring = true).assertIsDisplayed()
            onNodeWithText("25.0%", substring = true).assertIsDisplayed()
            onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertIsDisplayed()
            finishDownload.complete(Unit)
            val completed = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    coordinator.state.first {
                        it is ClipboardUrlDownloadState.Succeeded || it is ClipboardUrlDownloadState.Fallback
                    }
                }
            }
            assertIs<ClipboardUrlDownloadState.Succeeded>(completed)
            assertTrue(tasks.tasks.isEmpty())
            onAllNodesWithText("3.0 GB / 12.0 GB", substring = true).assertCountEquals(0)
        } finally {
            runOnIdle { coordinator.teardown() }
            tasks.stop()
        }
    }
}
