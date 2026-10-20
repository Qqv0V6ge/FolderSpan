package com.folderspan.permission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import strings.AppStrings

actual object PlatformPermissionProvider {
    private val notifications = MacNotificationPermission()
    private val desktopPrivacy = DesktopPrivacyPermissionManager()
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isMacOs: Boolean
        get() = MacPermissionSettings.supports(10)

    actual fun permissions(): List<PlatformPermission> =
        desktopPlatformPermissions(System.getProperty("os.name").orEmpty(), System.getProperty("os.version").orEmpty())

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        return when (permission.id) {
            PermissionIds.Notifications -> if (isMacOs) notifications.status() else withContext(Dispatchers.IO) {
                desktopPrivacy.status(permission.id)
            }
            PermissionIds.LocalNetwork -> if (isMacOs) MacLocalNetworkPermission.status() else desktopPrivacy.status(permission.id)
            PermissionIds.FilesAndFolders, PermissionIds.FullDiskAccess,
            PermissionIds.AppManagement -> if (isMacOs) MacPermissionSettings.privacyStatus(permission.id) else desktopPrivacy.status(permission.id)
            PermissionIds.LoginStartup -> DesktopLoginStartupManager.status()
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        if (!isMacOs && permission.id in setOf(PermissionIds.Notifications, PermissionIds.LocalNetwork, PermissionIds.FilesAndFolders)) {
            // These platforms use native settings or per-path access controls.
            // Opening a panel must not be reported as a successful authorization.
            openSettings(permission)
            onResult(PermissionStatus.Unknown)
            return
        }
        if (permission.id == PermissionIds.Notifications) {
            notifications.request(onResult)
            return
        }
        val result = when (permission.id) {
            PermissionIds.LocalNetwork -> MacLocalNetworkPermission.request()
            PermissionIds.FilesAndFolders, PermissionIds.FullDiskAccess, PermissionIds.AppManagement -> {
                MacPermissionSettings.open(permission.id)
                MacPermissionSettings.privacyStatus(permission.id)
            }
            PermissionIds.LoginStartup -> DesktopLoginStartupManager.enable()
            else -> PermissionStatus.Unsupported
        }
        onResult(result)
    }

    actual fun openSettings(permission: PlatformPermission) {
        settingsScope.launch {
            when (permission.id) {
                PermissionIds.Notifications, PermissionIds.LocalNetwork,
                PermissionIds.FilesAndFolders, PermissionIds.FullDiskAccess,
                PermissionIds.AppManagement -> if (isMacOs) MacPermissionSettings.open(permission.id) else desktopPrivacy.openSettings(permission.id)
                PermissionIds.LoginStartup -> DesktopLoginStartupManager.openSettings()
            }
        }
    }
}

internal fun desktopPlatformPermissions(osName: String, osVersion: String): List<PlatformPermission> = buildList {
    val isWindows = osName.contains("windows", ignoreCase = true)
    val isLinux = osName.contains("linux", ignoreCase = true)
    if (isWindows || isLinux) {
        add(
            PlatformPermission(
                PermissionIds.Notifications,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_notifications_title,
                description = if (isWindows) AppStrings.permission_notifications_windows_description else AppStrings.permission_notifications_linux_description,
            )
        )
        add(
            PlatformPermission(
                PermissionIds.LocalNetwork,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_local_network_title,
                description = if (isWindows) AppStrings.permission_local_network_windows_description else AppStrings.permission_local_network_linux_description,
            )
        )
        add(
            PlatformPermission(
                PermissionIds.FilesAndFolders,
                PermissionAction.ShowInstructions,
                title = AppStrings.permission_files_and_folders_title,
                description = if (isWindows) AppStrings.permission_files_windows_description else AppStrings.permission_files_linux_description,
            )
        )
    }
    if (MacLocalNetworkPermission.isSupported(osName, osVersion)) {
        add(
            PlatformPermission(
                PermissionIds.LocalNetwork,
                PermissionAction.Request,
                title = AppStrings.permission_local_network_title,
                description = AppStrings.permission_local_network_macos_description,
            )
        )
    }
    if (MacPermissionSettings.supports(10, 14, osName, osVersion)) {
        add(
            PlatformPermission(
                PermissionIds.Notifications,
                PermissionAction.RequestOnce,
                title = AppStrings.permission_notifications_title,
                description = AppStrings.permission_notifications_description,
            )
        )
        add(
            PlatformPermission(
                PermissionIds.FullDiskAccess,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_full_disk_access_title,
                description = AppStrings.permission_full_disk_access_description,
            )
        )
    }
    if (MacPermissionSettings.supports(10, 15, osName, osVersion)) {
        add(
            PlatformPermission(
                PermissionIds.FilesAndFolders,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_files_and_folders_title,
                description = AppStrings.permission_files_and_folders_description,
            )
        )
    }
    if (MacPermissionSettings.supports(13, osName = osName, osVersion = osVersion)) {
        add(
            PlatformPermission(
                PermissionIds.AppManagement,
                PermissionAction.OpenSettings,
                title = AppStrings.permission_app_management_title,
                description = AppStrings.permission_app_management_description,
            )
        )
    }
    if (DesktopLoginStartupManager.isCurrentPlatformSupported(osName)) {
        add(
            PlatformPermission(
                PermissionIds.LoginStartup,
                PermissionAction.Request,
                title = AppStrings.permission_login_startup_title,
                description = AppStrings.permission_login_startup_description,
            )
        )
    }
}
