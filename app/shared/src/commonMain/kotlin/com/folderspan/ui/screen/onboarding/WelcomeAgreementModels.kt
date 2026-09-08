package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class WelcomeAgreementFeature(
    val title: String,
    val description: String,
    val icon: ImageVector,
)

enum class WelcomeAgreementDocumentType {
    UserAgreement,
    PrivacyPolicy,
}

data class WelcomeAgreementDocument(
    val title: String,
    val paragraphs: List<String>,
)

enum class WelcomeAgreementContentLayout {
    Row,
    Column,
}

enum class WelcomeAgreementContentPlacement {
    Center,
    Top,
}

fun welcomeAgreementTitle(): String = AppStrings.ui_welcome_folderspan

fun welcomeAgreementSubtitle(): String =
    AppStrings.ui_tool_multi_device_file_management_lan_transfer_remote_storage

val welcomeAgreementFeatures = listOf(
    WelcomeAgreementFeature(
        title = AppStrings.ui_local_file_management,
        description = AppStrings.ui_browse_copy_move_rename_delete_local_files_while_maintaining,
        icon = Icons.Default.FolderOpen,
    ),
    WelcomeAgreementFeature(
        title = AppStrings.ui_quick_link_sharing,
        description = AppStrings.ui_publish_files_directories_temporary_access_links_other_devices_download,
        icon = Icons.Default.Link,
    ),
    WelcomeAgreementFeature(
        title = AppStrings.ui_transmission_between_devices_same_network,
        description = AppStrings.ui_discover_connect_devices_same_lan_send_receive_files_after,
        icon = Icons.Default.Devices,
    ),
    WelcomeAgreementFeature(
        title = AppStrings.ui_remote_connection_network_disk,
        description = AppStrings.ui_add_third_party_network_disks_server_locations_such_webdav,
        icon = Icons.Default.Cloud,
    ),
)

fun welcomeAgreementDocument(type: WelcomeAgreementDocumentType): WelcomeAgreementDocument =
    when (type) {
        WelcomeAgreementDocumentType.UserAgreement -> WelcomeAgreementDocument(
            title = AppStrings.ui_user_agreement,
            paragraphs = listOf(
                AppStrings.agreement_user_scope,
                AppStrings.ui_you_can_use_folderspan_manage_local_files_connect_devices,
                AppStrings.agreement_user_permissions,
                AppStrings.agreement_user_instructions_and_automation,
                AppStrings.agreement_user_remote_services,
                AppStrings.ui_you_should_ensure_that_you_have_rights_access_share,
                AppStrings.agreement_user_acceptable_use,
                AppStrings.agreement_user_sharing_security,
                AppStrings.agreement_user_data_protection,
                AppStrings.agreement_user_service_changes,
                AppStrings.agreement_user_intellectual_property,
                AppStrings.agreement_user_disclaimer,
                AppStrings.agreement_user_termination,
                AppStrings.agreement_user_minors,
                AppStrings.agreement_user_law_and_contact,
            )
        )

        WelcomeAgreementDocumentType.PrivacyPolicy -> WelcomeAgreementDocument(
            title = AppStrings.ui_privacy_policy,
            paragraphs = listOf(
                AppStrings.agreement_privacy_scope,
                AppStrings.agreement_privacy_principles,
                AppStrings.agreement_privacy_local_files,
                AppStrings.ui_folderspan_saves_necessary_data_such_device_name_connection_configuration,
                AppStrings.agreement_privacy_devices,
                AppStrings.agreement_privacy_link_sharing,
                AppStrings.agreement_privacy_network_drives,
                AppStrings.agreement_privacy_webrtc,
                AppStrings.agreement_privacy_clipboard_and_imports,
                AppStrings.agreement_privacy_mcp,
                AppStrings.agreement_privacy_logs,
                AppStrings.agreement_privacy_permissions,
                AppStrings.ui_app_will_not_actively_upload_your_local_files_only,
                AppStrings.ui_network_capabilities_used_lan_device_discovery_file_sharing_links,
                AppStrings.agreement_privacy_third_parties,
                AppStrings.agreement_privacy_cross_border,
                AppStrings.agreement_privacy_retention_and_security,
                AppStrings.agreement_privacy_rights,
                AppStrings.agreement_privacy_minors,
                AppStrings.agreement_privacy_changes_and_contact,
            )
        )
    }

fun resolveWelcomeAgreementContentLayout(width: Dp, height: Dp): WelcomeAgreementContentLayout =
    if (width > height) {
        WelcomeAgreementContentLayout.Row
    } else {
        WelcomeAgreementContentLayout.Column
    }

fun welcomeAgreementContentMaxWidth(layout: WelcomeAgreementContentLayout): Dp =
    when (layout) {
        WelcomeAgreementContentLayout.Row -> 1120.dp
        WelcomeAgreementContentLayout.Column -> 720.dp
    }

fun welcomeAgreementContentPlacement(layout: WelcomeAgreementContentLayout): WelcomeAgreementContentPlacement =
    when (layout) {
        WelcomeAgreementContentLayout.Row -> WelcomeAgreementContentPlacement.Center
        WelcomeAgreementContentLayout.Column -> WelcomeAgreementContentPlacement.Top
    }

fun shouldScrollWelcomeAgreementContent(): Boolean = true

fun canContinueWelcomeAgreement(agreementAccepted: Boolean): Boolean = agreementAccepted
