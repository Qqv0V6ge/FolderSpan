package com.folderspan.ui.screen.file.share

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.FileShareStatus
import strings.AppStrings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeviceShareSelectionTest : ChineseLocalizationTest() {
    @Test
    fun rowExposesCheckboxStateAndSupportsPointerAndKeyboardToggle() = runComposeUiTest {
        var selected by mutableStateOf(false)
        setContent {
            MaterialTheme {
                ShareToDeviceListItem(
                    device = device(), sendStatus = null, sendMessage = null, onCancel = {},
                    onClick = { selected = !selected }, modifier = Modifier.testTag("device"),
                    selectionMode = true, selected = selected,
                )
            }
        }
        onNodeWithTag("device").assertIsOff().performClick().assertIsOn()
        onNodeWithTag("device").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("device").assertIsOff()
    }

    @Test
    fun busyRowCannotBeSelectedButCanStillCancelItsOwnShare() = runComposeUiTest {
        var selected = false
        var cancelled = false
        setContent {
            MaterialTheme {
                ShareToDeviceListItem(
                    device = device(), sendStatus = FileShareStatus.WAITING, sendMessage = null,
                    onCancel = { cancelled = true }, onClick = { selected = true },
                    modifier = Modifier.testTag("busy-device"), selectionMode = true, selectionEnabled = false,
                )
            }
        }
        onNodeWithTag("busy-device").assertIsNotEnabled().performClick()
        onNodeWithContentDescription(AppStrings.ui_cancel).performClick()
        runOnIdle {
            assertEquals(false, selected)
            assertTrue(cancelled)
        }
    }

    @Test
    fun bottomSheetKeepsActionsVisibleWhileDevicesScrollAcrossWindowSizes() {
        val evidence = File("build/reports/device-share-ui").apply { mkdirs() }
        val devices = listOf("客厅电脑", "手机", "工作电脑", "平板", "笔记本").mapIndexed { index, name ->
            SocketDevice("device-$index", name, "/", type = DeviceType.JVM)
        }
        for (size in listOf(
            Size(360f, 800f), Size(720f, 900f), Size(1024f, 768f),
            Size(1366f, 900f), Size(1600f, 900f), Size(640f, 300f),
        )) runSkikoComposeUiTest(size = size) {
            var selectedIds by mutableStateOf<Set<String>>(emptySet())
            var sent = false
            var exited = false
            setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        DeviceShareSelectionSheet(
                            devices = devices, sendStatuses = emptyMap(), sendMessages = emptyMap(),
                            selectedDeviceIds = selectedIds, canSend = selectedIds.isNotEmpty(),
                            onToggleDevice = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            onToggleAll = {
                                selectedIds = if (selectedIds.size == devices.size) emptySet() else devices.map { it.id }.toSet()
                            },
                            onCancelShare = {}, onDismiss = { exited = true }, onSend = { sent = true },
                        )
                    }
                }
            }
            onNodeWithText(AppStrings.ui_select_device_send).assertIsDisplayed()
            onNodeWithText(AppStrings.ui_send).assertIsNotEnabled()
            onNodeWithText(devices.first().name).performClick()
            runOnIdle { assertEquals(setOf(devices.first().id), selectedIds) }
            onNodeWithText(AppStrings.ui_select_all).performClick()
            onNodeWithText(AppStrings.ui_deselect_all).assertIsDisplayed()
            onNode(hasScrollToIndexAction()).performScrollToIndex(devices.lastIndex)
            onNodeWithText(devices.last().name).assertIsDisplayed()
            val send = onNodeWithText(AppStrings.ui_send).assertIsDisplayed()
            val bounds = send.fetchSemanticsNode().boundsInRoot
            val root = onAllNodes(isRoot()).onLast().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
            assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
            ImageIO.write(
                onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png",
                File(evidence, "bottom-sheet-${size.width.toInt()}x${size.height.toInt()}.png"),
            )
            send.performClick()
            onNodeWithContentDescription(AppStrings.ui_exit_device_selection).performClick()
            runOnIdle {
                assertTrue(sent)
                assertTrue(exited)
            }
        }
    }

    private fun device() = SocketDevice("test", "FolderSpan", "/", type = DeviceType.JVM)
}
