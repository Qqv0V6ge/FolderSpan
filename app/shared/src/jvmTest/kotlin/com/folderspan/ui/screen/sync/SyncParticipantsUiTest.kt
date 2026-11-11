package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.ui.state.main.*
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import strings.AppStrings
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SyncParticipantsUiTest : ChineseLocalizationTest() {
    private val chosen = StorageDestination(ref = "chosen-target", path = "/archive-b")
    private val task = SyncTask(1, "照片同步", sourceType = SyncEndpointType.Local, sourcePath = "/selected/photos",
        targetType = SyncEndpointType.Network, targetRef = "unused-candidate", targetPath = "/unused",
        mode = SyncMode.Bidirectional, destinations = listOf(StorageDestination(ref = "unused-candidate", path = "/unused"), chosen),
        selectRecommended = true)
    private val attention = SyncTaskAttention("definition", true, 0, false, fixedTargets = listOf(chosen))
    private val record = SyncRunRecord(1, 1, "manual", 10, 20, SyncRunStatus.Success, 1, 1, 0,
        policySnapshot = task.withMode(SyncMode.BackupThenClean), participants = listOf(
            SyncRunParticipant("source", "所选照片", SyncEndpointType.Local, "", listOf("/selected/photos")),
            SyncRunParticipant("target-0", "Archive B", SyncEndpointType.Network, chosen.ref, listOf(chosen.path))))

    @Composable private fun TaskCard(owned: Boolean = true) = SyncTaskCard(task, owned, StringMapUiState(emptyMap()),
        selectionMode = false, isSelected = false, onSelectionToggle = {}, onClick = {}, onViewDetail = {},
        onRun = {}, onToggle = {}, onEdit = {}, onDuplicate = {}, onDelete = {}, attention = attention)

    @Composable private fun RecordCard() = SyncRunRecordCard(task, record, emptyMap(), onRetry = {},
        onTargetRetry = {}, onItemRetry = { _, _ -> }, onControl = {}, canControl = false)

    @Test fun confirmedTaskShowsItsChosenTargetInsteadOfTheFirstCandidate() = runSkikoComposeUiTest(size = Size(720f, 600f)) {
        setContent { MaterialTheme { Surface { TaskCard() } } }
        onNodeWithText(AppStrings.sync_fixed_recommended_target).assertExists()
        onNodeWithText(chosen.ref, substring = true).assertExists()
        onNodeWithText("unused-candidate", substring = true).assertDoesNotExist()
    }

    @Test fun foreignTaskDoesNotDisplayThisDevicesFixedTarget() = runSkikoComposeUiTest(size = Size(720f, 600f)) {
        setContent { MaterialTheme { Surface { TaskCard(owned = false) } } }
        onNodeWithText(AppStrings.sync_fixed_recommended_target).assertDoesNotExist()
        onNodeWithText(chosen.ref, substring = true).assertDoesNotExist()
        onNodeWithText(AppStrings.sync_recommended_candidates_arg0.format(arg0 = "2"), substring = true).assertExists()
    }

    @Test fun historyShowsItsCapturedModeAndParticipantsAfterCurrentSettingsChange() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        setContent { MaterialTheme { Surface { RecordCard() } } }
        onNodeWithText(AppStrings.sync_mode_backup_clean).assertExists()
        onNodeWithText(AppStrings.sync_mode_bidirectional).assertDoesNotExist()
        onNodeWithText("Archive B").assertExists()
        onNodeWithText(chosen.path).assertExists()
        onNodeWithText("unused-candidate", substring = true).assertDoesNotExist()
    }

    @Test fun participantsFitFiveWidthsAndTheShortScrollableWindow() {
        for ((width, height) in listOf(390 to 800, 720 to 900, 1024 to 768, 1366 to 900, 1700 to 900, 680 to 360)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TaskCard(); RecordCard() }
                } } }
                onNodeWithText(AppStrings.sync_fixed_recommended_target).assertIsDisplayed()
                if (height == 360) onNodeWithText(chosen.path).performScrollTo()
                onNodeWithText("Archive B").assertIsDisplayed()
                val path = Path.of("build/sync-group-ui-evidence/group-${width}x$height.png")
                Files.createDirectories(path.parent)
                Files.write(path, Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            }
        }
    }
}
