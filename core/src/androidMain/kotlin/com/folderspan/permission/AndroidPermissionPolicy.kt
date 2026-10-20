package com.folderspan.permission

import android.os.Build
import strings.AppStrings

internal fun androidPlatformPermissions(sdkInt: Int): List<PlatformPermission> = buildList {
    add(
        PlatformPermission(
            PermissionIds.ReadExternalStorage,
            PermissionAction.Request,
            title = AppStrings.permission_storage_title,
            description = AppStrings.permission_storage_description,
        )
    )
    if (sdkInt >= Build.VERSION_CODES.R) {
        add(
            PlatformPermission(
                PermissionIds.AllFilesAccess,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_all_files_title,
                description = AppStrings.permission_all_files_description,
            )
        )
    }
    add(
        PlatformPermission(
            PermissionIds.Notifications,
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) PermissionAction.Request else PermissionAction.OpenSettings,
            title = AppStrings.permission_notifications_title,
            description = AppStrings.permission_notifications_description,
        )
    )
    if (sdkInt >= 37) {
        add(
            PlatformPermission(
                PermissionIds.LocalNetwork,
                PermissionAction.Request,
                title = AppStrings.permission_local_network_title,
                description = AppStrings.permission_local_network_description,
            )
        )
    }
    add(
        PlatformPermission(
            PermissionIds.BatteryOptimization,
            PermissionAction.OpenSettings,
            title = AppStrings.permission_battery_title,
            description = AppStrings.permission_battery_description,
        )
    )
    add(
        PlatformPermission(
            PermissionIds.Shizuku,
            PermissionAction.Request,
            title = AppStrings.permission_shizuku_title,
            description = AppStrings.permission_shizuku_description,
            settingsInstructions = AppStrings.permission_shizuku_settings_instructions,
        )
    )
    add(
        PlatformPermission(
            PermissionIds.Root,
            PermissionAction.Request,
            title = AppStrings.permission_root_title,
            description = AppStrings.permission_root_description,
            settingsInstructions = AppStrings.permission_root_settings_instructions,
        )
    )
}

internal fun androidNotificationPermissionStatus(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    notificationsEnabled: Boolean,
): PermissionStatus {
    val canNotify = notificationsEnabled &&
        (sdkInt < Build.VERSION_CODES.TIRAMISU || runtimePermissionGranted)
    return if (canNotify) PermissionStatus.Granted else PermissionStatus.Denied
}

internal fun shouldOpenAndroidRuntimePermissionSettings(
    granted: Boolean,
    previouslyDenied: Boolean,
    shouldShowRationale: Boolean,
): Boolean = !granted && previouslyDenied && !shouldShowRationale

internal fun shouldOpenAndroidNotificationSettings(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || runtimePermissionGranted && !notificationsEnabled
