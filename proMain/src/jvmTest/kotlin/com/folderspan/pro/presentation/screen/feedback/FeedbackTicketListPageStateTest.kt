package com.folderspan.pro.presentation.screen.feedback

import com.folderspan.pro.testing.feedbackSummary
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import strings.AppStrings

class FeedbackTicketListPageStateTest {
    @Test
    fun emptyListMapsToLoadingErrorEmptyThenContent() {
        assertEquals(
            PageViewState.Loading,
            FeedbackTicketListUiState(isLoading = true).toPageViewState(),
        )

        val error = FeedbackTicketListUiState(errorMessage = "offline").toPageViewState()
        val errorState = assertIs<PageViewState.Error>(error)
        assertEquals(PageErrorType.General, errorState.error.type)
        assertEquals("offline", errorState.error.message)

        assertEquals(
            PageViewState.Empty(AppStrings.ui_feedback_no_tickets),
            FeedbackTicketListUiState().toPageViewState(),
        )
        assertEquals(
            PageViewState.Content,
            FeedbackTicketListUiState(items = listOf(feedbackSummary("one"))).toPageViewState(),
        )
    }

    @Test
    fun populatedListKeepsContentWhenRefreshOrAppendFails() {
        val items = listOf(feedbackSummary("one"))
        assertEquals(
            PageViewState.Content,
            FeedbackTicketListUiState(
                items = items,
                isLoading = true,
            ).toPageViewState(),
        )
        assertEquals(
            PageRefreshState.Refreshing,
            FeedbackTicketListUiState(
                items = items,
                isLoading = true,
            ).toPageRefreshState(),
        )
        assertEquals(
            PageRefreshState.Error("stale"),
            FeedbackTicketListUiState(
                items = items,
                refreshMessage = "stale",
            ).toPageRefreshState(),
        )
        assertEquals(
            PageAppendState.Error("page 2 down"),
            FeedbackTicketListUiState(
                items = items,
                errorMessage = "page 2 down",
            ).toPageAppendState(),
        )
        assertEquals(
            PageAppendState.End,
            FeedbackTicketListUiState(
                items = items,
                hasMore = false,
            ).toPageAppendState(),
        )
        assertEquals(
            PageAppendState.Idle,
            FeedbackTicketListUiState(
                items = items,
                hasMore = true,
            ).toPageAppendState(),
        )
    }
}
