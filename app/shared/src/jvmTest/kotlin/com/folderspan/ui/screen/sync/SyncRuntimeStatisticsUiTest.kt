package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.service.operation.TransferWaitReason
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.model.StringMapUiState
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.SyncEndpointType
import com.folderspan.ui.state.main.SyncRunStatus
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SyncRuntimeStatisticsUiTest : ChineseLocalizationTest() {
    private val task = SyncTask(1, "同步中", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", parallelism = 8, lastStatus = SyncRunStatus.Running)

    @Test fun showsActualConcurrencyAndNeutralWaitThenRecovery() = runSkikoComposeUiTest(size = Size(720f, 600f)) {
        var stats by mutableStateOf(SyncRuntimeStatistics("1 MB/s", 1, setOf(TransferWaitReason.DeviceBusy, TransferWaitReason.Backoff)))
        setContent { MaterialTheme { Surface { SyncTaskCard(task, true, StringMapUiState(emptyMap()),
            false, false, {}, {}, {}, {}, {}, {}, {}, {}, runtime = stats) } } }
        onNodeWithText(strings.AppStrings.sync_current_file_parallelism.format(arg0 = "1")).assertExists()
        onNodeWithText(strings.AppStrings.sync_current_file_parallelism.format(arg0 = "8")).assertDoesNotExist()
        onNodeWithText(strings.AppStrings.sync_wait_backoff, substring = true).assertExists()
        onNodeWithText(strings.AppStrings.ui_sync_failed).assertDoesNotExist()
        runOnIdle { stats = SyncRuntimeStatistics("8 MB/s", 3, emptySet()) }
        onNodeWithText(strings.AppStrings.sync_current_file_parallelism.format(arg0 = "3")).assertExists()
        onNodeWithText(strings.AppStrings.sync_wait_backoff, substring = true).assertDoesNotExist()
        onNodeWithText(strings.AppStrings.ui_speed_arg0.format(arg0 = "8 MB/s")).assertExists()
    }

    @Test fun foreignTaskDoesNotShowLocalRuntimeAndMissingMetricsStayUnknown() = runSkikoComposeUiTest(size = Size(720f, 600f)) {
        val absent = syncRuntimeStatistics(emptyList(), emptySet())
        assertEquals(null, absent.speed)
        assertEquals(0, absent.parallelism)
        setContent { MaterialTheme { Surface { SyncTaskCard(task, false, StringMapUiState(emptyMap()),
            false, false, {}, {}, {}, {}, {}, {}, {}, {}, runtime = SyncRuntimeStatistics("8 MB/s", 3, setOf(TransferWaitReason.Receiver))) } } }
        onNodeWithTag("sync-runtime-1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun runtimeSamplerStopsWhenTheTaskStopsRunning() = runSkikoComposeUiTest(size = Size(720f, 600f)) {
        var running by mutableStateOf(true)
        val samples = java.util.concurrent.atomic.AtomicInteger()
        setContent { MaterialTheme { Surface {
            val statistics = rememberSyncRuntimeStatistics(1, running) {
                samples.incrementAndGet()
                SyncRuntimeStatistics("1 MB/s", 1, emptySet())
            }
            if (statistics != null) SyncRuntimeStatisticsView(1, statistics)
        } } }
        waitUntil(timeoutMillis = 5_000) { samples.get() > 0 }
        runOnIdle { running = false }
        onNodeWithTag("sync-runtime-1", useUnmergedTree = true).assertDoesNotExist()
        val stopped = samples.get()
        mainClock.advanceTimeBy(1_500)
        runOnIdle { assertEquals(stopped, samples.get()) }
    }

    @Test fun runtimeRowsFitFiveWidthsAndTheShortWindow() {
        for ((width, height) in listOf(390 to 800, 720 to 900, 1024 to 768, 1366 to 900, 1700 to 900, 680 to 360)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SyncTaskCard(task, true, StringMapUiState(emptyMap()), false, false, {}, {}, {}, {}, {}, {}, {}, {},
                        runtime = SyncRuntimeStatistics("12 MB/s", 2, TransferWaitReason.entries.toSet()))
                } } } }
                onNodeWithTag("sync-runtime-1", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText(strings.AppStrings.sync_current_file_parallelism.format(arg0 = "2")).assertIsDisplayed()
                val path = Path.of("build/sync-runtime-ui-evidence/runtime-${width}x$height.png")
                Files.createDirectories(path.parent)
                val screenshot = onRoot().captureToImage()
                Files.write(path, Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData()!!.bytes)
            }
        }
    }
}
