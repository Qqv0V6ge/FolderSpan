package com.folderspan.ui.screen.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class AccountNotificationActionRowTest {
    @Test
    fun keepsTheFirstTwoActionsVisibleAndMovesTheRestToOverflow() {
        val actions = sampleActions()
        val layout = accountNotificationActionLayout(actions)

        assertEquals(listOf(AppStrings.ui_test_account_notification_action_row_view, "Unsupported"), layout.visible.map(NotificationAction::label))
        assertEquals(listOf("Third", "Hidden"), layout.overflow.map(NotificationAction::label))
        assertEquals(
            AccountNotificationActionLayout(visible = actions.take(2), overflow = emptyList()),
            accountNotificationActionLayout(actions.take(2)),
        )
    }

    @Test
    fun rendersTwoServerLabelsAndKeepsUnsupportedActionDisabled() = runComposeUiTest {
        var clickedLabel: String? = null

        setContent {
            MaterialTheme {
                AccountNotificationActionRow(
                    actions = sampleActions(),
                    onAction = { action -> clickedLabel = action.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        onNodeWithText(AppStrings.ui_test_account_notification_action_row_view).assertIsDisplayed().assertIsEnabled().performClick()
        onNodeWithText("Unsupported").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_notification_destination_unsupported).assertIsDisplayed()
        onNodeWithText("Third").assertDoesNotExist()
        onNodeWithText("Hidden").assertDoesNotExist()
        onNodeWithContentDescription(AppStrings.ui_more_actions).assertIsDisplayed()
        assertEquals(AppStrings.ui_test_account_notification_action_row_view, clickedLabel)
    }

    @Test
    fun overflowMenuExposesAdditionalActions() = runComposeUiTest {
        var clickedLabel: String? = null

        setContent {
            MaterialTheme {
                AccountNotificationActionRow(
                    actions = sampleActions(),
                    onAction = { action -> clickedLabel = action.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText("Hidden").assertIsDisplayed()
        onNodeWithText("Third").assertIsDisplayed().performClick()
        assertEquals("Third", clickedLabel)
    }

    @Test
    fun hidesOverflowMenuWhenThereAreAtMostTwoActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AccountNotificationActionRow(
                    actions = sampleActions().take(2),
                    onAction = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        onNodeWithText(AppStrings.ui_test_account_notification_action_row_view).assertIsDisplayed()
        onNodeWithText("Unsupported").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_more_actions).assertDoesNotExist()
    }

    private fun sampleActions() = listOf(
        urlAction(AppStrings.ui_test_account_notification_action_row_view, NotificationActionStyle.Primary, "https://example.test/primary"),
        urlAction("Unsupported", NotificationActionStyle.Secondary, "folderspan://settings"),
        urlAction("Third", NotificationActionStyle.Secondary, "http://example.test/third"),
        urlAction("Hidden", NotificationActionStyle.Secondary, "https://example.test/hidden"),
    )

    private fun urlAction(
        label: String,
        style: NotificationActionStyle,
        url: String,
    ) = NotificationAction(
        label = label,
        style = style,
        kind = NotificationActionKind.Url,
        url = url,
    )
}
