package com.folderspan.ui.screen.file

import strings.AppStrings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.folderspan.editor.EditorSearchDirection
import com.folderspan.editor.EditorSearchHistoryEntry
import com.folderspan.editor.EditorSearchMode
import com.folderspan.editor.EditorTextEncoding
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileEditorScreenTest : ChineseLocalizationTest() {
    @Test
    fun editorSelectionSurvivesFocusLossAndRemainsHighlighted() {
        val selected = TextRange(1, 4)
        val current = TextFieldValue("abcdef", selection = selected)
        val collapsed = current.copy(selection = TextRange(4))

        assertTrue(shouldDeferEditorSelectionCollapse(current, collapsed, focused = true))
        assertFalse(shouldDeferEditorSelectionCollapse(current, collapsed, focused = false))
        assertFalse(
            shouldDeferEditorSelectionCollapse(
                current,
                collapsed.copy(text = "changed"),
                focused = true,
            )
        )

        assertEquals(
            selected,
            preserveEditorSelectionAfterFocusLoss(
                current = current,
                updated = collapsed,
                focused = false,
            ).selection,
        )
        assertEquals(
            TextRange(4),
            preserveEditorSelectionAfterFocusLoss(
                current = current,
                updated = collapsed,
                focused = true,
            ).selection,
        )

        val transformed = EditorTextVisualTransformation(
            showInvisibleCharacters = true,
            persistentSelection = TextRange(1, 3),
            selectionBackground = Color.Red,
        ).filter(AnnotatedString("a \n"))
        assertEquals("a·¶\n", transformed.text.text)
        assertTrue(
            transformed.text.spanStyles.any { range ->
                range.start == 1 && range.end == 4 && range.item.background == Color.Red
            }
        )
    }

    @Test
    fun responsiveEditorChromeUsesSupportingPaneOnWideScreens() {
        assertFalse(usesEditorSupportingPane(WindowSizeClass.Compact))
        assertFalse(usesEditorSupportingPane(WindowSizeClass.Medium))
        assertTrue(usesEditorSupportingPane(WindowSizeClass.Expanded))
        assertTrue(usesEditorSupportingPane(WindowSizeClass.Large))
        assertTrue(usesEditorSupportingPane(WindowSizeClass.ExtraLarge))
    }

    @Test
    fun searchPresentationAdaptsFromDialogToSupportingPane() {
        assertTrue(EDITOR_SEARCH_DIALOG_ALWAYS_FULL_SCREEN)
        assertEquals(
            EditorSearchPresentation.Dialog,
            editorSearchPresentation(WindowSizeClass.Compact),
        )
        assertEquals(
            EditorSearchPresentation.Dialog,
            editorSearchPresentation(WindowSizeClass.Medium),
        )
        assertEquals(
            EditorSearchPresentation.SupportingPane,
            editorSearchPresentation(WindowSizeClass.Expanded),
        )
        assertEquals(
            EditorSearchPresentation.SupportingPane,
            editorSearchPresentation(WindowSizeClass.Large),
        )
        assertEquals(
            EditorSearchPresentation.SupportingPane,
            editorSearchPresentation(WindowSizeClass.ExtraLarge),
        )
    }

    @Test
    fun verticalOverscrollTurnsPagesOnlyPastAvailableBoundaries() {
        assertEquals(
            EditorPageTurnDirection.Previous,
            editorPageTurnDirection(
                availableScrollY = 1f,
                currentPage = 1L,
                pageCount = 3L,
                enabled = true,
            ),
        )
        assertEquals(
            EditorPageTurnDirection.Next,
            editorPageTurnDirection(
                availableScrollY = -1f,
                currentPage = 1L,
                pageCount = 3L,
                enabled = true,
            ),
        )
        assertEquals(null, editorPageTurnDirection(1f, 0L, 3L, enabled = true))
        assertEquals(null, editorPageTurnDirection(-1f, 2L, 3L, enabled = true))
        assertEquals(null, editorPageTurnDirection(-1f, 1L, 3L, enabled = false))
        assertEquals(null, editorPageTurnDirection(0f, 1L, 3L, enabled = true))
    }

    @Test
    fun wheelPageTurnOnlyStartsAtScrollableEdgesWithAvailablePages() {
        assertEquals(
            EditorPageTurnDirection.Previous,
            editorPageTurnWheelDirection(
                scrollY = -48f,
                atStart = true,
                atEnd = false,
                currentPage = 1L,
                pageCount = 3L,
                enabled = true,
            ),
        )
        assertEquals(
            EditorPageTurnDirection.Next,
            editorPageTurnWheelDirection(
                scrollY = 48f,
                atStart = false,
                atEnd = true,
                currentPage = 1L,
                pageCount = 3L,
                enabled = true,
            ),
        )
        assertEquals(null, editorPageTurnWheelDirection(-48f, true, false, 0L, 3L, true))
        assertEquals(null, editorPageTurnWheelDirection(48f, false, true, 2L, 3L, true))
        assertEquals(null, editorPageTurnWheelDirection(48f, false, false, 1L, 3L, true))
        assertEquals(null, editorPageTurnWheelDirection(48f, false, true, 1L, 3L, false))
    }

    @Test
    fun pageTurnIndicatorTracksPullReadyAndLoadingMessages() {
        val previousPull = editorPageTurnIndicatorState(
            direction = EditorPageTurnDirection.Previous,
            accumulatedDistance = 24f,
            threshold = 48f,
        )
        assertEquals(0.5f, previousPull.progress)
        assertEquals(EditorPageTurnPhase.Pulling, previousPull.phase)
        assertEquals(AppStrings.ui_pull_down_switch_previous_page, editorPageTurnIndicatorMessage(previousPull))

        val nextPull = editorPageTurnIndicatorState(
            direction = EditorPageTurnDirection.Next,
            accumulatedDistance = 96f,
            threshold = 48f,
        )
        assertEquals(1f, nextPull.progress)
        assertEquals(EditorPageTurnPhase.Ready, nextPull.phase)
        assertEquals(AppStrings.ui_release_switch_next_page, editorPageTurnIndicatorMessage(nextPull))

        val loading = nextPull.copy(phase = EditorPageTurnPhase.Loading)
        assertEquals(AppStrings.ui_loading_next_page, editorPageTurnIndicatorMessage(loading))

        val hidden = editorPageTurnIndicatorState(
            direction = null,
            accumulatedDistance = 24f,
            threshold = 48f,
        )
        assertEquals(EditorPageTurnIndicatorState(), hidden)
        assertEquals("", editorPageTurnIndicatorMessage(hidden))
    }

    @Test
    fun pageTurnGestureOnlyTriggersAfterThresholdRelease() {
        val tracker = EditorPageTurnGestureTracker(threshold = 48f)

        val pulling = tracker.pull(EditorPageTurnDirection.Previous, distance = 24f)
        assertEquals(EditorPageTurnPhase.Pulling, pulling.phase)
        assertEquals(null, tracker.release())
        assertEquals(EditorPageTurnIndicatorState(), tracker.indicatorState)

        tracker.pull(EditorPageTurnDirection.Previous, distance = 30f)
        val ready = tracker.pull(EditorPageTurnDirection.Previous, distance = 18f)
        assertEquals(EditorPageTurnPhase.Ready, ready.phase)
        assertEquals(AppStrings.ui_release_switch_previous_page, editorPageTurnIndicatorMessage(ready))
        assertEquals(EditorPageTurnDirection.Previous, tracker.release())
        assertEquals(EditorPageTurnIndicatorState(), tracker.indicatorState)
    }

    @Test
    fun reversingPageTurnPullReducesProgressBeforeRelease() {
        val tracker = EditorPageTurnGestureTracker(threshold = 48f)
        tracker.pull(EditorPageTurnDirection.Next, distance = 40f)

        val reversed = tracker.consumeReverse(scrollY = 16f)

        assertEquals(16f, reversed.consumedScrollY)
        assertEquals(0.5f, reversed.indicatorState.progress)
        assertEquals(EditorPageTurnPhase.Pulling, reversed.indicatorState.phase)
        assertEquals(null, tracker.release())
    }

    @Test
    fun pageTurnLoadingIndicatorWaitsForTargetPageRequestToStopLoading() {
        assertFalse(pageTurnLoadingFinished(2L, 1L, isLoading = false))
        assertFalse(pageTurnLoadingFinished(2L, 2L, isLoading = true))
        assertTrue(pageTurnLoadingFinished(2L, 2L, isLoading = false))
        assertFalse(pageTurnLoadingFinished(null, 2L, isLoading = false))
    }

    @Test
    fun searchUiOnlyOffersTextAndRegexModes() {
        assertEquals(
            listOf(EditorSearchMode.Text, EditorSearchMode.Regex),
            editorSearchUiModes,
        )
    }

    @Test
    fun searchQueriesPreserveSpacesAndNewlinesAsLiteralContent() {
        assertTrue(parseEditorSearchQueries("", EditorSearchMode.Text).isEmpty())
        assertEquals(listOf(" "), parseEditorSearchQueries(" ", EditorSearchMode.Text))
        assertEquals(listOf("\n"), parseEditorSearchQueries("\n", EditorSearchMode.Text))
        assertEquals(
            listOf("first\nsecond"),
            parseEditorSearchQueries("first\nsecond", EditorSearchMode.Text),
        )
        assertEquals(
            listOf("first\nsecond"),
            parseEditorSearchQueries("first\nsecond", EditorSearchMode.Regex),
        )
        assertEquals("␠", editorSearchQueryLabel(" "))
        assertEquals("↵", editorSearchQueryLabel("\n"))
        assertEquals("first↵second", editorSearchQueryLabel("first\r\nsecond"))
    }

    @Test
    fun searchHistoryRecordIsSelectedOnlyWhenItsRestoredOptionsAreActive() {
        val historyEntry = EditorSearchHistoryEntry(
            mode = EditorSearchMode.Text,
            queries = listOf("first", "second"),
            encoding = EditorTextEncoding.UTF8,
            caseSensitive = true,
            wholeWord = false,
            direction = EditorSearchDirection.Backward,
            updatedAt = 1L,
        )
        val restoredQuery = "first\nsecond"

        assertTrue(
            isEditorSearchHistoryEntrySelected(
                entry = historyEntry,
                query = restoredQuery,
                mode = EditorSearchMode.Text,
                caseSensitive = true,
                wholeWord = false,
                direction = EditorSearchDirection.Backward,
            )
        )
        assertFalse(
            isEditorSearchHistoryEntrySelected(
                entry = historyEntry,
                query = "$restoredQuery!",
                mode = EditorSearchMode.Text,
                caseSensitive = true,
                wholeWord = false,
                direction = EditorSearchDirection.Backward,
            )
        )
        assertFalse(
            isEditorSearchHistoryEntrySelected(
                entry = historyEntry,
                query = restoredQuery,
                mode = EditorSearchMode.Regex,
                caseSensitive = true,
                wholeWord = false,
                direction = EditorSearchDirection.Backward,
            )
        )
    }

    @Test
    fun dismissingSearchOnlyCollapsesItsPresentation() {
        val presentationState = FileEditorPresentationState()

        presentationState.toggleSearch()
        assertTrue(presentationState.searchExpanded)

        presentationState.dismissSearch()
        assertFalse(presentationState.searchExpanded)

        presentationState.dismissSearch()
        assertFalse(presentationState.searchExpanded)
    }

    @Test
    fun lineNumberAndAutomaticWrapPreferencesPersistAcrossEditorStates() {
        val storedValues = mutableMapOf<String, Boolean>()
        fun createState() = FileEditorPresentationState(
            readBoolean = { key, defaultValue -> storedValues[key] ?: defaultValue },
            writeBoolean = { key, value -> storedValues[key] = value },
        )

        val initialState = createState()
        assertTrue(initialState.showLineNumbers)
        assertTrue(initialState.automaticWrap)

        initialState.toggleShowLineNumbers()
        initialState.toggleAutomaticWrap()

        assertFalse(storedValues.getValue(SettingsUtils.KEY_EDITOR_SHOW_LINE_NUMBERS))
        assertFalse(storedValues.getValue(SettingsUtils.KEY_EDITOR_AUTOMATIC_WRAP))

        val restoredState = createState()
        assertFalse(restoredState.showLineNumbers)
        assertFalse(restoredState.automaticWrap)

        restoredState.toggleShowLineNumbers()
        assertTrue(storedValues.getValue(SettingsUtils.KEY_EDITOR_SHOW_LINE_NUMBERS))
        assertFalse(storedValues.getValue(SettingsUtils.KEY_EDITOR_AUTOMATIC_WRAP))
    }

    @Test
    fun pageNavigationAndBottomBarVisibilityFollowEditorState() {
        assertFalse(shouldShowFileEditorPageNavigation(1L))
        assertTrue(shouldShowFileEditorPageNavigation(2L))

        assertFalse(shouldShowFileEditorBottomBar(canWrite = false, pageCount = 1L))
        assertTrue(shouldShowFileEditorBottomBar(canWrite = true, pageCount = 1L))
        assertTrue(shouldShowFileEditorBottomBar(canWrite = false, pageCount = 2L))
    }

    @Test
    fun currentPageTextSearchIsCaseInsensitive() {
        assertEquals(
            listOf(EditorSearchMatch(start = 0, length = 4), EditorSearchMatch(start = 5, length = 4)),
            findTextMatches("Test test", "test"),
        )
    }

}
