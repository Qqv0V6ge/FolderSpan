package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.ui.state.main.SyncEndpointType
import com.folderspan.ui.state.main.SyncRunRecord
import com.folderspan.ui.state.main.SyncRunStatus
import com.folderspan.ui.state.main.SyncScheduleType
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.SyncMode
import com.folderspan.ui.state.main.SyncTaskAttention
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class SyncTaskPresentationTest {
    @Test fun weeklyTaskShowsTimeDaysNextRunAndScheduleToggle() = runComposeUiTest {
        val scheduled = task.copy(scheduleType = SyncScheduleType.Weekly, scheduleTimeMinutes = 225,
            scheduleWeekdays = 1 or 8, nextRunAt = 1_793_708_100_000)
        var toggles = 0
        setContent { MaterialTheme {
            SyncTaskCard(scheduled, true, StringMapUiState(emptyMap()), false, false,
                {}, {}, {}, {}, { toggles++ }, {}, {}, {})
        } }
        onNodeWithText(syncScheduleLabel(scheduled)).assertExists()
        onNodeWithText(AppStrings.sync_next_run_arg0.format(arg0 = scheduled.nextRunAt.timestampToYMDHM())).assertExists()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.ui_turn_off_scheduled_execution).performClick()
        assertEquals(1, toggles)
    }

    private val task = SyncTask(
        id = 1, name = "Documents", sourceType = SyncEndpointType.Local,
        sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target",
        scheduleType = SyncScheduleType.Interval, ownerDeviceId = "other", ownerDeviceName = "Laptop",
    )

    @Test
    fun foreignTaskOffersTakeoverWithoutRunOrScheduleToggle() = runComposeUiTest {
        var takeovers = 0
        setContent {
            MaterialTheme {
                SyncTaskCard(
                    task = task.copy(mode = SyncMode.Mirror), ownedByCurrentDevice = false, deviceNameByIdUiState = StringMapUiState(emptyMap()),
                    selectionMode = false, isSelected = false, onSelectionToggle = {}, onClick = {},
                    onViewDetail = {}, onRun = {}, onToggle = {}, onEdit = { takeovers++ },
                    onDuplicate = {}, onDelete = {},
                )
            }
        }
        onNodeWithText(AppStrings.sync_owner_arg0.format(arg0 = "Laptop")).assertExists()
        onNodeWithContentDescription(AppStrings.ui_execute_immediately).assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.ui_edit).assertDoesNotExist()
        onNodeWithText(AppStrings.sync_preview_title).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_turn_off_scheduled_execution).assertDoesNotExist()
        onNodeWithText(AppStrings.sync_take_ownership).performClick()
        assertEquals(1, takeovers)
    }

    @Test
    fun ownedTaskShowsModeAndPendingDeletionsAndOpensTheCorrectPreview() = runComposeUiTest {
        var previews = 0
        var pending = 0
        setContent { MaterialTheme {
            SyncTaskCard(task.copy(mode = SyncMode.Mirror), true, StringMapUiState(emptyMap()),
                false, false, {}, {}, {}, {}, {}, {}, {}, {},
                attention = SyncTaskAttention("fp", false, 101, false, 42),
                onPreview = { previews++ }, onPendingDeletions = { pending++ })
        } }
        onNodeWithText(AppStrings.sync_mode_mirror).assertExists()
        onNodeWithText(AppStrings.sync_preview_required).assertExists()
        onNodeWithText(AppStrings.sync_pending_count.format(arg0 = "101")).assertExists()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.sync_preview_title).performClick()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.sync_pending_deletions_title).performClick()
        assertEquals(1, previews)
        assertEquals(1, pending)
    }

    @Test
    fun updateTaskOpensPreviewWithoutRequiringFirstConfirmation() = runComposeUiTest {
        var previews = 0
        setContent { MaterialTheme {
            SyncTaskCard(task.copy(mode = SyncMode.Update), true, StringMapUiState(emptyMap()),
                false, false, {}, {}, {}, {}, {}, {}, {}, {}, onPreview = { previews++ })
        } }
        onNodeWithText(AppStrings.sync_preview_required).assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.sync_preview_title).performClick()
        assertEquals(1, previews)
    }

    @Test
    fun modeFilterOffersAllModesAndCanReset() = runComposeUiTest {
        var selected: SyncMode? = null
        setContent { MaterialTheme { SyncModeFilterRow(selected) { selected = it } } }
        onNodeWithText(AppStrings.sync_mode_bidirectional).performClick()
        assertEquals(SyncMode.Bidirectional, selected)
        onNodeWithText(AppStrings.ui_all).performClick()
        assertEquals(null, selected)
    }

    @Test
    fun recordShowsClassifiedResultsAndHidesForeignRetry() = runComposeUiTest {
        val record = record().copy(copiedCount = 3, unchangedCount = 4997, failureCount = 2)
        setContent {
            MaterialTheme {
                SyncRunRecordCard(
                    task = task, record = record, targetNames = emptyMap(), onRetry = {}, onTargetRetry = {},
                    onItemRetry = { _, _ -> }, onControl = {}, canControl = false,
                )
            }
        }
        onNodeWithText(AppStrings.sync_counts_arg0_arg1_arg2_arg3_arg4.format(
            arg0 = "3", arg1 = "0", arg2 = "0", arg3 = "4997", arg4 = "0",
        )).assertExists()
        onNodeWithText(AppStrings.ui_retry_failed_items).assertDoesNotExist()
        onNodeWithText(AppStrings.sync_show_failures).assertExists()
    }

    @Test
    fun averageRateUsesTransferDurationAndHandlesMissingStatistics() {
        assertEquals(2 * 1024 * 1024L, syncAverageBytesPerSecond(record()))
        assertEquals(0L, syncAverageBytesPerSecond(record().copy(transferDurationMillis = 0)))
    }

    private fun record() = SyncRunRecord(
        runId = 2, taskId = 1, trigger = "manual", startedAt = 1, endedAt = 50_001,
        status = SyncRunStatus.PartialSuccess, totalCount = 5002, successCount = 5000, failureCount = 2,
        transferredBytes = 100 * 1024 * 1024L, transferDurationMillis = 50_000,
    )
}
