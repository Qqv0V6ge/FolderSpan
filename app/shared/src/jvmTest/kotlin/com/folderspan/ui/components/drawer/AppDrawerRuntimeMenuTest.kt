package com.folderspan.ui.components.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppDrawerRuntimeMenuTest {
    @Test
    fun nativeMenuDoesNotShowRuntimeSelector() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MoreOptionsDropdown({}, null, {}, {})
            }
        }
        onNodeWithContentDescription(AppStrings.ui_more_options).performClick()
        onNodeWithText(AppStrings.ui_web_ui_runtime_reload).assertDoesNotExist()
    }

    @Test
    fun attachedMenuOpensRuntimeSelectorAndClosesMenu() = runComposeUiTest {
        var requests = 0
        setContent {
            MaterialTheme {
                MoreOptionsDropdown(
                    onOpenFromClipboard = {},
                    onOpenFeedback = null,
                    onOpenSettings = {},
                    onExit = {},
                    onSwitchRuntime = { requests++ },
                )
            }
        }
        onNodeWithContentDescription(AppStrings.ui_more_options).performClick()
        onNodeWithText(AppStrings.ui_web_ui_runtime_reload).performClick()
        assertEquals(1, requests)
        onNodeWithText(AppStrings.ui_web_ui_runtime_reload).assertDoesNotExist()
    }
}
