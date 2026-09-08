package com.folderspan.ui.screen.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.MutableAppNavigator
import com.folderspan.ui.screen.file.filter.FileFilterScreen
import com.folderspan.ui.screen.file.share.FileShareManageScreen
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class ToolboxScreenTest {
    @Test
    fun toolboxOpensFileFiltersAndShares() = runComposeUiTest {
        val navigator = MutableAppNavigator(AppRoute.Home)
        setContent {
            CompositionLocalProvider(LocalAppNavigator provides navigator) {
                MaterialTheme { ToolboxScreen().Content() }
            }
        }

        onNodeWithText(AppStrings.ui_device_management).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_filter_type).performScrollTo().performClick()
        val filterRoute = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertIs<FileFilterScreen>(AppRoutePayloadStore.resolve(filterRoute))

        navigator.pop()
        onNodeWithText(AppStrings.ui_share_management).performScrollTo().performClick()
        val shareRoute = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertIs<FileShareManageScreen>(AppRoutePayloadStore.resolve(shareRoute))
        navigator.pop()
    }
}
