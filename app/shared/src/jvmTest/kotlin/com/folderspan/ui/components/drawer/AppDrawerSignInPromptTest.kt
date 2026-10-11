package com.folderspan.ui.components.drawer

import com.folderspan.appDrawerAccountHeader
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppDrawerSignInPromptTest {
    @Test
    fun loggedOutTitleSwitchesBetweenAppNameAndSignInPrompt() = runComposeUiTest {
        val header = appDrawerAccountHeader(null)
        setContent {
            MaterialTheme {
                AppDrawerAccountTitle(
                    header = header,
                    showSignInPrompt = false,
                    onClick = {},
                )
            }
        }
        onNodeWithText(AppStrings.app_name).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_tap_to_sign_in).assertDoesNotExist()
    }

    @Test
    fun loggedOutTitleShowsSignInPromptWhenActive() = runComposeUiTest {
        val header = appDrawerAccountHeader(null)
        setContent {
            MaterialTheme {
                AppDrawerAccountTitle(
                    header = header,
                    showSignInPrompt = true,
                    onClick = {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_tap_to_sign_in).assertIsDisplayed()
        onNodeWithText(AppStrings.app_name).assertDoesNotExist()
    }

    @Test
    fun loggedOutTitleClickInvokesCallback() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                AppDrawerAccountTitle(
                    header = appDrawerAccountHeader(null),
                    showSignInPrompt = true,
                    onClick = { clicked = true },
                )
            }
        }
        onNodeWithText(AppStrings.ui_tap_to_sign_in).performClick()
        assertTrue(clicked)
    }

    @Test
    fun loggedOutNavigationIconShowsDeviceIconWhenPromptIsIdle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppDrawerAccountNavigationIcon(
                    header = appDrawerAccountHeader(null),
                    showSignInPrompt = false,
                )
            }
        }
        onNodeWithContentDescription(AppStrings.ui_pc_equipment).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_login).assertDoesNotExist()
    }

    @Test
    fun loggedOutNavigationIconShowsLoginTextWhenPromptIsActive() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppDrawerAccountNavigationIcon(
                    header = appDrawerAccountHeader(null),
                    showSignInPrompt = true,
                )
            }
        }
        onNodeWithText(AppStrings.ui_login).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_pc_equipment).assertDoesNotExist()
    }

    @Test
    fun loggedInTitleKeepsAccountNameWhenPromptFlagIsSet() = runComposeUiTest {
        val header = AppDrawerAccountHeader(
            title = "Alice Chen",
            subtitle = "alice@example.com",
            avatarLabel = "A",
        )
        setContent {
            MaterialTheme {
                AppDrawerAccountTitle(
                    header = header,
                    showSignInPrompt = true,
                    onClick = {},
                )
            }
        }
        onNodeWithText("Alice Chen").assertIsDisplayed()
        onNodeWithText("alice@example.com").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_tap_to_sign_in).assertDoesNotExist()
    }
}
