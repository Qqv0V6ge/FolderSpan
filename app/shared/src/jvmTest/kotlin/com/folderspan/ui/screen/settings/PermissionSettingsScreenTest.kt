package com.folderspan.ui.screen.settings

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.permission.PermissionAction
import com.folderspan.permission.PermissionIds
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermission
import com.folderspan.test.ChineseLocalizationTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class PermissionSettingsScreenTest : ChineseLocalizationTest() {
    @Test
    fun unavailableExternalServiceStillOffersItsManagerInstructions() = runComposeUiTest {
        var guidesOpened = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.Shizuku,
                        PermissionAction.Request,
                        AppStrings.permission_shizuku_title,
                        AppStrings.permission_shizuku_description,
                        settingsInstructions = AppStrings.permission_shizuku_settings_instructions,
                    ),
                    status = PermissionStatus.Unsupported,
                    onRequest = { error("Unavailable service cannot accept a request") },
                    onOpenSettings = { guidesOpened++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                )
            }
        }
        onNodeWithText(AppStrings.permission_settings_instructions_action).assertIsDisplayed().performClick()
        assertEquals(1, guidesOpened)
    }

    @Test
    fun unsupportedPermissionQueryStillAllowsReadingItsInstructions() = runComposeUiTest {
        var guidesOpened = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.LocalNetwork,
                        PermissionAction.ShowInstructions,
                        AppStrings.permission_local_network_title,
                        AppStrings.permission_web_local_network_description,
                        settingsInstructions = AppStrings.permission_web_site_settings_instructions,
                    ),
                    status = PermissionStatus.Unsupported,
                    onRequest = { error("Unsupported query must not trigger a permission request") },
                    onOpenSettings = { guidesOpened++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                )
            }
        }
        onNodeWithText(AppStrings.permission_settings_instructions_action).assertIsDisplayed().performClick()
        assertEquals(1, guidesOpened)
    }

    @Test
    fun browserDenialOffersSiteSettingsInstructions() = runComposeUiTest {
        var guidesOpened = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.Notifications,
                        PermissionAction.RequestOnce,
                        AppStrings.permission_notifications_title,
                        AppStrings.permission_web_notifications_description,
                        settingsInstructions = AppStrings.permission_web_site_settings_instructions,
                    ),
                    status = PermissionStatus.Denied,
                    onRequest = { error("A denied browser permission requires site settings") },
                    onOpenSettings = { guidesOpened++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_request).assertDoesNotExist()
        onNodeWithText(AppStrings.permission_settings_instructions_action).performClick()
        assertEquals(1, guidesOpened)
    }

    @Test
    fun siteSettingsInstructionsCanBeReadAndDismissedInACompactDialog() = runComposeUiTest {
        setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme {
                if (visible) {
                    PermissionSettingsInstructionsDialog(
                        permission = PlatformPermission(
                            PermissionIds.Notifications,
                            PermissionAction.RequestOnce,
                            AppStrings.permission_notifications_title,
                            "分享通知",
                            settingsInstructions = AppStrings.permission_web_site_settings_instructions,
                        ),
                        onDismiss = { visible = false },
                        modifier = Modifier.width(360.dp).testTag("site-settings-guide"),
                    )
                }
            }
        }
        onNodeWithText(AppStrings.permission_web_site_settings_instructions).assertIsDisplayed()
        onNodeWithText("分享通知").assertDoesNotExist()
        val screenshot = File("build/reports/permission-settings/site-settings-guide.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onNodeWithTag("site-settings-guide").captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText(AppStrings.ui_confirm).performClick()
        onNodeWithText(AppStrings.permission_web_site_settings_instructions).assertDoesNotExist()
    }

    @Test
    fun deniedRepeatablePermissionKeepsRetryAndOffersSettings() = runComposeUiTest {
        var requests = 0
        var settingsOpens = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(PermissionIds.LocalNetwork, PermissionAction.Request, "局域网访问", "用于设备分享"),
                    status = PermissionStatus.Denied,
                    onRequest = { requests++ },
                    onOpenSettings = { settingsOpens++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_request).performClick()
        onNodeWithText(AppStrings.ui_go_settings).assertIsDisplayed().performClick()
        assertEquals(1, requests)
        assertEquals(1, settingsOpens)
    }

    @Test
    fun perFileAccessOffersInstructionsWithoutAnAuthorizationRequest() = runComposeUiTest {
        var guidesOpened = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.FilesAndFolders,
                        PermissionAction.ShowInstructions,
                        AppStrings.permission_files_and_folders_title,
                        AppStrings.permission_web_file_save_description,
                    ),
                    status = PermissionStatus.Unknown,
                    onRequest = { error("Per-file permission must be requested through the real save dialog") },
                    onOpenSettings = { guidesOpened++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        onNodeWithText(AppStrings.ui_request).assertDoesNotExist()
        onNodeWithText(AppStrings.permission_settings_instructions_action).assertIsDisplayed().performClick()
        assertEquals(1, guidesOpened)
    }

    @Test
    fun deniedNotificationPermissionOpensSettingsInsteadOfRequestingAgain() = runComposeUiTest {
        var requests = 0
        var settingsOpens = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.Notifications,
                        PermissionAction.RequestOnce,
                        AppStrings.permission_notifications_title,
                        AppStrings.permission_notifications_description,
                    ),
                    status = PermissionStatus.Denied,
                    onRequest = { requests++ },
                    onOpenSettings = { settingsOpens++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                    modifier = Modifier.width(360.dp).testTag("denied-notification-permission"),
                )
            }
        }

        assertEquals(0, requests)
        onNodeWithText(AppStrings.ui_request).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_go_settings).assertIsDisplayed().performClick()
        assertEquals(0, requests)
        assertEquals(1, settingsOpens)
        val screenshot = File("build/reports/permission-settings/notifications-denied.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onNodeWithTag("denied-notification-permission").captureToImage().toAwtImage(), "png", screenshot)
    }

    @Test
    fun fullDiskAccessIsOptionalAndOnlyOffersSystemSettings() = settingsOnlyPermission(
        PermissionIds.FullDiskAccess,
        AppStrings.permission_full_disk_access_title,
        AppStrings.permission_full_disk_access_description,
    )

    @Test
    fun fileAndFolderAccessOnlyOffersSystemSettings() = settingsOnlyPermission(
        PermissionIds.FilesAndFolders,
        AppStrings.permission_files_and_folders_title,
        AppStrings.permission_files_and_folders_description,
    )

    @Test
    fun appManagementIsOptionalAndOnlyOffersSystemSettings() = settingsOnlyPermission(
        PermissionIds.AppManagement,
        AppStrings.permission_app_management_title,
        AppStrings.permission_app_management_description,
    )

    private fun settingsOnlyPermission(id: String, title: String, description: String) = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(id, PermissionAction.OpenSettings, title, description),
                    status = PermissionStatus.Unknown,
                    onRequest = { error("This permission must be managed in System Settings") },
                    onOpenSettings = { settingsOpens++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                    modifier = Modifier.width(360.dp).testTag(id),
                )
            }
        }
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithText(description).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_request).assertDoesNotExist()
        onNodeWithText(AppStrings.ui_go_settings).assertIsDisplayed().performClick()
        assertEquals(1, settingsOpens)
        val screenshot = File("build/reports/permission-settings/$id.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onNodeWithTag(id).captureToImage().toAwtImage(), "png", screenshot)
    }

    @Test
    fun unqueryablePermissionExplainsItsStatusAndOffersBothActionsAtCompactWidth() = runComposeUiTest {
        var requests = 0
        var settingsOpens = 0
        setContent {
            MaterialTheme {
                PermissionListItem(
                    permission = PlatformPermission(
                        PermissionIds.LocalNetwork,
                        PermissionAction.Request,
                        AppStrings.permission_local_network_title,
                        AppStrings.permission_local_network_macos_description,
                    ),
                    status = PermissionStatus.Unknown,
                    onRequest = { requests++ },
                    onOpenSettings = { settingsOpens++ },
                    rootStartupRequestEnabled = false,
                    onRootStartupRequestChange = {},
                    modifier = Modifier.width(360.dp).testTag("local-network-permission"),
                )
            }
        }

        onNodeWithText(AppStrings.permission_local_network_title).assertIsDisplayed()
        onNodeWithText(AppStrings.permission_local_network_macos_description).assertIsDisplayed()
        onNodeWithText(
            AppStrings.ui_status_arg0.format(arg0 = AppStrings.permission_status_confirm_in_settings)
        ).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_request).assertIsDisplayed().performClick()
        onNodeWithText(AppStrings.ui_go_settings).assertIsDisplayed().performClick()
        assertEquals(1, requests)
        assertEquals(1, settingsOpens)

        val screenshot = File("build/reports/permission-settings/local-network-compact.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onNodeWithTag("local-network-permission").captureToImage().toAwtImage(), "png", screenshot)
    }
}
