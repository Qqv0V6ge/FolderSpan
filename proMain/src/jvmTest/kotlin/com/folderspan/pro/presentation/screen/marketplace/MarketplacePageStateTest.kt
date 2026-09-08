package com.folderspan.pro.presentation.screen.marketplace

import androidx.paging.LoadState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import strings.AppStrings

class MarketplacePageStateTest {
    @Test
    fun firstOpenFailureUsesSectionErrorNotRefreshBanner() {
        val refresh = LoadState.Error(IllegalStateException("offline"))

        val error = assertIs<PageViewState.Error>(
            marketplaceSectionPageViewState(refresh = refresh, itemCount = 0),
        )
        assertEquals(PageErrorType.General, error.error.type)
        assertEquals("offline", error.error.message)
        assertEquals(
            PageRefreshState.Idle,
            marketplaceSectionRefreshState(refresh = refresh, itemCount = 0),
        )
    }

    @Test
    fun refreshFailureKeepsCardsAndUsesBanner() {
        val refresh = LoadState.Error(IllegalStateException("offline"))

        assertEquals(
            PageViewState.Content,
            marketplaceSectionPageViewState(refresh = refresh, itemCount = 4),
        )
        val banner = assertIs<PageRefreshState.Error>(
            marketplaceSectionRefreshState(refresh = refresh, itemCount = 4),
        )
        assertEquals("offline", banner.message)
    }

    @Test
    fun refreshLoadingWithCardsDoesNotReplaceSection() {
        val refresh = LoadState.Loading

        assertEquals(
            PageViewState.Content,
            marketplaceSectionPageViewState(refresh = refresh, itemCount = 2),
        )
        assertEquals(
            PageRefreshState.Refreshing,
            marketplaceSectionRefreshState(refresh = refresh, itemCount = 2),
        )
    }

    @Test
    fun emptyCatalogUsesPluginEmptyCopy() {
        val empty = assertIs<PageViewState.Empty>(
            marketplaceSectionPageViewState(
                refresh = LoadState.NotLoading(endOfPaginationReached = true),
                itemCount = 0,
            ),
        )
        assertEquals(AppStrings.ui_there_no_plugins_yet, empty.message)
    }
}
