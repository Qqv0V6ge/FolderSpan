package com.folderspan.pro.presentation.screen.profile

import com.folderspan.pro.core.ui.components.AuthStatusTone

enum class UserProfileLayoutMode {
    Compact,
    Medium,
    Expanded,
}

data class UserProfileViewData(
    val uuid: String,
    val name: String,
    val email: String,
    val avatar: String?,
    val signature: String?,
    val status: Int?,
    val profileEditable: Boolean = true,
)

data class UserDeviceViewData(
    val id: Long,
    val deviceType: String,
    val deviceName: String,
    val deviceKey: String,
    val createdAt: Long,
)

data class UserDevicesPayload(
    val devices: List<UserDeviceViewData>,
    val total: Int,
)

data class SettingTargetViewData(
    val id: String,
    val name: String,
    val deviceKey: String?,
    val type: String?,
    val description: String?,
    val subType: String? = null,
)

internal fun SettingTargetViewData.requestHeaderDeviceKey(): String? =
    deviceKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: id.trim().takeIf { it.isNotEmpty() }

internal fun SettingTargetViewData.isUsingRequestHeader(activeRequestHeaderDeviceKey: String?): Boolean {
    val activeDeviceKey = activeRequestHeaderDeviceKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return requestHeaderDeviceKey() == activeDeviceKey
}

data class SettingTargetsPayload(
    val targets: List<SettingTargetViewData>,
    val total: Int,
)

data class SectionFeedback(
    val tone: AuthStatusTone,
    val text: String,
)

data class UserProfileUiState(
    val profile: UserProfileViewData? = null,
    val devices: List<UserDeviceViewData> = emptyList(),
    val deviceTotal: Int = 0,
    val isRefreshingPage: Boolean = false,
    val isWithdrawingProfileReview: Boolean = false,
    val hasCheckedProfileCache: Boolean = false,
    val removingDeviceIds: Set<Long> = emptySet(),
    val pageErrorMessage: String? = null,
    val profileFeedback: SectionFeedback? = null,
    val devicesFeedback: SectionFeedback? = null,
)

data class PersonalSettingsUiState(
    val targets: List<SettingTargetViewData> = emptyList(),
    val targetTotal: Int = 0,
    val activeRequestHeaderDeviceKey: String? = null,
    val isLoading: Boolean = false,
    val isTargetActionSubmitting: Boolean = false,
    val pageErrorMessage: String? = null,
    val feedback: SectionFeedback? = null,
)

data class EditProfileUiState(
    val profile: UserProfileViewData? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isAvatarSubmitting: Boolean = false,
    val isAvatarRemovalConfirmationVisible: Boolean = false,
    val pageErrorMessage: String? = null,
    val feedback: SectionFeedback? = null,
    val avatarFeedback: SectionFeedback? = null,
    val canRetryAvatarAction: Boolean = false,
    val pendingAvatar: DeliveredAvatarPicture? = null,
    val name: String = "",
    val signature: String = "",
    val nameFieldError: String? = null,
)

data class ChangePasswordUiState(
    val isSubmitting: Boolean = false,
    val feedback: SectionFeedback? = null,
    val pageErrorMessage: String? = null,
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val oldPasswordVisible: Boolean = false,
    val newPasswordVisible: Boolean = false,
    val confirmPasswordVisible: Boolean = false,
    val oldPasswordFieldError: String? = null,
    val newPasswordFieldError: String? = null,
    val confirmPasswordFieldError: String? = null,
)

sealed interface ProfileEffect {
    data object LoggedOut : ProfileEffect
}
