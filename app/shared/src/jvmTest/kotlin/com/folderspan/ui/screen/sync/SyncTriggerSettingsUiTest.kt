package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.sync.SyncRunCondition
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.SyncEndpointType
import org.jetbrains.skia.Image
import strings.AppStrings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncTriggerSettingsUiTest : ChineseLocalizationTest() {
    @Test fun desktopAndWebKeepMobileConditionsInactiveAndStaleDialogSettingsCannotBeIgnored() {
        assertTrue(supportsSyncRunConditions(DeviceType.Android))
        assertTrue(supportsSyncRunConditions(DeviceType.IOS))
        assertFalse(supportsSyncRunConditions(DeviceType.JVM))
        assertFalse(supportsSyncRunConditions(DeviceType.JS))
        val task = SyncTask(1, "Photos", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/target", ownerDeviceId = "local", requireWifi = true)
        assertTrue(sameSyncConditionSettings(task, task.copy(updatedAt = 1)))
        assertFalse(sameSyncConditionSettings(task, task.copy(requireCharging = true)))
        assertFalse(sameSyncConditionSettings(task, task.copy(ownerDeviceId = "other")))
        assertFalse(sameSyncConditionSettings(task, null))
    }

    @Test fun mobileSettingsAreEditableAndReachableAcrossWidthsAndAShortWindow() {
        val evidence = Path.of("build", "sync-condition-ui-evidence")
        Files.createDirectories(evidence)
        for (size in listOf(Size(390f, 800f), Size(720f, 900f), Size(1024f, 768f), Size(1366f, 900f),
            Size(1700f, 900f), Size(680f, 360f))) runSkikoComposeUiTest(size = size) {
            var device by mutableStateOf(false)
            var network by mutableStateOf(false)
            var wifi by mutableStateOf(false)
            var charging by mutableStateOf(false)
            setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SyncTriggerSettings(device, network, wifi, charging, { device = it }, { network = it },
                        { wifi = it }, { charging = it }, supportsConditions = true)
                }
            } } }
            onNodeWithTag("sync-event-device").performScrollTo().performClick().assertIsOn()
            onNodeWithTag("sync-event-network").performScrollTo().performClick().assertIsOn()
            onNodeWithTag("sync-require-wifi").performScrollTo().performClick().assertIsOn()
            onNodeWithTag("sync-require-charging").performScrollTo().performClick().assertIsOn()
            onNodeWithText(AppStrings.sync_conditions_hint).performScrollTo().assertIsDisplayed()
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            Files.write(evidence.resolve("conditions-${size.width.toInt()}x${size.height.toInt()}.png"), Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
        }
    }

    @Test fun unsupportedPlatformHidesConditionControlsAndRetainsEvents() = runSkikoComposeUiTest {
        var changes = 0
        setContent { MaterialTheme {
            SyncTriggerSettings(true, true, true, true, { changes++ }, { changes++ }, { changes++ }, { changes++ }, supportsConditions = false)
        } }
        onNodeWithTag("sync-require-wifi").assertDoesNotExist()
        onNodeWithTag("sync-require-charging").assertDoesNotExist()
        onNodeWithText(AppStrings.sync_conditions_unsupported).assertIsDisplayed()
        onNodeWithTag("sync-event-device").performClick()
        runOnIdle { assertEquals(1, changes) }
    }

    @Test fun overrideRequiresAnExplicitConfirmationAndDismissalDoesNotRun() = runSkikoComposeUiTest {
        var visible by mutableStateOf(true)
        var runs = 0
        setContent { MaterialTheme {
            if (visible) SyncConditionOverrideDialog(setOf(SyncRunCondition.Wifi, SyncRunCondition.Charging),
                onConfirm = { runs++; visible = false }, onDismiss = { visible = false })
        } }
        onNodeWithText(AppStrings.sync_ignore_conditions_hint.format(arg0 = syncConditionsLabel(setOf(SyncRunCondition.Wifi, SyncRunCondition.Charging)))).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_cancel).performClick()
        runOnIdle { assertEquals(0, runs); visible = true }
        onNodeWithTag("sync-ignore-conditions").performClick()
        runOnIdle { assertEquals(1, runs) }
        onNodeWithTag("sync-condition-confirmation").assertDoesNotExist()
    }

    @Test fun waitingReasonAndCancelActionAreOnlyShownForTheExecutionOwner() = runSkikoComposeUiTest {
        val task = SyncTask(1, "Photos", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/target", ownerDeviceId = "local")
        var owned by mutableStateOf(true)
        var canceled = 0
        setContent { MaterialTheme {
            SyncTaskCard(task, owned, StringMapUiState(emptyMap()), false, false, {}, {}, {}, {}, {}, {}, {}, {},
                waitingConditions = setOf(SyncRunCondition.Charging), onCancelWaiting = { canceled++ })
        } }
        onNodeWithText(syncWaitingConditionsLabel(setOf(SyncRunCondition.Charging))).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.sync_cancel_waiting).performClick()
        runOnIdle { assertEquals(1, canceled); owned = false }
        onNodeWithText(syncWaitingConditionsLabel(setOf(SyncRunCondition.Charging))).assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.sync_cancel_waiting).assertDoesNotExist()
    }
}
