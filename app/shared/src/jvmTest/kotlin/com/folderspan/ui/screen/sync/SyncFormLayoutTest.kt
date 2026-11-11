package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.state.main.SyncBidiConflictPolicy
import com.folderspan.ui.state.main.SyncMode
import org.jetbrains.skia.Image
import strings.AppStrings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncFormLayoutTest : ChineseLocalizationTest() {
    @Test
    fun modeAndExecutionFieldsMatchTaskNameColumnAndRemainEditableAcrossWindowSizes() {
        val evidence = Path.of("build", "sync-form-ui-evidence")
        Files.createDirectories(evidence)
        for (size in listOf(
            Size(390f, 800f), Size(720f, 900f), Size(1024f, 768f),
            Size(1366f, 900f), Size(1700f, 900f), Size(680f, 360f),
        )) runSkikoComposeUiTest(size = size) {
            var retries by mutableStateOf("1")
            var timeout by mutableStateOf("120")
            var parallelism by mutableStateOf("1")
            var mode by mutableStateOf(SyncMode.Update)
            setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        GridList(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalSpacing = 16.dp,
                            horizontalSpacing = 16.dp,
                        ) {
                            item {
                                OutlinedTextField("照片备份", {},
                                    label = { Text(AppStrings.ui_task_name) },
                                    modifier = Modifier.fillMaxWidth().testTag("sync-task-name"),
                                    singleLine = true)
                            }
                            syncModeSettings(mode, SyncBidiConflictPolicy.KeepBoth, true, "30", "100",
                                { mode = it }, {}, {}, {}, {})
                            syncExecutionSettings(retries, timeout, parallelism,
                                { retries = it }, { timeout = it }, { parallelism = it })
                        }
                    }
                }
            }
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            val taskName = onNodeWithTag("sync-task-name").fetchSemanticsNode().boundsInRoot
            val grid = onNode(hasScrollToIndexAction())
            val fields = listOf("sync-retry-count", "sync-timeout-seconds", "sync-parallelism")
            for (tag in listOf("sync-mode") + fields) {
                grid.performScrollToNode(hasTestTag(tag))
                val field = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(field.width > 0f && field.left >= root.left && field.right <= root.right,
                    "控件不应越过窗口边界：tag=$tag, field=$field, root=$root")
                assertTrue(abs(field.width - taskName.width) <= 1f,
                    "模式和执行参数应与任务名称共享一列宽度：tag=$tag, field=$field, taskName=$taskName")
            }
            grid.performScrollToNode(hasTestTag("sync-mode"))
            onNodeWithTag("sync-mode").performClick()
            onNodeWithText(AppStrings.sync_mode_backup_clean).performClick()
            for ((tag, value) in fields.zip(listOf("2", "90", "3"))) {
                grid.performScrollToNode(hasTestTag(tag))
                onNodeWithTag(tag).assertIsDisplayed().performTextReplacement(value)
            }
            runOnIdle {
                assertEquals(SyncMode.BackupThenClean, mode)
                assertEquals("2", retries)
                assertEquals("90", timeout)
                assertEquals("3", parallelism)
            }
            grid.performScrollToNode(hasTestTag("sync-task-name"))
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            Files.write(evidence.resolve("execution-${size.width.toInt()}x${size.height.toInt()}.png"),
                Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
        }
    }

    @Test
    fun existingAndNewSwitchRowsAlignAndRemainEditable() {
        val evidence = Path.of("build", "sync-form-ui-evidence")
        Files.createDirectories(evidence)
        for (size in listOf(Size(390f, 800f), Size(1024f, 768f))) runSkikoComposeUiTest(size = size) {
            var ignore by mutableStateOf(false)
            var device by mutableStateOf(false)
            setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            Column(Modifier.fillMaxWidth().testTag("sync-form-content")) {
                                AdvancedIgnoreConfiguration(ignore, emptyList(), emptyList(), { ignore = it }, {})
                                SyncTriggerSettings(device, false, false, false, { device = it }, {}, {}, {},
                                    supportsConditions = false)
                            }
                        }
                    }
                }
            }
            val oldSwitch = onNodeWithTag("sync-advanced-ignore-switch").fetchSemanticsNode().boundsInRoot
            val newSwitch = onNodeWithTag("sync-event-device").fetchSemanticsNode().boundsInRoot
            val oldLabel = onNodeWithText(AppStrings.ui_advanced_ignore).fetchSemanticsNode().boundsInRoot
            val newLabel = onNodeWithText(AppStrings.sync_event_device).fetchSemanticsNode().boundsInRoot
            val content = onNodeWithTag("sync-form-content").fetchSemanticsNode().boundsInRoot
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            Files.write(evidence.resolve("switches-${size.width.toInt()}x${size.height.toInt()}.png"),
                Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
            assertTrue(abs(oldSwitch.right - newSwitch.right) < 1f,
                "新旧开关应右对齐：old=$oldSwitch, new=$newSwitch, content=$content")
            assertTrue(abs(oldLabel.left - newLabel.left) < 1f,
                "新旧标签应左对齐：old=$oldLabel, new=$newLabel, content=$content")
            for (label in listOf(oldLabel, newLabel)) {
                assertTrue(abs(label.left - content.left) < 1f,
                    "开关标签应贴合内容左边界，不增加行内留白：label=$label, content=$content")
            }
            for (toggle in listOf(oldSwitch, newSwitch)) {
                assertTrue(abs(toggle.right - content.right) < 1f,
                    "开关应贴合内容右边界，不增加行内留白：toggle=$toggle, content=$content")
            }
            onNodeWithTag("sync-advanced-ignore-switch").performClick().assertIsOn()
            onNodeWithTag("sync-event-device").performClick().assertIsOn()
            runOnIdle {
                assertTrue(ignore)
                assertTrue(device)
            }
        }
    }
}
