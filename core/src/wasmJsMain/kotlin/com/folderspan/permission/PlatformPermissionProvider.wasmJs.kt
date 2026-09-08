@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.permission

import kotlinx.browser.window
import strings.AppStrings

actual object PlatformPermissionProvider {
    actual fun permissions(): List<PlatformPermission> =
        if (isNotificationSupported()) {
            listOf(
                PlatformPermission(
                    PermissionIds.Notifications,
                    PermissionAction.Request,
                    title = AppStrings.permission_notifications_title,
                    description = AppStrings.permission_notifications_description,
                )
            )
        } else {
            emptyList()
        }

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        return when (permission.id) {
            PermissionIds.Notifications -> {
                if (isNotificationSupported()) notificationStatus() else PermissionStatus.Unsupported
            }
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        if (permission.id != PermissionIds.Notifications || !isNotificationSupported()) {
            onResult(PermissionStatus.Unsupported)
            return
        }
        val currentStatus = notificationStatus()
        if (currentStatus != PermissionStatus.NotDetermined) {
            onResult(currentStatus)
            return
        }
        requestNotificationPermission(
            onFulfilled = { result -> onResult(result.toPermissionStatus()) },
            onRejected = { onResult(PermissionStatus.Denied) }
        )
    }

    actual fun openSettings(permission: PlatformPermission) {
        if (permission.id != PermissionIds.Notifications) return
        window.open(NOTIFICATION_SETTINGS_URL, "_blank")
    }
}

private fun isNotificationSupported(): Boolean = isNotificationSupportedJs()

@JsFun("() => typeof Notification !== 'undefined'")
private external fun isNotificationSupportedJs(): Boolean

private fun notificationStatus(): PermissionStatus =
    notificationPermissionJs().toPermissionStatus()

@JsFun("() => Notification.permission")
private external fun notificationPermissionJs(): String

@JsFun(
    """
    (onFulfilled, onRejected) => {
      try {
        if (typeof Notification === 'undefined' || !Notification.requestPermission) {
          onRejected(null);
          return;
        }
        Notification.requestPermission().then(onFulfilled).catch(onRejected);
      } catch (e) {
        onRejected(e);
      }
    }
    """
)
private external fun requestNotificationPermission(
    onFulfilled: (String) -> Unit,
    onRejected: (JsAny?) -> Unit
)

private fun String.toPermissionStatus(): PermissionStatus = when (this) {
    "granted" -> PermissionStatus.Granted
    "denied" -> PermissionStatus.Denied
    "default" -> PermissionStatus.NotDetermined
    else -> PermissionStatus.Unsupported
}

private const val NOTIFICATION_SETTINGS_URL =
    "https://support.google.com/chrome/answer/3220216?hl=zh-Hans"
