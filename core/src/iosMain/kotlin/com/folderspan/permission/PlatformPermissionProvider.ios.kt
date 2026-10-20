package com.folderspan.permission

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import strings.AppStrings

actual object PlatformPermissionProvider {
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }
    private val localNetwork = IosLocalNetworkPermission()

    actual fun permissions(): List<PlatformPermission> = iosPlatformPermissions(currentIosMajorVersion())

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        return when (permission.id) {
            PermissionIds.Notifications -> notificationStatus()
            PermissionIds.LocalNetwork -> localNetwork.status()
            PermissionIds.FilesAndFolders -> PermissionStatus.Unknown
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        if (permission.id == PermissionIds.FilesAndFolders) {
            // Access belongs to the app sandbox and individual document-picker selections.
            onResult(PermissionStatus.Unknown)
            return
        }
        if (permission.id == PermissionIds.LocalNetwork) {
            onResult(localNetwork.request())
            return
        }
        if (permission.id != PermissionIds.Notifications) {
            onResult(PermissionStatus.Unsupported)
            return
        }
        val options = UNAuthorizationOptionAlert or
            UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge
        runCatching {
            center.requestAuthorizationWithOptions(options) { granted, error ->
                onResult(iosNotificationRequestStatus(granted, error != null))
            }
        }.onFailure { onResult(PermissionStatus.Unknown) }
    }

    actual fun openSettings(permission: PlatformPermission) {
        if (permission.id !in setOf(PermissionIds.Notifications, PermissionIds.LocalNetwork)) return
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    private suspend fun notificationStatus(): PermissionStatus = readIosNotificationPermissionStatus { onResult ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            onResult(settings?.authorizationStatus)
        }
    }
}

internal fun iosPlatformPermissions(majorVersion: Int): List<PlatformPermission> = buildList {
    add(
        PlatformPermission(
            PermissionIds.FilesAndFolders,
            PermissionAction.ShowInstructions,
            title = AppStrings.permission_files_and_folders_title,
            description = AppStrings.permission_files_ios_description,
        )
    )
    add(
        PlatformPermission(
            PermissionIds.Notifications,
            PermissionAction.RequestOnce,
            title = AppStrings.permission_notifications_title,
            description = AppStrings.permission_notifications_description,
        )
    )
    if (majorVersion >= 14) {
        add(
            PlatformPermission(
                PermissionIds.LocalNetwork,
                PermissionAction.Request,
                title = AppStrings.permission_local_network_title,
                description = AppStrings.permission_local_network_ios_description,
            )
        )
    }
}
