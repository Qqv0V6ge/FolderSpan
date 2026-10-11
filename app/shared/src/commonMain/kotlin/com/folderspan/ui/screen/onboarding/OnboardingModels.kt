package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.folderspan.ui.screen.device.DeviceScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.screen.network.NetworkAddEntryScreen
import com.folderspan.ui.navigation.AppScreenRoute

data class OnboardingPage(
    val key: String,
    val widgetType: OnboardingWidgetType,
    val title: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val actions: List<OnboardingAction>,
) {
    val previewType: OnboardingWidgetType
        get() = widgetType
}

data class OnboardingAction(
    val target: String,
    val action: String,
)

enum class OnboardingWidgetType {
    LinkShare,
    DeviceShare,
    RemoteFiles,
    CloudDrive,
}

enum class OnboardingContentLayout {
    Row,
    Column,
}

fun resolveOnboardingContentLayout(width: Dp, height: Dp): OnboardingContentLayout =
    if (width > height) {
        OnboardingContentLayout.Row
    } else {
        OnboardingContentLayout.Column
    }

fun OnboardingPage.targetScreen(): AppScreenRoute =
    when (widgetType) {
        OnboardingWidgetType.LinkShare -> FileShareScreen
        OnboardingWidgetType.DeviceShare -> DeviceScreen()
        OnboardingWidgetType.RemoteFiles -> DeviceScreen()
        OnboardingWidgetType.CloudDrive -> NetworkAddEntryScreen()
    }

fun OnboardingPage.previewScreen(): AppScreenRoute = targetScreen()

val onboardingPages = listOf(
    OnboardingPage(
        key = "link-share",
        widgetType = OnboardingWidgetType.LinkShare,
        title = AppStrings.ui_quick_sharing,
        label = AppStrings.ui_generate_link,
        description = AppStrings.ui_publish_current_file_directory_temporary_access_link_other_party,
        icon = Icons.Default.Link,
        actions = listOf(
            OnboardingAction(
                target = AppStrings.ui_file,
                action = AppStrings.ui_confirm_that_menu_button_appears_right_file_you_want,
            ),
            OnboardingAction(
                target = AppStrings.ui_file_menu,
                action = AppStrings.ui_click_share_enter_quick_sharing_settings,
            ),
            OnboardingAction(
                target = AppStrings.ui_share_entrance,
                action = AppStrings.ui_scan_qr_code_copy_access_link_open_sharing_page,
            ),
        ),
    ),
    OnboardingPage(
        key = "device-share",
        widgetType = OnboardingWidgetType.DeviceShare,
        title = AppStrings.ui_device_sharing,
        label = AppStrings.ui_select_device_send,
        description = AppStrings.ui_select_device_same_network_send_file,
        icon = Icons.Default.Devices,
        actions = listOf(
            OnboardingAction(
                target = AppStrings.ui_file,
                action = AppStrings.ui_confirm_that_menu_button_appears_right_file_you_want,
            ),
            OnboardingAction(
                target = AppStrings.ui_file_menu,
                action = AppStrings.ui_click_share_enter_sharing_page,
            ),
            OnboardingAction(
                target = AppStrings.ui_share_other_devices,
                action = AppStrings.ui_select_device_same_network_send_files,
            ),
            OnboardingAction(
                target = AppStrings.ui_receiver,
                action = AppStrings.ui_other_party_agrees_device_sharing_request,
            ),
        ),
    ),
    OnboardingPage(
        key = "remote-file",
        widgetType = OnboardingWidgetType.RemoteFiles,
        title = AppStrings.ui_manipulate_files_across_devices,
        label = AppStrings.ui_operate_remote_like_local,
        description = AppStrings.ui_after_connecting_device_browse_remote_directory_directly_copy_move,
        icon = Icons.Default.FolderOpen,
        actions = listOf(
            OnboardingAction(
                target = AppStrings.ui_equipment,
                action = AppStrings.ui_select_connected_device_enter_remote_file,
            ),
            OnboardingAction(
                target = AppStrings.ui_remote_folder,
                action = AppStrings.ui_select_folder_copy,
            ),
            OnboardingAction(
                target = AppStrings.ui_folder_menu,
                action = AppStrings.ui_display_complete_device_menu_click_copy,
            ),
            OnboardingAction(
                target = AppStrings.ui_local_directory,
                action = AppStrings.ui_switch_local_click_paste,
            ),
        ),
    ),
    OnboardingPage(
        key = "cloud-drive",
        widgetType = OnboardingWidgetType.CloudDrive,
        title = AppStrings.ui_third_party_network_disk,
        label = AppStrings.ui_mount_network_location,
        description = AppStrings.ui_add_webdav_s3_smb_other_network_locations_put_third,
        icon = Icons.Default.Cloud,
        actions = listOf(
            OnboardingAction(
                target = AppStrings.ui_network_location,
                action = AppStrings.ui_select_network_location_enter_network_disk_file,
            ),
            OnboardingAction(
                target = AppStrings.ui_remote_folder,
                action = AppStrings.ui_select_folder_copy,
            ),
            OnboardingAction(
                target = AppStrings.ui_folder_menu,
                action = AppStrings.ui_display_full_network_menu_click_copy,
            ),
            OnboardingAction(
                target = AppStrings.ui_local_directory,
                action = AppStrings.ui_switch_local_click_paste,
            ),
        ),
    ),
)
