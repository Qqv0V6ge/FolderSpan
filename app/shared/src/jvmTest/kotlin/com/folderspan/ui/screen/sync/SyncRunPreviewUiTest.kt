package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.components.pagestate.*
import com.folderspan.ui.state.main.*
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncRunPreviewUiTest : ChineseLocalizationTest() {
    private fun preview(emptyWarning: Boolean = false, pending: Boolean = false) = SyncRunPreview(
        "token", 1, "Documents", SyncMode.Mirror, linkedMapOf("source" to "Source", "target" to "First target", "target:2" to "Second target"),
        mapOf(SyncPreviewAction.Copy to SyncPreviewCount(1, 10), SyncPreviewAction.Recycle to SyncPreviewCount(1, 6)),
        2, 12, true, if (emptyWarning) listOf("source" to "photos") else emptyList(), pending,
        recycleEnabled = true, participantPaths = mapOf("source" to listOf("/selected/photo"), "target" to listOf("/backup1"), "target:2" to listOf("/backup2")), pendingRunId = 42,
    )

    @Test fun recommendedPreviewExplainsThatItsSelectedTargetRemainsFixed() = runSkikoComposeUiTest(size = Size(900f, 1400f)) {
        val value = preview().copy(fixedRecommendedTarget = true,
            participants = linkedMapOf("source" to "Source", "target" to "Chosen archive"),
            participantPaths = mapOf("source" to listOf("/selected/photo"), "target" to listOf("/chosen")))
        setContent { MaterialTheme { TestPage(value) } }
        onNodeWithText(AppStrings.sync_fixed_recommended_target_hint).performScrollTo().assertIsDisplayed()
        onNodeWithText("Chosen archive").performScrollTo().assertIsDisplayed()
        onNodeWithText("Second target").assertDoesNotExist()
    }

    @Test fun loadingAndErrorUseSharedStatesAndRetryAndCancelRemainAvailable() = runSkikoComposeUiTest(size = Size(680f, 700f)) {
        var state by mutableStateOf<PageViewState>(PageViewState.Loading)
        var retry = 0
        var cancel = 0
        setContent { MaterialTheme { TestPage(pageState = state, onRefresh = { retry++ }, onCancel = { cancel++ }) } }
        onNodeWithTag(PageStateTestTags.Loading).assertIsDisplayed()
        onNodeWithTag("sync-preview-overview").assertDoesNotExist()
        runOnIdle { state = PageViewState.Error(PageErrorState(PageErrorType.General, "read failed")) }
        onNodeWithTag(PageStateTestTags.Error).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        onNodeWithContentDescription(AppStrings.sync_preview_cancel).performClick()
        runOnIdle { assertEquals(1, retry); assertEquals(1, cancel) }
    }

    @Test fun emptyPlanStillAllowsFirstConfirmationAndPendingRejection() = runSkikoComposeUiTest(size = Size(900f, 1400f)) {
        var confirmed = 0
        var rejected = 0
        val value = preview(pending = true).copy(itemCount = 0, counts = emptyMap())
        setContent { MaterialTheme { TestPage(value, rowsState = PageViewState.Empty(AppStrings.sync_preview_empty),
            onConfirm = { confirmed++ }, onReject = { rejected++ }) } }
        onNodeWithText(AppStrings.sync_preview_empty).assertIsDisplayed()
        onNodeWithText("Source").assertIsDisplayed()
        onNodeWithText("First target").assertIsDisplayed()
        onNodeWithText("Second target").assertIsDisplayed()
        onNodeWithTag("sync-preview-confirm").performScrollTo().assertIsEnabled().performClick()
        onNodeWithText(AppStrings.sync_preview_reject).performScrollTo().performClick()
        runOnIdle { assertEquals(1, confirmed); assertEquals(1, rejected) }
    }

    @Test fun unexpectedlyEmptyScopeRequiresSeparateConsentAndBusyDisablesActions() = runSkikoComposeUiTest(size = Size(900f, 1500f)) {
        var busy by mutableStateOf(false)
        var confirmed = 0
        setContent { MaterialTheme { TestPage(preview(emptyWarning = true), busy = busy, onConfirm = { confirmed++ }) } }
        onNodeWithTag("sync-preview-confirm").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("sync-preview-empty-confirm").performScrollTo().performClick()
        onNodeWithTag("sync-preview-confirm").performScrollTo().assertIsEnabled().performClick()
        runOnIdle { busy = true }
        onNodeWithTag("sync-preview-confirm").assertIsNotEnabled()
        runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun compactShortWindowCanSwitchToItemsAndReturnToScrollableOverview() = runSkikoComposeUiTest(size = Size(390f, 360f)) {
        var selected: SyncPreviewAction? = null
        setContent { MaterialTheme { TestPage(preview(), rows = listOf(SyncPreviewItem(SyncPreviewAction.Copy, "target", "photo.jpg", size = 10)),
            onFilter = { action, _ -> selected = action }) } }
        onNodeWithText(AppStrings.sync_preview_copy).performScrollTo().performClick()
        onNodeWithTag("sync-preview-items").assertIsDisplayed()
        onNodeWithText("photo.jpg").assertIsDisplayed()
        runOnIdle { assertEquals(SyncPreviewAction.Copy, selected) }
        onNodeWithContentDescription(AppStrings.sync_preview_back_summary).performClick()
        onNodeWithTag("sync-preview-overview").assertIsDisplayed()
        onNodeWithTag("sync-preview-overview").performScrollToNode(hasTestTag("sync-preview-confirm"))
        onNodeWithTag("sync-preview-confirm").assertIsDisplayed()
    }

    @Test fun mediumThroughExtraLargeShowBothPanesWithoutClippingAtShortHeight() {
        for (width in listOf(680f, 960f, 1280f, 1700f)) runSkikoComposeUiTest(size = Size(width, 380f)) {
            setContent { MaterialTheme { TestPage(preview(), separatingPaneGap = 40.dp) } }
            val overview = onNodeWithTag("sync-preview-overview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val detail = onNodeWithTag("sync-preview-items").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(detail.left - overview.right >= 39f)
            onNodeWithTag("sync-preview-overview").performScrollToNode(hasTestTag("sync-preview-confirm"))
            onNodeWithTag("sync-preview-confirm").assertIsDisplayed()
        }
    }

    @Test fun duplicatePathsHaveStableDistinctRowsAndAppendRetryKeepsExistingRows() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        var requests = 0
        val rows = List(100) { SyncPreviewItem(SyncPreviewAction.Copy, "target", "same-name.txt", size = 10) }
        setContent { MaterialTheme { TestPage(preview().copy(itemCount = 200), rows = rows,
            appendState = PageAppendState.Error("page failed"), onLoadMore = { requests++ }) } }
        onNodeWithTag("sync-preview-row-list").performScrollToIndex(100)
        onNodeWithText("page failed").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        runOnIdle { assertEquals(1, requests) }
    }

    @Composable
    private fun TestPage(
        value: SyncRunPreview? = null, pageState: PageViewState = PageViewState.Content, busy: Boolean = false,
        rows: List<SyncPreviewItem> = emptyList(), rowsState: PageViewState = PageViewState.Content,
        appendState: PageAppendState = PageAppendState.End, separatingPaneGap: androidx.compose.ui.unit.Dp = 0.dp,
        onFilter: (SyncPreviewAction?, Boolean) -> Unit = { _, _ -> }, onLoadMore: () -> Unit = {},
        onRefresh: () -> Unit = {}, onCancel: () -> Unit = {}, onConfirm: () -> Unit = {}, onReject: () -> Unit = {},
    ) = SyncRunPreviewPage(value, pageState, busy, rows, rowsState, appendState, null, false, null,
        onFilter, onLoadMore, onRefresh, onCancel, onConfirm, onReject, separatingPaneGap = separatingPaneGap)
}
