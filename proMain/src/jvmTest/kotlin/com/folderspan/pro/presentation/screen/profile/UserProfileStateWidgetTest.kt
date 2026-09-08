package com.folderspan.pro.presentation.screen.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.pagestate.PageStateTestTags
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UserProfileStateWidgetTest {
    @Test
    fun loadingStateShowsCoreWidgetCopy() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UserProfileLoadingState(
                    title = AppStrings.ui_reading_data,
                )
            }
        }

        onNodeWithText(AppStrings.ui_reading_data).assertIsDisplayed()
    }

    @Test
    fun fallbackStateShowsCoreErrorWidgetAndSecondaryAction() = runComposeUiTest {
        var primaryCount = 0
        var secondaryCount = 0

        setContent {
            MaterialTheme {
                UserProfileFallbackState(
                    title = AppStrings.ui_test_user_profile_state_widget_unable_to_read,
                    description = AppStrings.ui_test_user_profile_state_widget_please_retry,
                    primaryLabel = AppStrings.ui_reload,
                    onPrimary = { primaryCount++ },
                    secondaryLabel = AppStrings.ui_log_out,
                    onSecondary = { secondaryCount++ },
                )
            }
        }

        onNodeWithText(AppStrings.ui_test_user_profile_state_widget_unable_to_read).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_test_user_profile_state_widget_please_retry).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_reload).performClick()
        onNodeWithText(AppStrings.ui_log_out).performClick()

        assertEquals(1, primaryCount)
        assertEquals(1, secondaryCount)
    }

    @Test
    fun editorLoadingUsesPageStateLayoutAndLoadingBase() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    EditorPageContent(
                        isLoading = true,
                        hasContent = false,
                        loadingTitle = AppStrings.ui_loading_data,
                        fallbackTitle = AppStrings.ui_test_user_profile_state_widget_unable_to_read,
                        fallbackDescription = AppStrings.ui_test_user_profile_state_widget_please_retry,
                        onRetry = {},
                        onLogout = {},
                    ) {
                        Text("secret-content")
                    }
                }
            }
        }

        onNodeWithTag(PageStateTestTags.Loading).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_loading_data).assertIsDisplayed()
        onNodeWithText("secret-content").assertDoesNotExist()
    }

    @Test
    fun editorErrorUsesPageStateLayoutAndErrorBase() = runComposeUiTest {
        var retryCount = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    EditorPageContent(
                        isLoading = false,
                        hasContent = false,
                        loadingTitle = AppStrings.ui_loading_data,
                        fallbackTitle = AppStrings.ui_test_user_profile_state_widget_unable_to_read,
                        fallbackDescription = AppStrings.ui_test_user_profile_state_widget_please_retry,
                        onRetry = { retryCount++ },
                        onLogout = {},
                    ) {
                        Text("secret-content")
                    }
                }
            }
        }

        onNodeWithTag(PageStateTestTags.Error).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_test_user_profile_state_widget_unable_to_read).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_test_user_profile_state_widget_please_retry).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_reload).performClick()
        onNodeWithText("secret-content").assertDoesNotExist()
        assertEquals(1, retryCount)
    }

    @Test
    fun loginDevicesRemovalUsesSnackbar() = runComposeUiTest {
        var removedDeviceId: Long? = null
        var removedCurrentDevice = false
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 600.dp)) {
                    LoginDevicesPage(
                        state = UserProfileUiState(
                            devices = listOf(
                                UserDeviceViewData(
                                    id = 1L,
                                    deviceType = "desktop",
                                    deviceName = "Grid Device",
                                    deviceKey = "current-device",
                                    createdAt = 0L,
                                )
                            )
                        ),
                        currentDeviceKey = "current-device",
                        onRemoveDevice = { deviceId, isCurrentDevice ->
                            removedDeviceId = deviceId
                            removedCurrentDevice = isCurrentDevice
                        },
                    )
                }
            }
        }

        onNodeWithText("Grid Device").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_remove).performClick()
        onNodeWithText(
            AppStrings.ui_delete_arg0.format(arg0 = "Grid Device")
        ).assertIsDisplayed()
        assertEquals(null, removedDeviceId)

        onNodeWithText(AppStrings.ui_confirm).performClick()
        waitUntil(timeoutMillis = 1_000) { removedDeviceId != null }
        assertEquals(1L, removedDeviceId)
        assertEquals(true, removedCurrentDevice)
    }

    @Test
    fun personalSettingsDeletionUsesSnackbar() = runComposeUiTest {
        val target = SettingTargetViewData(
            id = "target-1",
            name = "Grid Settings",
            deviceKey = "device-1",
            type = null,
            description = null,
        )
        var deletedTarget: SettingTargetViewData? = null
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 600.dp)) {
                    PersonalSettingsPage(
                        state = PersonalSettingsUiState(
                            targets = listOf(target)
                        ),
                        onDeleteTarget = { deletedTarget = it },
                    )
                }
            }
        }

        onNodeWithText("Grid Settings").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_more_actions).performClick()
        onNodeWithText(AppStrings.profile_delete_settings).performClick()
        onNodeWithText(
            AppStrings.ui_delete_arg0.format(arg0 = "Grid Settings")
        ).assertIsDisplayed()
        assertEquals(null, deletedTarget)

        onNodeWithText(AppStrings.ui_confirm).performClick()
        waitUntil(timeoutMillis = 1_000) { deletedTarget != null }
        assertEquals(target, deletedTarget)
    }
}
