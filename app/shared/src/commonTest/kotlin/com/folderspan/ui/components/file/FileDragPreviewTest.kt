package com.folderspan.ui.components.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.FileDragSession
import com.folderspan.ui.state.file.FileDropEndpoint
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.state.file.FileDropTargetKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class FileDragPreviewTest : ChineseLocalizationTest() {
    @AfterTest
    fun cleanup() {
        FileDropSessionRegistry.end()
    }

    @Test
    fun previewFollowsPointerInRootCoordinatesAtHighDensityAndInRtlLayout() = runComposeUiTest {
        val file = file("report.pdf")
        val session = mutableStateOf(FileDragSession(files = listOf(file), horizontal = 240f, vertical = 260f))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Box(Modifier.padding(start = 40.dp, end = 20.dp, top = 25.dp).size(600.dp, 500.dp)) {
                        FileDragPreview(session.value)
                    }
                }
            }
        }
        onNodeWithText(file.name).assertIsDisplayed().assertHasNoClickAction()
        val firstBounds = onNodeWithText(file.name).fetchSemanticsNode().boundsInRoot
        assertEquals(240f, firstBounds.center.x, absoluteTolerance = 1f)
        assertEquals(260f, firstBounds.center.y, absoluteTolerance = 1f)
        runOnIdle { session.value = session.value.copy(horizontal = 300f, vertical = 280f) }
        val movedBounds = onNodeWithText(file.name).fetchSemanticsNode().boundsInRoot
        assertEquals(60f, movedBounds.left - firstBounds.left, absoluteTolerance = 1f)
        assertEquals(20f, movedBounds.top - firstBounds.top, absoluteTolerance = 1f)
    }

    @Test
    fun fileIconUsesThemeContentColorWhenSwitchingToDarkMode() = runComposeUiTest {
        val file = file("folder").copy(isDirectory = true)
        val darkTheme = mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalContentColor provides Color.Red) {
                MaterialTheme(
                    colorScheme = if (darkTheme.value) {
                        darkColorScheme(onSurface = Color.White)
                    } else {
                        lightColorScheme(onSurface = Color.Black)
                    },
                ) {
                    FileDragPreviewContent(file, count = 1)
                }
            }
        }
        fun assertIconColor(expected: Color) {
            val pixels = onNodeWithText(file.name).captureToImage().toPixelMap()
            val iconPixels = (12 until 44).sumOf { x ->
                (12 until pixels.height - 12).count { y -> pixels[x, y] == expected }
            }
            assertTrue(iconPixels > 0, "文件图标应使用当前主题的内容色 $expected")
        }
        assertIconColor(Color.Black)
        runOnIdle { darkTheme.value = true }
        assertIconColor(Color.White)
    }

    @Test
    fun mouseDragShowsGrabbedFileAndSelectionCountThenDisappearsAfterDrop() = runComposeUiTest {
        val first = file("one.txt")
        val dragged = file("two.txt")
        val selected = listOf(first, dragged)
        val endpoint = FileDropEndpoint(FileDropTargetKind.Device, "device")
        var copied = emptyList<FileSimpleInfo>()
        setContent {
            val session by FileDropSessionRegistry.session.collectAsState()
            MaterialTheme {
                Box(Modifier.size(700.dp, 400.dp)) {
                    Row {
                        Box(Modifier.size(200.dp).testTag("source").fileDragSource(dragged, { selected }, { target, files ->
                            assertEquals(endpoint, target)
                            copied = files
                        }, null))
                        Box(Modifier.size(200.dp).testTag("target").fileDropTarget(endpoint))
                    }
                    FileDragPreview(session)
                }
            }
        }
        onNodeWithText(dragged.name).assertDoesNotExist()
        onNodeWithTag("source").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
        }
        onNodeWithText(dragged.name).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_arg0_items.format(arg0 = "2")).assertIsDisplayed()
        onNodeWithTag("source").performMouseInput { moveTo(center + Offset(180f, 0f)) }
        onNodeWithText(dragged.name).assertIsDisplayed()
        onNodeWithTag("source").performMouseInput { release() }
        onNodeWithText(dragged.name).assertDoesNotExist()
        runOnIdle {
            assertEquals(selected, copied)
            assertNull(FileDropSessionRegistry.session.value)
        }
    }

    @Test
    fun touchPreviewStaysAboveFingerAndDisappearsWhenSourceIsDisposed() = runComposeUiTest {
        val file = file("folder").copy(isDirectory = true)
        val sourceVisible = mutableStateOf(true)
        setContent {
            val session by FileDropSessionRegistry.session.collectAsState()
            MaterialTheme {
                Box(Modifier.size(500.dp)) {
                    if (sourceVisible.value) {
                        Box(Modifier.size(300.dp).testTag("source").fileDragSource(file, { emptyList() }, { _, _ -> }, null))
                    }
                    FileDragPreview(session)
                }
            }
        }
        onNodeWithTag("source").performTouchInput {
            down(center)
            advanceEventTime(700L)
            moveBy(Offset(60f, 30f))
        }
        onNodeWithText(file.name).assertIsDisplayed()
        val previewBounds = onNodeWithText(file.name).fetchSemanticsNode().boundsInRoot
        runOnIdle {
            val session = FileDropSessionRegistry.session.value!!
            assertTrue(session.isTouch)
            assertTrue(previewBounds.bottom < session.vertical)
        }
        runOnIdle { sourceVisible.value = false }
        onNodeWithText(file.name).assertDoesNotExist()
    }

    @Test
    fun previewDoesNotInterceptUnderlyingPointerInput() = runComposeUiTest {
        val file = file("report.pdf")
        var clicks = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    Box(Modifier.fillMaxSize().testTag("underPreview").clickable { clicks++ })
                    FileDragPreview(FileDragSession(files = listOf(file), horizontal = 20f, vertical = 20f))
                }
            }
        }
        val previewBounds = onNodeWithText(file.name).fetchSemanticsNode().boundsInRoot
        val targetBounds = onNodeWithTag("underPreview").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("underPreview").performMouseInput { click(position = previewBounds.center - targetBounds.topLeft) }
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun noDuplicatePreviewForExternalFilesOrEndedSessions() = runComposeUiTest {
        val file = file("report.pdf")
        val session = mutableStateOf<FileDragSession?>(FileDragSession(files = listOf(file), external = true))
        setContent { MaterialTheme { Box(Modifier.size(400.dp)) { FileDragPreview(session.value) } } }
        onNodeWithText(file.name).assertDoesNotExist()
        runOnIdle { session.value = FileDragSession(files = listOf(file)) }
        onNodeWithText(file.name).assertIsDisplayed()
        runOnIdle { session.value = null }
        onNodeWithText(file.name).assertDoesNotExist()
    }

    @Test
    fun mousePreviewCentersOnPointerAndClampsAtViewportEdges() {
        val viewport = IntSize(400, 300)
        val preview = IntSize(120, 60)
        assertEquals(IntOffset(40, 70), fileDragPreviewOffset(Offset(100f, 100f), viewport, preview, 32f, false))
        assertEquals(IntOffset(280, 240), fileDragPreviewOffset(Offset(380f, 280f), viewport, preview, 32f, false))
        assertEquals(IntOffset.Zero, fileDragPreviewOffset(Offset(-100f, -100f), viewport, preview, 16f, false))
        assertEquals(IntOffset(280, 240), fileDragPreviewOffset(Offset(900f, 900f), viewport, preview, 16f, false))
        assertEquals(IntOffset.Zero, fileDragPreviewOffset(Offset.Zero, IntSize(30, 20), preview, 16f, false))
    }

    @Test
    fun touchPreviewFlipsAwayFromViewportEdgesWithFingerGap() {
        val viewport = IntSize(400, 300)
        val preview = IntSize(120, 60)
        assertEquals(IntOffset(132, 8), fileDragPreviewOffset(Offset(100f, 100f), viewport, preview, 32f, true))
        assertEquals(IntOffset(132, 42), fileDragPreviewOffset(Offset(100f, 10f), viewport, preview, 32f, true))
        assertEquals(IntOffset(228, 188), fileDragPreviewOffset(Offset(380f, 280f), viewport, preview, 32f, true))
    }

    private fun file(name: String): FileSimpleInfo = FileSimpleInfo.nullFileSimpleInfo().copy(name = name, path = "/$name")
}
