package com.folderspan.ui.screen.sync

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.state.main.SyncEndpointType
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.SyncMode
import com.folderspan.ui.state.main.SyncConflictPolicy
import com.folderspan.ui.state.main.SyncScheduleType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.components.grid.GridList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncEditScreenTest {
    @Test fun scheduleInputRejectsInvalidTimeAndEmptyWeekdaysWithoutDiscardingHiddenDrafts() {
        assertEquals(0, parseSyncScheduleTime("00:00"))
        assertEquals(1439, parseSyncScheduleTime("23:59"))
        assertEquals(150, parseSyncScheduleTime("2:30"))
        for (value in listOf("", "24:00", "12:60", "-1:00", "02:3", "02:300", "２:30", "2.5:30"))
            assertEquals(null, parseSyncScheduleTime(value))
        assertEquals(AppStrings.sync_weekday_required, syncScheduleInputError(SyncScheduleType.Weekly, "02:30", 0))
        assertEquals(AppStrings.sync_invalid_schedule_time, syncScheduleInputError(SyncScheduleType.Daily, "24:00", 0))
        assertEquals(null, syncScheduleInputError(SyncScheduleType.Weekly, "02:30", 1 or 8))
        assertEquals(null, syncScheduleInputError(SyncScheduleType.Manual, "draft", 0))
        assertEquals(null, syncScheduleInputError(SyncScheduleType.Interval, "draft", 0))
        assertEquals("02:30", syncScheduleTimeText(150))
        for (type in DeviceType.entries) assertTrue(syncSchedulePlatformHint(type).isNotBlank())
    }

    @Test fun weeklySelectionRetainsTimeDaysAndPauseWhenChangingScheduleTypes() = runComposeUiTest {
        var type by mutableStateOf(SyncScheduleType.Weekly)
        var time by mutableStateOf("02:00")
        var weekdays by mutableStateOf(0)
        var enabled by mutableStateOf(true)
        setContent { MaterialTheme {
            SyncScheduleSettings(type, enabled, "15", time, weekdays, { type = it }, { enabled = it }, {}, { time = it }, { weekdays = it })
        } }
        onNodeWithText(AppStrings.sync_weekday_required).assertExists()
        onNodeWithTag("sync-schedule-weekday-0").performClick().assertIsSelected()
        onNodeWithTag("sync-schedule-weekday-3").performClick().assertIsSelected()
        onNodeWithTag("sync-schedule-time").performTextReplacement("03:45")
        onNodeWithTag("sync-schedule-enabled").performClick().assertIsOff()
        onNodeWithTag("sync-schedule-Daily").performClick()
        onNodeWithTag("sync-schedule-Weekly").performClick()
        onNodeWithTag("sync-schedule-weekday-0").assertIsSelected()
        onNodeWithTag("sync-schedule-weekday-3").assertIsSelected()
        assertEquals(1 or 8, weekdays)
        assertEquals("03:45", time)
        assertFalse(enabled)
        assertEquals(null, syncScheduleInputError(type, time, weekdays))
    }

    @Test fun modeSwitchesKeepCompatiblePoliciesAndProtectionBounds() {
        assertEquals(SyncConflictPolicy.Rename, syncConflictPolicyForMode(SyncConflictPolicy.Replace, SyncMode.BackupThenClean))
        assertEquals(SyncConflictPolicy.Skip, syncConflictPolicyForMode(SyncConflictPolicy.Skip, SyncMode.BackupThenClean))
        assertEquals(SyncConflictPolicy.Replace, syncConflictPolicyForMode(SyncConflictPolicy.Rename, SyncMode.Mirror))
        assertEquals(SyncConflictPolicy.Rename, syncConflictPolicyForMode(SyncConflictPolicy.Rename, SyncMode.Update))
        assertTrue(validSyncProtectionSettings("1", "1"))
        assertTrue(validSyncProtectionSettings("365", "100000"))
        for (retention in listOf("", "0", "366", "1.5")) assertFalse(validSyncProtectionSettings(retention, "100"))
        for (threshold in listOf("", "0", "100001", "1.5")) assertFalse(validSyncProtectionSettings("30", threshold))
        assertTrue(validSyncProtectionSettings("", "", SyncMode.Update))
        assertTrue(validSyncProtectionSettings("", "", SyncMode.BackupThenClean))
        assertTrue(validSyncProtectionSettings("", "100", SyncMode.Mirror, recycle = false))
        assertFalse(validSyncProtectionSettings("", "", SyncMode.Mirror, recycle = false))
    }

    @Test
    fun executionSettingsKeepInputAndValidateBounds() = runComposeUiTest {
        var retries by mutableStateOf("1")
        var timeout by mutableStateOf("120")
        var parallelism by mutableStateOf("1")
        setContent {
            MaterialTheme {
                GridList(modifier = Modifier.fillMaxSize()) {
                    syncExecutionSettings(retries, timeout, parallelism, { retries = it }, { timeout = it }, { parallelism = it })
                }
            }
        }
        onNodeWithTag("sync-retry-count").performTextReplacement("5")
        onNodeWithTag("sync-timeout-seconds").performTextReplacement("0")
        onNodeWithTag("sync-parallelism").performTextReplacement("8")
        assertTrue(validSyncExecutionSettings(retries, timeout, parallelism))
        onNodeWithTag("sync-timeout-seconds").performTextReplacement("29")
        assertFalse(validSyncExecutionSettings(retries, timeout, parallelism))
        assertEquals("29", timeout)
    }

    @Test
    fun executionSettingsRejectInvalidAndOutOfRangeValues() {
        assertTrue(validSyncExecutionSettings("0", "30", "1"))
        assertTrue(validSyncExecutionSettings("5", "3600", "8"))
        listOf("-1", "6", "", "1.5").forEach { assertFalse(validSyncExecutionSettings(it, "120", "1")) }
        listOf("-1", "29", "3601", "").forEach { assertFalse(validSyncExecutionSettings("1", it, "1")) }
        listOf("0", "9", "").forEach { assertFalse(validSyncExecutionSettings("1", "120", it)) }
    }

    @Test
    fun reverseWarningCancelDoesNotConfirmOrChangeInput() = runComposeUiTest {
        var confirmations = 0
        var dismissals = 0
        val task = SyncTask(id = 1, name = "reverse", sourceType = SyncEndpointType.Local, targetType = SyncEndpointType.Local, sourcePath = "/a", targetPath = "/b")
        setContent {
            MaterialTheme {
                SyncReverseTaskDialog(listOf(task), { confirmations++ }, { dismissals++ })
            }
        }
        onNodeWithText("reverse").assertExists()
        onNodeWithText(AppStrings.ui_cancel).performClick()
        assertEquals(0, confirmations)
        assertEquals(1, dismissals)
        assertEquals("/a", task.sourcePath)
        onNodeWithText(AppStrings.ui_confirm).performClick()
        assertEquals(1, confirmations)
    }

    @Test
    fun advancedIgnoreOnlySwitchTogglesAndPreservesSelectionWhenDisabled() = runComposeUiTest {
        var enabled by mutableStateOf(false)
        var selectionRequests = 0
        val selectedFileNames = listOf(".gitignore")

        setContent {
            MaterialTheme {
                AdvancedIgnoreConfiguration(
                    enabled = enabled,
                    selectedFileNames = selectedFileNames,
                    availableFileNames = listOf(".gitignore"),
                    onEnabledChange = { value -> enabled = value },
                    onSelectFiles = { selectionRequests++ },
                )
            }
        }

        onNodeWithText(AppStrings.ui_advanced_ignore).assert(hasClickAction().not())
        onNodeWithText(AppStrings.ui_advanced_ignore_description).assert(hasClickAction().not())
        onNodeWithTag("sync-advanced-ignore-switch")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(enabled)
        onNodeWithTag("sync-ignore-file-selector").performClick()
        assertEquals(1, selectionRequests)

        onNodeWithTag("sync-advanced-ignore-switch")
            .performClick()
            .assertIsOff()
        assertFalse(enabled)
        assertEquals(listOf(".gitignore"), selectedFileNames)
    }

    @Test
    fun ignoreFileDialogSupportsAccessibleMultipleSelection() = runComposeUiTest {
        var selectedFileNames by mutableStateOf(emptyList<String>())

        setContent {
            MaterialTheme {
                IgnoreFileSelectionDialog(
                    availableFileNames = listOf(".gitignore", ".dockerignore"),
                    selectedFileNames = selectedFileNames,
                    onSelectionChange = { fileNames -> selectedFileNames = fileNames },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("sync-ignore-file-.gitignore")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        onNodeWithTag("sync-ignore-file-.dockerignore")
            .assertIsOff()
            .performClick()
            .assertIsOn()

        assertEquals(listOf(".gitignore", ".dockerignore"), selectedFileNames)
        onNodeWithTag("sync-ignore-file-.gitignore").performClick().assertIsOff()
        assertEquals(listOf(".dockerignore"), selectedFileNames)
        onAllNodesWithTag("sync-ignore-file-.npmignore").assertCountEquals(0)
    }

    @Test
    fun advancedIgnoreHidesSelectorWhenSourceHasNoIgnoreFiles() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AdvancedIgnoreConfiguration(
                    enabled = true,
                    selectedFileNames = emptyList(),
                    availableFileNames = emptyList(),
                    onEnabledChange = {},
                    onSelectFiles = {},
                )
            }
        }

        onAllNodesWithTag("sync-ignore-file-selector").assertCountEquals(0)
        onAllNodesWithContentDescription(AppStrings.ui_select_ignore_files).assertCountEquals(0)
    }

    @Test
    fun ignoreFileSelectionEncodingNormalizesOrderAndDuplicates() {
        val encoded = encodeIgnoreFileSelection(
            listOf(".dockerignore", ".gitignore", ".dockerignore", "unsupported"),
        )

        assertEquals(listOf(".gitignore", ".dockerignore"), decodeIgnoreFileSelection(encoded))
    }

    @Test
    fun retainAvailableIgnoreFileSelectionRemovesMissingAndUnsupportedNames() {
        assertEquals(
            listOf(".gitignore"),
            retainAvailableIgnoreFileSelection(
                selectedFileNames = listOf(".dockerignore", ".gitignore", "unsupported"),
                availableFileNames = listOf(".gitignore", ".npmignore"),
            ),
        )
    }

    @Test
    fun syncSourceChangedDetectsTypeEndpointAndPathChangesOnly() {
        assertFalse(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Local,
                currentRef = "",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Device,
                currentRef = "device-1",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Device,
                previousRef = "device-1",
                previousPath = "/source",
                currentType = SyncEndpointType.Device,
                currentRef = "device-2",
                currentPath = "/source",
            )
        )
        assertTrue(
            syncSourceChanged(
                previousType = SyncEndpointType.Local,
                previousRef = "",
                previousPath = "/source",
                currentType = SyncEndpointType.Local,
                currentRef = "",
                currentPath = "/other",
            )
        )
    }
}
