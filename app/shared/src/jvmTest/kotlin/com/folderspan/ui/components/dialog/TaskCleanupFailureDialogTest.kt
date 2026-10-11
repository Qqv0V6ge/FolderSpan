package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.ui.state.main.TaskCleanupFailure
import org.jetbrains.skia.Image
import strings.AppStrings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TaskCleanupFailureDialogTest {
    @Test fun failureChoicesAreDistinctAndBusyDisablesRepeatClicks() = runComposeUiTest {
        val busy = mutableStateOf(false)
        var retries = 0
        var deletions = 0
        setContent {
            MaterialTheme {
                TaskCleanupFailureDialog(
                    failures = listOf(TaskCleanupFailure(17, "/Downloads/report.part", "cleanup_failed"),
                        TaskCleanupFailure(18, "/Downloads/photos.part", "ownership_unverified")),
                    busy = busy.value,
                    onDismiss = {},
                    onRetry = { retries++ },
                    onDeleteRecord = { deletions++ },
                )
            }
        }
        onNodeWithText(AppStrings.ui_task_cleanup_notice).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_cleanup_retry).performClick()
        assertEquals(1, retries)
        assertEquals(0, deletions)
        onNodeWithText(AppStrings.ui_task_cleanup_delete_record).performClick()
        assertEquals(1, deletions)
        val screenshot = onAllNodes(isRoot()).onLast().captureToImage()
        val output = File("build/reports/task-cleanup-failure.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData()!!.bytes)
        runOnIdle { busy.value = true }
        onNodeWithText(AppStrings.ui_task_cleanup_retry).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_task_cleanup_delete_record).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_task_cleanup_busy).assertIsDisplayed()
    }

    @Test fun unreadableJournalDisplaysUnknownInsteadOfZeroFiles() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TaskCleanupFailureDialog(listOf(TaskCleanupFailure(19, null, "journal_unreadable")), false, {}, {}, {})
            }
        }
        onNodeWithText(AppStrings.ui_task_cleanup_unknown).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_cleanup_retry).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_cleanup_delete_record).assertIsDisplayed()
    }

    @Test fun narrowWindowKeepsChoicesVisibleAndEscapeOnlyClosesWhenIdle() = runSkikoComposeUiTest(size = Size(360f, 560f)) {
        val visible = mutableStateOf(true)
        val busy = mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (visible.value) TaskCleanupFailureDialog(
                    failures = (1..20).map { TaskCleanupFailure(it.toLong(), "/Downloads/folder/long-file-name-$it.part", "cleanup_failed") },
                    busy = busy.value,
                    onDismiss = { visible.value = false },
                    onRetry = {}, onDeleteRecord = {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_task_cleanup_failed).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText(AppStrings.ui_task_cleanup_failed).assertIsDisplayed()
        runOnIdle { busy.value = false }
        onNodeWithText(AppStrings.ui_task_cleanup_retry).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_task_cleanup_delete_record).assertIsDisplayed()
        val output = File("build/reports/task-cleanup-failure-narrow.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
        onNodeWithText(AppStrings.ui_task_cleanup_failed).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText(AppStrings.ui_task_cleanup_failed).assertDoesNotExist()
    }
}
