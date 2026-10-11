package com.folderspan

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.ui.components.drawer.MoreOptionsDropdown
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.MutableAppNavigator
import com.folderspan.ui.screen.main.NotificationDetailScreen
import com.folderspan.ui.screen.main.openLocalNotification
import com.folderspan.ui.screen.settings.LocalAboutSoftwareContent
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class NoProIntegrationTest : ChineseLocalizationTest() {
    @Test
    fun errorLogOpensLocalDetailWithoutProRoutes() {
        val notification = RequestNotificationFactory.buildErrorLogNotification("Captured failure").notification
        val navigator = MutableAppNavigator(AppRoute.Home)
        try {
            openLocalNotification(navigator, notification)
            val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
            val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
            assertEquals(notification, screen.notification)
            assertNull(proFeedbackScreen())
            assertNull(proManualDataSyncScreen())
            assertNull(appDrawerAccountHeaderClickRoute())
        } finally {
            navigator.popToRoot()
        }
    }

    @Test
    fun drawerKeepsLocalActionsWithoutFeedback() = runComposeUiTest {
        setContent {
            MaterialTheme { MoreOptionsDropdown({}, null, {}, {}) }
        }
        onNodeWithContentDescription(AppStrings.ui_more_options).performClick()
        onNodeWithText(AppStrings.ui_open_clipboard).assertIsDisplayed()
        onNodeWithText(AppStrings.settings_title).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_and_suggestions).assertDoesNotExist()
    }

    @Test
    fun aboutKeepsVersionAndAgreementsWithoutOnlineUpdates() = runComposeUiTest {
        setContent {
            MaterialTheme { LocalAboutSoftwareContent("1.2.3", false, {}) }
        }
        onNodeWithText("1.2.3").assertIsDisplayed()
        onNodeWithTag("about-software-check").assertDoesNotExist()
        onNodeWithTag("about-software-history").assertDoesNotExist()
        onNodeWithTag("about-software-user-agreement").performScrollTo().performClick()
        onNodeWithText(AppStrings.ui_got_it).assertIsDisplayed()
    }
}
