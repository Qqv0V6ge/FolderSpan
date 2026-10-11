package com.folderspan.ui.components.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.pro.presentation.navigation.ProRoutes
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.MutableAppNavigator
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class AppDrawerMenuTest {
    @Test
    fun globalMenuShowsFeedbackAndMovesToolsOut() = runComposeUiTest {
        val navigator = MutableAppNavigator(AppRoute.Home)
        var clipboardRequests = 0
        setContent {
            MaterialTheme {
                MoreOptionsDropdown(
                    onOpenFromClipboard = { clipboardRequests++ },
                    onOpenFeedback = { navigator.push(ProRoutes.feedbackScreen()) },
                    onOpenSettings = {},
                    onExit = {},
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_more_options).performClick()

        onNodeWithText(AppStrings.ui_feedback_and_suggestions).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_open_clipboard).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_paste_clipboard_files).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_filter_type).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_equipment).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_share).assertDoesNotExist()

        onNodeWithText(AppStrings.ui_open_clipboard).performClick()
        assertEquals(1, clipboardRequests)
        onNodeWithContentDescription(AppStrings.ui_more_options).performClick()
        onNodeWithText(AppStrings.ui_feedback_and_suggestions).performClick()

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertEquals("Pro:ProFeedbackRoute", route.routeKey)
        navigator.pop()
    }
}
