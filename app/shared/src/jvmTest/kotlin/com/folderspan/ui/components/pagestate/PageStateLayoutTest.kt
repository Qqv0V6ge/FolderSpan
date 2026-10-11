package com.folderspan.ui.components.pagestate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class PageStateLayoutTest {
    @Test
    fun loadingErrorEmptyAndContentAreExclusive() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    PageStateLayout(state = PageViewState.Loading) {
                        Text("secret-content")
                    }
                }
            }
        }
        onNodeWithTag(PageStateTestTags.Loading).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_loading).assertIsDisplayed()
        onNodeWithText("secret-content").assertDoesNotExist()
    }

    @Test
    fun errorRetryInvokesCallback() = runComposeUiTest {
        var retryCount = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    PageStateLayout(
                        state = PageViewState.Error(PageErrorState(PageErrorType.General, "boom")),
                        onRetry = { retryCount++ },
                    ) {
                        Text("secret-content")
                    }
                }
            }
        }
        onNodeWithTag(PageStateTestTags.Error).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun refreshErrorBannerShowsAboveContent() = runComposeUiTest {
        var refreshRetry = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    PageStateLayout(
                        state = PageViewState.Content,
                        refreshState = PageRefreshState.Error("stale refresh"),
                        onRetryRefresh = { refreshRetry++ },
                    ) {
                        Text("visible-content")
                    }
                }
            }
        }
        onNodeWithTag(PageStateTestTags.Content).assertIsDisplayed()
        onNodeWithTag(PageStateTestTags.RefreshError).assertIsDisplayed()
        onNodeWithText("stale refresh").assertIsDisplayed()
        onNodeWithText("visible-content").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        assertEquals(1, refreshRetry)
    }

    @Test
    fun appendFooterRendersLoadingAndRetry() = runComposeUiTest {
        var appendRetry = 0
        setContent {
            MaterialTheme {
                PageAppendFooter(
                    state = PageAppendState.Error("append failed"),
                    onRetry = { appendRetry++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithTag(PageStateTestTags.AppendFooter).assertIsDisplayed()
        onNodeWithText("append failed").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        assertEquals(1, appendRetry)
    }
}
