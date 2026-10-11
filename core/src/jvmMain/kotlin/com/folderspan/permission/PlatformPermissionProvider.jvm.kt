package com.folderspan.permission

import strings.AppStrings

actual object PlatformPermissionProvider {
    actual fun permissions(): List<PlatformPermission> =
        if (DesktopLoginStartupManager.isCurrentPlatformSupported()) {
            listOf(
                PlatformPermission(
                    PermissionIds.LoginStartup,
                    PermissionAction.Request,
                    title = AppStrings.permission_login_startup_title,
                    description = AppStrings.permission_login_startup_description,
                )
            )
        } else {
            emptyList()
        }

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        return when (permission.id) {
            PermissionIds.LoginStartup -> DesktopLoginStartupManager.status()
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        val result = when (permission.id) {
            PermissionIds.LoginStartup -> DesktopLoginStartupManager.enable()
            else -> PermissionStatus.Unsupported
        }
        onResult(result)
    }

    actual fun openSettings(permission: PlatformPermission) {
        if (permission.id == PermissionIds.LoginStartup) {
            DesktopLoginStartupManager.openSettings()
        }
    }
}
