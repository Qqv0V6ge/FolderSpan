package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateHistorySnapshot
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class AppUpdateHistoryScreenTest {
    @Test
    fun historyOpensDownloadFromActionButton() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val update = AppUpdate(
            id = 9L,
            title = "FolderSpan 1.4",
            content = "- Faster startup",
            version = "1.4.0",
            platform = "linux",
            link = "https://example.test/download",
            publishedAtEpochMillis = 1_700_000_000_000L,
        )

        setContent {
            var selected by remember { mutableStateOf<AppUpdate?>(null) }
            MaterialTheme {
                AppUpdateHistoryContent(
                    snapshot = AppUpdateHistorySnapshot(items = listOf(update)),
                    currentVersion = "1.0.0",
                    selected = selected,
                    onSelect = { selected = it },
                    onRetry = {},
                    onLoadMore = {},
                    onOpenLink = opened::add,
                    onDismissDetail = { selected = null },
                )
            }
        }

        onNodeWithTag("about-software-history-item-9").assertIsDisplayed().performClick()
        onNodeWithText("Open download page").assertIsDisplayed().performClick()
        assertEquals(listOf("https://example.test/download"), opened)
    }

    @Test
    fun emptyHistoryShowsGuidanceWithoutRepeatingTitle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppUpdateHistoryContent(
                    snapshot = AppUpdateHistorySnapshot(),
                    currentVersion = "1.0.0",
                    selected = null,
                    onSelect = {},
                    onRetry = {},
                    onLoadMore = {},
                    onOpenLink = {},
                    onDismissDetail = {},
                )
            }
        }

        onNodeWithTag("about-software-history-empty").assertIsDisplayed()
    }

    @Test
    fun errorHistoryShowsRetryableMessage() = runComposeUiTest {
        val retries = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                AppUpdateHistoryContent(
                    snapshot = AppUpdateHistorySnapshot(
                        errorMessage = "Unable to load version history. Try again later.",
                    ),
                    currentVersion = "1.0.0",
                    selected = null,
                    onSelect = {},
                    onRetry = { retries += Unit },
                    onLoadMore = {},
                    onOpenLink = {},
                    onDismissDetail = {},
                )
            }
        }

        onNodeWithTag("about-software-history-error").assertIsDisplayed()
        onNodeWithText("Reload").assertIsDisplayed().performClick()
        assertEquals(1, retries.size)
        onNodeWithTag("about-software-history-empty").assertDoesNotExist()
        onNodeWithTag("about-software-history-list").assertDoesNotExist()
    }

    @Test
    fun firstOpenHistoryFailureIsListErrorNotRefreshBanner() {
        val snapshot = AppUpdateHistorySnapshot(
            errorMessage = "Unable to load version history. Try again later.",
        )
        val error = assertIs<PageViewState.Error>(snapshot.toPageViewState())
        assertEquals(PageErrorType.Connection, error.error.type)
        assertEquals("Unable to load version history. Try again later.", error.error.message)
        assertEquals(PageRefreshState.Idle, snapshot.toPageRefreshState())
    }

    @Test
    fun historyRefreshFailureKeepsItemsAndUsesBanner() {
        val snapshot = AppUpdateHistorySnapshot(
            items = listOf(
                AppUpdate(
                    id = 1L,
                    title = "FolderSpan 1.4",
                    content = "",
                    version = "1.4.0",
                    platform = "linux",
                    link = "",
                    publishedAtEpochMillis = 1L,
                ),
            ),
            errorMessage = "Unable to load version history. Try again later.",
        )
        assertEquals(PageViewState.Content, snapshot.toPageViewState())
        val refresh = assertIs<PageRefreshState.Error>(snapshot.toPageRefreshState())
        assertEquals("Unable to load version history. Try again later.", refresh.message)
    }

    @Test
    fun splitPaneThresholdMatchesMediumWidth() {
        assertEquals(false, shouldUseAppUpdateHistorySplitPane(599.dp))
        assertEquals(true, shouldUseAppUpdateHistorySplitPane(600.dp))
    }
}
