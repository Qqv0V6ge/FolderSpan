package com.folderspan.ui.components.pagestate

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.ui.components.grid.toGridListErrorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import strings.AppStrings

class PageStateTest {
    @Test
    fun resolvePrefersLoadingThenErrorThenEmpty() {
        assertEquals(
            PageViewState.Loading,
            resolvePageViewState(
                isLoading = true,
                errorState = PageErrorState(PageErrorType.General),
                isEmpty = true,
            ),
        )
        assertEquals(
            PageViewState.Error(PageErrorState(PageErrorType.Connection, "offline")),
            resolvePageViewState(
                errorState = PageErrorState(PageErrorType.Connection, "offline"),
                isEmpty = true,
            ),
        )
        assertEquals(
            PageViewState.Empty(AppStrings.ui_no_sync_tasks_yet),
            resolvePageViewState(isEmpty = true, emptyMessage = AppStrings.ui_no_sync_tasks_yet),
        )
        assertEquals(PageViewState.Content, resolvePageViewState())
    }

    @Test
    fun emptyDataExceptionMapsToEmptyMessageInsteadOfError() {
        val empty = EmptyDataException("specific empty state")
        assertNull(empty.toPageErrorState())
        assertNull(empty.toGridListErrorState())
        assertEquals("specific empty state", empty.toPageEmptyMessage())
    }

    @Test
    fun failuresPreserveTypeAndMessage() {
        val authority = AuthorityException("denied").toPageErrorState()
        assertEquals(PageErrorType.Authority, authority?.type)
        assertEquals("denied", authority?.message)

        val cancellation = CancellationException("cancelled").toPageErrorState()
        assertEquals(PageErrorType.Cancellation, cancellation?.type)

        val general = assertIs<PageErrorState>(
            IllegalStateException("specific failure").toGridListErrorState(),
        )
        assertEquals(PageErrorType.General, general.type)
        assertEquals("specific failure", general.message)
    }

    @Test
    fun loadMoreTriggersNearEndAndIgnoresEmptyLists() {
        assertEquals(false, shouldTriggerLoadMore(lastVisibleIndex = -1, totalItemsCount = 0))
        assertEquals(false, shouldTriggerLoadMore(lastVisibleIndex = 0, totalItemsCount = 20))
        assertEquals(true, shouldTriggerLoadMore(lastVisibleIndex = 15, totalItemsCount = 20))
        assertEquals(true, shouldTriggerLoadMore(lastVisibleIndex = 19, totalItemsCount = 20))
        assertEquals(
            false,
            shouldTriggerLoadMore(lastVisibleIndex = 10, totalItemsCount = 20, prefetchDistance = 3),
        )
    }
}
