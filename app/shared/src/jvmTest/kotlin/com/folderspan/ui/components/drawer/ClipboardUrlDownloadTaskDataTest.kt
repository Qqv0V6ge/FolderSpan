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
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class ClipboardUrlDownloadTaskDataTest : ChineseLocalizationTest() {
    @Test
    fun downloadObjectPopulatesTheUnmodifiedTaskDialogUntilSuccess() = runComposeUiTest {
        val finishDownload = CompletableDeferred<Unit>()
        val tasks = TaskState(
            runtimeStore = object : TaskRuntimePersistenceStore by TempTaskRuntimePersistenceStore() {
                // 只观察本测试创建的任务，不恢复其他测试保留的任务快照。
                override fun loadTaskSnapshots(): List<Task> = emptyList()
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
                coordinator.confirm(assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft)
            }
            waitUntil { tasks.tasks.singleOrNull()?.currentProgressCur() == 25 }
            onNodeWithText(AppStrings.ui_arg0_total.format(arg0 = "100")).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_arg0_complete.format(arg0 = "25")).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_arg0_remaining.format(arg0 = "75")).assertIsDisplayed()
            onNodeWithText("3.0 GB / 12.0 GB", substring = true).assertIsDisplayed()
            onNodeWithText("25.0%", substring = true).assertIsDisplayed()
            onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertIsDisplayed()
            finishDownload.complete(Unit)
            waitUntil { tasks.tasks.isEmpty() }
            onAllNodesWithText("3.0 GB / 12.0 GB", substring = true).assertCountEquals(0)
        } finally {
            runOnIdle { coordinator.teardown() }
        }
    }
}
