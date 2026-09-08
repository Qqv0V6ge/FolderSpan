package com.folderspan.pro.presentation.screen.profile

import kotlin.test.*
import strings.AppStrings

class UserProfilePageDeviceTextTest {
    @Test
    fun profileContentModeTreatsMissingProfileDuringRefreshAsRequesting() {
        val mode = resolveUserProfileContentMode(
            hasProfile = false,
            hasCheckedProfileCache = true,
            isRefreshingPage = true,
        )

        assertEquals(UserProfileContentMode.Requesting, mode)
    }

    @Test
    fun profileContentModeOnlyFallsBackAfterRefreshFinished() {
        val mode = resolveUserProfileContentMode(
            hasProfile = false,
            hasCheckedProfileCache = true,
            isRefreshingPage = false,
        )

        assertEquals(UserProfileContentMode.Fallback, mode)
    }

    @Test
    fun loginDevicesContentModeShowsLoadingWhenEmptyDevicesAreRefreshing() {
        val mode = resolveLoginDevicesContentMode(
            hasDevices = false,
            isRefreshingPage = true,
            hasPageError = false,
        )

        assertEquals(LoginDevicesContentMode.Loading, mode)
    }

    @Test
    fun loginDevicesContentModeOnlyFallsBackWhenEmptyDevicesFailedAfterRefresh() {
        val mode = resolveLoginDevicesContentMode(
            hasDevices = false,
            isRefreshingPage = false,
            hasPageError = true,
        )

        assertEquals(LoginDevicesContentMode.Fallback, mode)
    }

    @Test
    fun loginDeviceSupportingTextOnlyShowsLoginTime() {
        val text = loginDeviceSupportingText(
            UserDeviceViewData(
                id = 1L,
                deviceType = "Android",
                deviceName = "Work Phone",
                deviceKey = "device-key",
                createdAt = 0L,
            ),
        )

        assertEquals(AppStrings.ui_login_time_arg0.format(arg0 = AppStrings.ui_unknown), text)
        assertFalse(text.contains(AppStrings.ui_type))
        assertFalse(text.contains(AppStrings.ui_test_user_profile_page_device_text_identifier))
        assertFalse(text.contains(AppStrings.ui_creation_time))
    }

    @Test
    fun settingTargetSupportingTextDoesNotShowType() {
        val text = settingTargetSupportingText(
            SettingTargetViewData(
                id = "target-id",
                name = "Workstation",
                deviceKey = "device-key",
                type = "device",
                subType = "JVM",
                description = null,
            ),
        )

        assertEquals(AppStrings.ui_can_copied_this_device, text)
        assertFalse(text.orEmpty().contains(AppStrings.ui_type))
        assertFalse(text.orEmpty().contains(AppStrings.ui_test_user_profile_page_device_text_identifier))
        assertFalse(text.orEmpty().contains("device-key"))
    }

    @Test
    fun settingTargetSupportingTextDoesNotShowTargetId() {
        val text = settingTargetSupportingText(
            SettingTargetViewData(
                id = "target-id",
                name = "Workstation",
                deviceKey = null,
                type = "device",
                subType = "JVM",
                description = null,
            ),
        )

        assertNull(text)
    }

    @Test
    fun personalSettingsTargetMenuLabelsShowUseOnlyWhenTargetIsNotActive() {
        assertEquals(
            listOf(AppStrings.profile_use_settings, AppStrings.profile_copy_settings_to_device, AppStrings.profile_delete_settings),
            personalSettingsTargetMenuLabels(isTargetInUse = false),
        )
    }

    @Test
    fun personalSettingsTargetMenuLabelsShowCancelUseOnlyWhenTargetIsActive() {
        assertEquals(
            listOf(AppStrings.profile_restore_device_settings, AppStrings.profile_copy_settings_to_device, AppStrings.profile_delete_settings),
            personalSettingsTargetMenuLabels(isTargetInUse = true),
        )
    }

    @Test
    fun settingTargetInUseFallsBackToTargetIdWhenDeviceKeyMissing() {
        val target = SettingTargetViewData(
            id = "target-id",
            name = "Workstation",
            deviceKey = null,
            type = "device",
            subType = "JVM",
            description = null,
        )

        assertTrue(target.isUsingRequestHeader("target-id"))
        assertFalse(target.isUsingRequestHeader("other-target-id"))
        assertFalse(target.isUsingRequestHeader(null))
    }

    @Test
    fun settingTargetRequestHeaderDeviceKeyPrefersDeviceKeyOverTargetId() {
        val target = SettingTargetViewData(
            id = "target-id",
            name = "Workstation",
            deviceKey = "device-key",
            type = "device",
            subType = "JVM",
            description = null,
        )

        assertEquals(
            "device-key",
            target.requestHeaderDeviceKey(),
        )
    }
}
