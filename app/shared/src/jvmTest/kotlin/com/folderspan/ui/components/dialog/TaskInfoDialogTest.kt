package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import strings.AppStrings
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TaskInfoDialogTest {
    @Test
    fun failureResultsAreAvailableForEveryTaskStatus() = runComposeUiTest {
        val status = mutableStateOf(StatusEnum.SUCCESS)
        val failureCount = mutableStateOf(1)

        setContent {
            MaterialTheme {
                TaskInfoDialog(
                    uiState = TaskInfoDialogUiState(
                        task = Task(
                            taskType = TaskType.Copy,
                            key = 1L,
                            status = status.value,
                        ),
                        canContinueTask = false,
                        failureCount = failureCount.value,
                        failureSummary = "/failed.txt" to "Failed",
                        queueResults = emptyList(),
                    ),
                    onDismiss = {},
                    onToResult = {},
                    onContinue = {},
                    onDelete = {},
                    onCancelTask = {},
                    onPauseTask = {},
                    onResumeTask = {},
                )
            }
        }

        listOf(
            StatusEnum.SUCCESS,
            StatusEnum.FAILURE,
            StatusEnum.LOADING,
            StatusEnum.PAUSE,
        ).forEach { taskStatus ->
            runOnIdle { status.value = taskStatus }
            waitForIdle()
            onNodeWithText(AppStrings.ui_view_results).assertIsDisplayed()
        }

        runOnIdle { failureCount.value = 0 }
        waitForIdle()
        onNodeWithText(AppStrings.ui_view_results).assertDoesNotExist()
    }
}
