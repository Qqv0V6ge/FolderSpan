package com.folderspan.ui.components.status

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StatusWidgetTest {
    @Test
    fun statusContentIsVisibleAndActionInvokesCallback() = runComposeUiTest {
        var actionCount = 0

        setContent {
            MaterialTheme {
                StatusWidget(
                    title = AppStrings.ui_test_status_widget_unable_to_load,
                    supportingText = AppStrings.ui_test_status_widget_retry,
                    actionLabel = AppStrings.ui_try_again,
                    onAction = { actionCount++ },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                    )
                }
            }
        }

        onNodeWithText(AppStrings.ui_test_status_widget_unable_to_load).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_test_status_widget_retry).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_try_again).performClick()

        assertEquals(1, actionCount)
    }
}
