package com.folderspan.ui.components.file

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.combinedClickableWithContextClick
import com.folderspan.ui.components.drawer.LocalFileDropRow
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.state.file.FileDropEndpoint
import com.folderspan.ui.state.file.FileDropTargetKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class FileDragDropTest : ChineseLocalizationTest() {
    @AfterTest
    fun cleanup() {
        FileDropSessionRegistry.end()
    }

    @Test
    fun touchLongPressThenMoveDragsWithoutOpeningOrChangingSelection() = runComposeUiTest {
        var opens = 0
        var menus = 0
        val file = file("one.txt")
        val selected = mutableListOf(file, file("two.txt"))
        setContent {
            MaterialTheme {
                Box(
                    Modifier.size(200.dp).testTag("source")
                        .fileDragSource(file, { selected.toList() }, { _, _ -> }) { menus++ }
                        .combinedClickableWithContextClick({ opens++ }, { menus++ }, deferTouchLongClick = true),
                )
            }
        }
        onNodeWithTag("source").performTouchInput {
            down(center)
            advanceEventTime(700L)
            moveBy(Offset(60f, 0f))
        }
        runOnIdle {
            assertEquals(2, FileDropSessionRegistry.session.value?.files?.size)
            selected.clear()
            assertEquals(2, FileDropSessionRegistry.session.value?.files?.size)
        }
        onNodeWithTag("source").performTouchInput { up() }
        runOnIdle {
            assertEquals(0, opens)
            assertEquals(0, menus)
            assertNull(FileDropSessionRegistry.session.value)
        }
    }

    @Test
    fun stationaryLongPressKeepsContextMenu() = runComposeUiTest {
        var opens = 0
        var menus = 0
        val file = file("one.txt")
        setContent {
            Box(
                Modifier.size(200.dp).testTag("source")
                    .fileDragSource(file, { emptyList() }, { _, _ -> }) { menus++ }
                    .combinedClickableWithContextClick({ opens++ }, { menus++ }, deferTouchLongClick = true),
            )
        }
        onNodeWithTag("source").performTouchInput {
            down(center)
            advanceEventTime(700L)
            up()
        }
        runOnIdle {
            assertEquals(0, opens)
            assertEquals(1, menus)
            assertNull(FileDropSessionRegistry.session.value)
        }
    }

    @Test
    fun mouseDragStartsWithoutLongPressAndRightClickStillOpensMenu() = runComposeUiTest {
        var opens = 0
        var menus = 0
        val file = file("one.txt")
        setContent {
            Box(
                Modifier.size(200.dp).testTag("source")
                    .fileDragSource(file, { emptyList() }, { _, _ -> }) { menus++ }
                    .combinedClickableWithContextClick({ opens++ }, { menus++ }, deferTouchLongClick = true),
            )
        }
        onNodeWithTag("source").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(60f, 0f))
        }
        runOnIdle { assertEquals(1, FileDropSessionRegistry.session.value?.files?.size) }
        onNodeWithTag("source").performMouseInput { release() }
        waitForIdle()
        onNodeWithTag("source").performMouseInput { click(button = MouseButton.Secondary) }
        runOnIdle {
            assertEquals(0, opens)
            assertEquals(1, menus)
        }
    }

    @Test
    fun ordinaryClickAndRightClickRemainAvailableWithoutDragging() = runComposeUiTest {
        var opens = 0
        var menus = 0
        val file = file("one.txt")
        setContent {
            Box(
                Modifier.size(200.dp).testTag("source")
                    .fileDragSource(file, { emptyList() }, { _, _ -> }) { menus++ }
                    .combinedClickableWithContextClick({ opens++ }, { menus++ }, deferTouchLongClick = true),
            )
        }
        onNodeWithTag("source").performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("source").performMouseInput { click(button = MouseButton.Secondary) }
        runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, menus)
            assertNull(FileDropSessionRegistry.session.value)
        }
    }

    @Test
    fun movementBeforeTouchLongPressRemainsAvailableForScrolling() = runComposeUiTest {
        val file = file("one.txt")
        setContent {
            Box(Modifier.size(200.dp).testTag("source").fileDragSource(file, { emptyList() }, { _, _ -> }, null))
        }
        onNodeWithTag("source").performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            up()
        }
        runOnIdle { assertNull(FileDropSessionRegistry.session.value) }
    }

    @Test
    fun localDropRowIsNotAClickNavigationActionAndShowsDestinationWhenHovered() = runComposeUiTest {
        setContent { MaterialTheme { LocalFileDropRow("/Documents", hovered = true) } }
        onNodeWithText(AppStrings.ui_local).assertHasNoClickAction()
        onNodeWithText(AppStrings.ui_drop_copy_received.format(arg0 = "/Documents")).assertHasNoClickAction()
    }

    @Test
    fun dropCapturesSelectionAtPressAndDestinationBeforeTransientTargetDisappears() = runComposeUiTest {
        val first = file("one.txt")
        val selected = mutableListOf(first, file("two.txt"))
        val endpoint = FileDropEndpoint(FileDropTargetKind.Local)
        var delivered = emptyList<FileSimpleInfo>()
        var submissions = 0
        setContent {
            val session by FileDropSessionRegistry.session.collectAsState()
            Row {
                Box(
                    Modifier.size(200.dp).testTag("source").fileDragSource(first, { selected.toList() }, { target, files ->
                        assertEquals(endpoint, target)
                        assertNotNull(FileDropSessionRegistry.session.value)
                        delivered = files
                        submissions++
                        FileDropSessionRegistry.end()
                    }, null),
                )
                if (session != null) {
                    Box(Modifier.size(200.dp).testTag("target").fileDropTarget(endpoint))
                }
            }
        }
        onNodeWithTag("source").performMouseInput {
            moveTo(center)
            press()
        }
        runOnIdle { selected.clear() }
        onNodeWithTag("source").performMouseInput { moveTo(center + Offset(160f, 0f)) }
        waitForIdle()
        onNodeWithTag("source").performMouseInput { release() }
        runOnIdle {
            assertEquals(1, submissions)
            assertEquals(listOf("one.txt", "two.txt"), delivered.map { it.name })
            assertNull(FileDropSessionRegistry.session.value)
        }
        onNodeWithTag("target").assertDoesNotExist()
    }

    private fun file(name: String): FileSimpleInfo = FileSimpleInfo.nullFileSimpleInfo().copy(name = name, path = "/$name")
}
