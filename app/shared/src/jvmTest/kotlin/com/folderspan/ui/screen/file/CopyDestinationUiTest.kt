package com.folderspan.ui.screen.file

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class CopyDestinationUiTest : ChineseLocalizationTest() {
    @Test fun waterfallGridKeepsThePrimaryActionReachableAtAllSixSizes() {
        listOf(360 to 800, 640 to 360, 720 to 900, 1024 to 768, 1366 to 900, 1600 to 900).forEachIndexed { index, size ->
            runSkikoComposeUiTest(size = Size(size.first.toFloat(), size.second.toFloat())) {
                setContent { MaterialTheme(colorScheme = if (index % 2 == 0) lightColorScheme() else darkColorScheme()) { CopyDestinationSample() } }
                onNodeWithTag("copy-submit").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("copy-target-list").assertIsDisplayed()
                onNodeWithText(AppStrings.copy_destination_sources.format(count = "3")).assertDoesNotExist()
                onNodeWithText("选择保存位置，默认复制到各资源的主目录。").assertDoesNotExist()
                onNodeWithContentDescription("刷新位置").assertDoesNotExist()
                onNodeWithTag("copy-target-list").performScrollToNode(hasTestTag("copy-add-directory:Local"))
                onNodeWithTag("copy-add-directory:Local").assertIsDisplayed()
                onNodeWithTag("copy-search").assertDoesNotExist()
                onNodeWithTag("copy-selected-list").assertDoesNotExist()
                onNodeWithTag("copy-capacity:Local", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithText("剩余空间").assertDoesNotExist()
                onNodeWithText("未知").assertDoesNotExist()
                onNodeWithTag("copy-target-list").performScrollToIndex(0)
                val file = File("build/reports/copy-destination/${size.first}x${size.second}.png")
                file.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            }
        }
    }

    @Test fun touchAndKeyboardShowTheCopyActionOnlyForSelectedDestinations() = runSkikoComposeUiTest(size = Size(540f, 800f)) {
        val (state, targets) = sampleCopyDestinationState()
        state.remove(state.selected.value.single().directory)
        var submitted = 0
        setContent { MaterialTheme { TestPage(state, targets, onSubmit = { submitted++ }) } }
        onNodeWithTag("copy-submit").assertDoesNotExist()
        val file = File("build/reports/copy-destination/no-selection.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        runOnIdle { assertEquals(0, submitted) }
        onNodeWithTag("copy-resource-select:Local").performTouchInput { click() }
        onNodeWithTag("copy-submit").assertIsEnabled()
        onNodeWithText("${AppStrings.ui_copy} (2)", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("copy-resource-select:Local").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("copy-resource-select:Local").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("copy-submit").assertDoesNotExist()
        onNodeWithTag("copy-target-list").performScrollToNode(hasTestTag("copy-target:Device:/storage/emulated/0"))
        onNodeWithTag("copy-select-directory:Device:/storage/emulated/0").performClick()
        onNodeWithTag("copy-submit").performClick()
        runOnIdle {
            assertEquals(1, submitted)
            assertEquals("/storage/emulated/0", state.selected.value.single().directory.file.path)
        }
    }

    @Test fun addedDirectoriesStaySeparateAndUnavailableSelectionsCanBeRemoved() = runSkikoComposeUiTest(size = Size(1000f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        val unavailable = targets.first().copy(unavailableReason = "Permission revoked")
        val extra = targets.first().copy(name = "照片", directory = CopyFileSnapshot(targets.first().directory.endpoint,
            targets.first().directory.file.copy(path = "/home/webb/Pictures")))
        state.updateAvailability(targets.first().directory, "Permission revoked")
        setContent { MaterialTheme { TestPage(state, listOf(unavailable) + targets.drop(1) + extra) } }
        onNodeWithTag("copy-submit").assertIsNotEnabled()
        onNodeWithTag("copy-select-directory:Local:/home/webb").performClick()
        onNodeWithTag("copy-select-directory:Local:/home/webb/Pictures").performClick()
        onNodeWithTag("copy-submit").assertIsEnabled()
        runOnIdle { assertEquals("/home/webb/Pictures", state.selected.value.single().directory.file.path) }
    }

    @Test fun resourceCardsGroupFoldersAndAddDoesNotToggleTheResource() = runSkikoComposeUiTest(size = Size(1366f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        var retries = 0
        var browsing = ""
        var showFailure by mutableStateOf(true)
        val failed = CopyDestinationLoad(targets[1].directory.endpoint, "Redmi K30 Pro", loading = false, error = "Connection failed")
        setContent { MaterialTheme { TestPage(state, targets, loads = if (showFailure) listOf(failed) else emptyList(), onRetry = { retries++ }, onBrowse = { browsing = it.connectionName }) } }
        onAllNodesWithText(AppStrings.copy_destination_retry).onFirst().performClick()
        runOnIdle { assertEquals(1, retries); showFailure = false }
        val localCard = onNodeWithTag("copy-resource:Local")
        localCard.onChildren().filterToOne(hasTestTag("copy-target:Local:/home/webb")).assertExists()
        onNode(hasTestTag("copy-target:Local:/mnt/Archive/Photos/2026/Travel/Originals") and hasAnyAncestor(hasTestTag("copy-resource:Local"))).assertExists()
        onNodeWithTag("copy-add-directory:Local").performClick()
        runOnIdle { assertEquals("本机", browsing); assertEquals(1, state.selected.value.size) }
        onNodeWithTag("copy-resource-select:Local").performClick()
        runOnIdle { assertEquals(2, state.selected.value.size) }
        onNodeWithTag("copy-resource-select:Local").performClick()
        runOnIdle { assertTrue(state.selected.value.isEmpty()) }
    }

    @Test fun compactCardsOnlyRespondToTheirButtonsAndHaveNoHeaderHover() = runSkikoComposeUiTest(size = Size(540f, 800f)) {
        val (state, targets) = sampleCopyDestinationState()
        state.remove(state.selected.value.single().directory)
        setContent { MaterialTheme { TestPage(state, targets) } }
        val header = onNodeWithTag("copy-resource-header:Local")
        header.assertHasNoClickAction()
        onNodeWithTag("copy-target:Local:/home/webb").assertHasNoClickAction()
        val before = header.captureToImage().toAwtImage()
        header.performMouseInput { moveTo(center) }
        val after = header.captureToImage().toAwtImage()
        kotlin.test.assertContentEquals(before.getRGB(0, 0, before.width, before.height, null, 0, before.width),
            after.getRGB(0, 0, after.width, after.height, null, 0, after.width))
        header.performMouseInput { click(androidx.compose.ui.geometry.Offset(40f, center.y)) }
        runOnIdle { assertTrue(state.selected.value.isEmpty()) }
        val grid = onNodeWithTag("copy-target-list").fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag("copy-resource:Local").fetchSemanticsNode().boundsInRoot
        assertEquals(8f, card.left - grid.left)
        assertEquals(0f, card.top - grid.top)
        onNodeWithTag("copy-resource-select:Local").performClick()
        runOnIdle { assertEquals(2, state.selected.value.size) }
    }

    @Test fun removeButtonIsLeftOfTheCheckboxAndDoesNotToggleOtherLocations() = runSkikoComposeUiTest(size = Size(1000f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        setContent { MaterialTheme { TestPage(state, targets) } }
        val path = "/mnt/Archive/Photos/2026/Travel/Originals"
        val remove = onNodeWithTag("copy-remove-directory:Local:$path")
        val checkbox = onNodeWithTag("copy-select-directory:Local:$path", useUnmergedTree = true)
        assertTrue(remove.fetchSemanticsNode().boundsInRoot.right < checkbox.fetchSemanticsNode().boundsInRoot.left)
        remove.performClick()
        onNodeWithTag("copy-target:Local:$path").assertDoesNotExist()
        runOnIdle { assertEquals(1, state.selected.value.size) }
        onNodeWithTag("copy-remove-directory:Local:/home/webb").performClick()
        onNodeWithTag("copy-target:Local:/home/webb").assertDoesNotExist()
        onNodeWithTag("copy-add-directory:Local").assertIsEnabled()
        runOnIdle { assertTrue(state.selected.value.isEmpty()) }
        onNodeWithTag("copy-submit").assertDoesNotExist()
    }

    @Test fun submittedLocationsCannotBeRemovedFromTheDraft() = runSkikoComposeUiTest(size = Size(1000f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        setContent { MaterialTheme { TestPage(state, targets, accepted = listOf(targets.first())) } }
        onNodeWithTag("copy-remove-directory:Local:/home/webb").assertIsNotEnabled()
        onNodeWithTag("copy-select-directory:Local:/home/webb").assertIsNotEnabled()
        runOnIdle { assertEquals(1, state.selected.value.size) }
    }

    @Test fun longNamesResizingAndLargerTextKeepTheSameResourceSelection() = runSkikoComposeUiTest(size = Size(1100f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        val longName = "办公室备份设备：照片、项目文件与长期归档"
        val longTargets = targets.map { it.copy(name = longName, connectionName = longName) }
        var width by mutableStateOf(1100.dp)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                MaterialTheme { Box(Modifier.width(width)) { TestPage(state, longTargets) } }
            }
        }
        onNodeWithTag("copy-submit").assertIsDisplayed().assertIsEnabled()
        runOnIdle { width = 360.dp }
        onNodeWithTag("copy-submit").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("copy-target-list").performScrollToNode(hasTestTag("copy-add-directory:Local"))
        onNodeWithTag("copy-add-directory:Local").assertIsDisplayed()
        runOnIdle { assertEquals(1, state.selected.value.size) }
    }

    @Test fun wideGridUsesMultipleColumnsAndFillsTheSpaceUnderShorterCards() = runSkikoComposeUiTest(size = Size(1100f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        val reason = "此目录当前只读，请重新授权后重试。".repeat(8)
        val varyingTargets = listOf(targets.first().copy(unavailableReason = reason)) + targets.drop(1)
        state.updateAvailability(targets.first().directory, reason)
        setContent { MaterialTheme { TestPage(state, varyingTargets) } }
        fun bounds(target: CopyDestination) = onNodeWithTag("copy-resource:${target.directory.endpoint.connectionBinding}").fetchSemanticsNode().boundsInRoot
        val ordered = targets.distinctBy { it.directory.endpoint }.sortedBy { it.directory.endpoint.endpoint.protocol.ordinal }
        val first = bounds(ordered[0])
        val second = bounds(ordered[1])
        val third = bounds(ordered[2])
        val next = bounds(ordered[3])
        assertEquals(first.top, second.top)
        assertEquals(first.top, third.top)
        assertTrue(first.left < second.left && second.left < third.left)
        assertTrue(first.height > second.height)
        assertTrue(next.top >= minOf(second.bottom, third.bottom) && next.top < first.bottom)
        val file = File("build/reports/copy-destination/waterfall.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun sourceSheetShowsOnlyTheCurrentRequestAndKeepsSelectedTargets() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val (original, targets) = sampleCopyDestinationState()
        val endpoint = original.sources.first().endpoint
        val other = CopyDestinationState("preview", listOf(CopyFileSnapshot(endpoint,
            FileSimpleInfo.pathFileSimpleInfo("/Incoming/other.txt").copy(name = "other.txt", isDirectory = false, size = 1024))))
        var state by mutableStateOf(original)
        setContent { MaterialTheme { key(state.requestId) { TestPage(state, targets) } } }
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithTag("copy-source-sheet").assertIsDisplayed()
        onNodeWithText("photo.jpg").assertIsDisplayed()
        onNodeWithText("旅行照片").assertIsDisplayed()
        onNodeWithText("other.txt").assertDoesNotExist()
        val file = File("build/reports/copy-destination/source-files.png")
        file.parentFile.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
        onNodeWithTag("copy-close-sources").performClick()
        onNodeWithTag("copy-source-sheet").assertDoesNotExist()
        onNodeWithTag("copy-submit").assertIsEnabled()
        runOnIdle { assertEquals(1, original.selected.value.size); state = other }
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithText("other.txt").assertIsDisplayed()
        onNodeWithText("photo.jpg").assertDoesNotExist()
        onNodeWithTag("copy-close-sources").performClick()
        runOnIdle { assertEquals(0, other.selected.value.size); assertEquals(1, original.selected.value.size) }
    }

    @Test fun longSourceListCanScrollAndCloseInAShortWindow() = runSkikoComposeUiTest(size = Size(640f, 360f)) {
        val (original, targets) = sampleCopyDestinationState()
        val state = CopyDestinationState("preview", List(40) { index -> CopyFileSnapshot(original.sources.first().endpoint,
            FileSimpleInfo.pathFileSimpleInfo("/Incoming/file-$index.txt").copy(name = "file-$index.txt", isDirectory = false, size = 1024)) })
        state.select(targets.first())
        setContent { MaterialTheme { TestPage(state, targets) } }
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithTag("copy-source-list").performScrollToIndex(39)
        onNodeWithText("file-39.txt").assertIsDisplayed()
        onNodeWithTag("copy-close-sources").assertIsDisplayed().performClick()
        onNodeWithTag("copy-submit").assertIsDisplayed().assertIsEnabled()
        runOnIdle { assertEquals(1, state.selected.value.size) }
    }

    @Test fun sourceSheetAddsAndRemovesItemsWithoutTypeLabelsAndDisablesEmptyCopies() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        var adds = 0
        setContent { MaterialTheme { TestPage(state, targets, onAddSources = { adds++ }) } }
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithText(AppStrings.ui_folder).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_file).assertDoesNotExist()
        onNodeWithTag("copy-add-sources").performClick()
        runOnIdle { assertEquals(1, adds) }
        state.sources.forEach { source ->
            onNodeWithTag("copy-remove-source:${source.endpoint.connectionBinding}:${source.file.path}").performClick()
        }
        onNodeWithText(AppStrings.copy_destination_sources.format(count = "0")).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_no_files).assertIsDisplayed()
        onNodeWithTag("copy-add-sources").assertIsEnabled()
        onNodeWithTag("copy-close-sources").performClick()
        onNodeWithTag("copy-submit").assertIsNotEnabled()
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun acceptedBatchesKeepSourceActionsLocked() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val (state, targets) = sampleCopyDestinationState()
        setContent { MaterialTheme { TestPage(state, targets, accepted = listOf(targets.first())) } }
        onNodeWithTag("copy-view-sources").performClick()
        onNodeWithTag("copy-add-sources").assertIsNotEnabled()
        onNodeWithTag("copy-remove-source:Local:/Pictures/photo.jpg").assertIsNotEnabled()
        onNodeWithText(AppStrings.copy_destination_sources_locked).assertIsDisplayed()
    }

    @Composable private fun TestPage(state: CopyDestinationState, targets: List<CopyDestination>, loads: List<CopyDestinationLoad> = emptyList(), onRetry: (CopyEndpointIdentity) -> Unit = {}, onBrowse: (CopyDestination) -> Unit = {}, onSubmit: () -> Unit = {}, accepted: List<CopyDestination> = emptyList(), onAddSources: () -> Unit = {}) {
        val removed = remember { mutableStateListOf<CopyFileSnapshot>() }
        val controls by state.controls.collectAsState()
        val selected by state.selected.collectAsState()
        var sources by remember(state.requestId) { mutableStateOf(state.sources) }
        val resources = targets.distinctBy { it.directory.endpoint }.map { target ->
            CopyDestinationLoad(target.directory.endpoint, target.connectionName, listOf(target), false)
        }
        CopyDestinationPage(controls, loads + resources, selected, state.visibleDestinations(targets).filterNot { target -> removed.any { it.sameLocation(target.directory) } }, sources, false, accepted, null,
            onBack = {}, onScroll = state::rememberScroll,
            onToggle = { target -> if (selected.any { it.directory.sameLocation(target.directory) }) state.remove(target.directory) else state.select(target) },
            onToggleResource = { group -> if (group.all { target -> selected.any { it.directory.sameLocation(target.directory) } }) group.forEach { state.remove(it.directory) } else state.selectAll(group) },
            onRemove = { target -> state.remove(target.directory); removed.add(target.directory) },
            onBrowse = onBrowse, onRetry = onRetry, onSubmit = onSubmit,
            onAddSources = onAddSources, onRemoveSource = { source -> sources = sources.filterNot { it.sameLocation(source) } })
    }
}
