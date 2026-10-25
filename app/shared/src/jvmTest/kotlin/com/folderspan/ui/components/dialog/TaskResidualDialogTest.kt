package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.StatusEnum
import com.folderspan.extensions.formatFileSize
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskResidualFile
import com.folderspan.ui.state.main.TaskResidualSummary
import com.folderspan.ui.state.main.TaskType
import org.jetbrains.skia.Image
import strings.AppStrings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TaskResidualDialogTest {
    private val path = "/Downloads/.folderspan-18ac4532.part"
    private val summary = TaskResidualSummary(17, listOf(TaskResidualFile(path, 2048)))

    @Test
    fun retainedFilesShowPathSizeAndSeparateKeepFromCleanup() = runComposeUiTest {
        val visible = mutableStateOf(true)
        var keeps = 0
        var cleanups = 0
        setContent {
            MaterialTheme {
                if (visible.value) TaskResidualNoticeDialog(
                    summary = summary,
                    canContinueTask = true,
                    onKeep = { keeps++; visible.value = false },
                    onCleanup = { cleanups++ },
                )
            }
        }
        onNodeWithText(AppStrings.ui_task_residual_notice).assertIsDisplayed()
        onNodeWithText(path).assertIsDisplayed()
        onNodeWithText(2048L.formatFileSize()).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_continue_notice).assertIsDisplayed()
        val output = File("build/reports/task-residual-notice.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
        onNodeWithText(AppStrings.ui_task_residual_keep).performClick()
        assertEquals(1, keeps)
        assertEquals(0, cleanups)
        onNodeWithText(AppStrings.ui_task_residual_title).assertDoesNotExist()
        runOnIdle { visible.value = true }
        onNodeWithText(AppStrings.ui_task_residual_delete).performClick()
        assertEquals(1, cleanups)
        assertEquals(1, keeps)
    }

    @Test
    fun startupNoticeExplainsRecoveryAndUnknownSize() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TaskResidualNoticeDialog(
                    summary.copy(files = listOf(TaskResidualFile(path)), recovered = true), {}, {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_task_residual_recovered).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_size_unknown).assertIsDisplayed()
        onNodeWithText(0L.formatFileSize()).assertDoesNotExist()
    }

    @Test
    fun unknownFilesAreExplainedWithoutClaimingZeroRemainingFiles() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TaskResidualNoticeDialog(TaskResidualSummary(19, emptyList(), unknown = true, recovered = true), {}, {})
            }
        }
        onNodeWithText(AppStrings.ui_task_residual_recovered_unknown).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_unknown).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_count.format(arg0 = "0")).assertDoesNotExist()
    }

    @Test
    fun narrowWindowKeepsBothChoicesVisible() = runSkikoComposeUiTest(size = Size(360f, 560f)) {
        setContent {
            MaterialTheme {
                TaskResidualNoticeDialog(summary.copy(recovered = true), {}, {})
            }
        }
        onNodeWithText(AppStrings.ui_task_residual_delete).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_keep).assertIsDisplayed()
        val output = File("build/reports/task-residual-notice-narrow.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
    }

    @Test
    fun shortWindowKeepsBothChoicesReachable() = runSkikoComposeUiTest(size = Size(640f, 360f)) {
        setContent {
            MaterialTheme {
                TaskResidualNoticeDialog(
                    summary.copy(files = (1..20).map { TaskResidualFile("/Downloads/folder/long-file-name-$it.part", it.toLong()) }),
                    {}, {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_task_residual_delete).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_keep).assertIsDisplayed()
        onNodeWithTag("task_residual_notice_content")
            .performScrollToNode(hasText("/Downloads/folder/long-file-name-20.part"))
        val output = File("build/reports/task-residual-notice-short.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
        onNodeWithText("/Downloads/folder/long-file-name-20.part").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_delete).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_residual_keep).assertIsDisplayed()
    }

    @Test
    fun taskDetailsRetainPathsAndCleanupEntryAfterNoticeIsDismissed() = runComposeUiTest {
        val status = mutableStateOf(StatusEnum.FAILURE)
        var cleanups = 0
        setContent {
            MaterialTheme {
                TaskInfoDialog(
                    uiState = TaskInfoDialogUiState(
                        task = Task(taskType = TaskType.Copy, key = 17, status = status.value),
                        canContinueTask = true,
                        failureCount = 1,
                        failureSummary = null,
                        queueResults = emptyList(),
                        residualSummary = summary,
                    ),
                    onDismiss = {}, onToResult = {}, onContinue = {},
                    onDelete = { cleanups++ }, onCancelTask = {}, onPauseTask = {}, onResumeTask = {},
                )
            }
        }
        listOf(StatusEnum.FAILURE, StatusEnum.SUCCESS).forEachIndexed { index, taskStatus ->
            runOnIdle { status.value = taskStatus }
            onNodeWithText(AppStrings.ui_task_residual_section).assertIsDisplayed()
            onNodeWithText(path).assertIsDisplayed()
            onNodeWithText(2048L.formatFileSize()).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_task_residual_details_notice).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_task_residual_cleanup_notice).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_task_residual_delete).performClick()
            assertEquals(index + 1, cleanups)
            onNodeWithText(AppStrings.ui_view_results).assertIsDisplayed()
            if (taskStatus == StatusEnum.FAILURE) {
                onNodeWithText(AppStrings.ui_continue_task).assertIsDisplayed()
            } else {
                onNodeWithText(AppStrings.ui_continue_task).assertDoesNotExist()
            }
        }
        val output = File("build/reports/task-residual-details.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
    }
}
