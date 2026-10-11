package com.folderspan.ui.screen.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.notification.RequestNotificationFactory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppUpdateActionRowTest {
    @Test
    fun openDownloadButtonOpensHttpsLink() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val metadata = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.4.0",
            link = "https://example.test/download",
        ).notification.metadata

        setContent {
            MaterialTheme {
                AppUpdateActionRow(
                    metadata = metadata,
                    openUrl = opened::add,
                )
            }
        }

        onNodeWithTag("notification-app-update-open-link")
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf("https://example.test/download"), opened)
    }

    @Test
    fun invalidLinkHidesTheButton() = runComposeUiTest {
        val metadata = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.4.0",
            link = "",
        ).notification.metadata

        setContent {
            MaterialTheme {
                AppUpdateActionRow(metadata = metadata, openUrl = {})
            }
        }

        onAllNodesWithTag("notification-app-update-open-link").assertCountEquals(0)
    }
}
