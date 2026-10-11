package com.folderspan.ui.components.filter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketFilterSelection
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketListPage
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketListUiState
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class FeedbackTicketFilterSheetProviderTest {
    @Test
    fun ticketFiltersUseSharedSheetComponentsAndApplyAsOneSelection() = runComposeUiTest {
        var applied: FeedbackTicketFilterSelection? = null
        setContent {
            MaterialTheme {
                FeedbackTicketFilterSheetProvider {
                    Box(Modifier.size(600.dp, 800.dp)) {
                        FeedbackTicketListPage(
                            state = FeedbackTicketListUiState(isSignedIn = true),
                            onNavigateBack = {},
                            onSignIn = {},
                            onRefresh = {},
                            onLoadMore = {},
                            onUpdateFilters = { type, platform, start, end ->
                                applied = FeedbackTicketFilterSelection(type, platform, start, end)
                            },
                            onSubmitFeedback = {},
                            ticketDetail = { _, _, _ -> },
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription(AppStrings.ui_feedback_filter_tickets).performClick()
        onNodeWithText(AppStrings.ui_feedback_filter_type).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_filter_platform).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_time_range).performScrollTo().assertIsDisplayed()

        onNodeWithText(AppStrings.ui_suggestion).performClick()
        onNodeWithText("windows").performScrollTo().performClick()
        onNodeWithText(AppStrings.ui_feedback_last_30_days).performScrollTo().performClick()
        onNodeWithText(AppStrings.ui_application).performScrollTo().performClick()

        val selection = assertNotNull(applied)
        assertEquals("suggestion", selection.type)
        assertEquals("windows", selection.platform)
        assertNotNull(selection.startTimeEpochSeconds)
    }
}
