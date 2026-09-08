package com.folderspan.permission

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import strings.AppStrings
import kotlin.coroutines.resume

actual object PlatformPermissionProvider {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    actual fun permissions(): List<PlatformPermission> =
        listOf(
            PlatformPermission(
                PermissionIds.Notifications,
                PermissionAction.Request,
                title = AppStrings.permission_notifications_title,
                description = AppStrings.permission_notifications_description,
            )
        )

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        return when (permission.id) {
            PermissionIds.Notifications -> notificationStatus()
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        if (permission.id != PermissionIds.Notifications) {
            onResult(PermissionStatus.Unsupported)
            return
        }
        val options = UNAuthorizationOptionAlert or
            UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            onResult(if (granted) PermissionStatus.Granted else PermissionStatus.Denied)
        }
    }

    actual fun openSettings(permission: PlatformPermission) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    private suspend fun notificationStatus(): PermissionStatus =
        suspendCancellableCoroutine { cont ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
                cont.resume(status.toPermissionStatus())
            }
        }
}

private fun Long.toPermissionStatus(): PermissionStatus = when (this) {
    UNAuthorizationStatusAuthorized -> PermissionStatus.Granted
    UNAuthorizationStatusProvisional -> PermissionStatus.Granted
    UNAuthorizationStatusEphemeral -> PermissionStatus.Granted
    UNAuthorizationStatusDenied -> PermissionStatus.Denied
    UNAuthorizationStatusNotDetermined -> PermissionStatus.NotDetermined
    else -> PermissionStatus.Unsupported
}
