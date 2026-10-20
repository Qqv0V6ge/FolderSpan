package com.folderspan.permission

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.coroutines.resume

internal suspend fun readIosNotificationPermissionStatus(
    query: ((Long?) -> Unit) -> Unit,
): PermissionStatus = withTimeoutOrNull(3_000L) {
    suspendCancellableCoroutine { continuation ->
        runCatching {
            query { authorizationStatus ->
                if (continuation.isActive) {
                    continuation.resume(iosNotificationStatus(authorizationStatus))
                }
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(PermissionStatus.Unknown)
        }
    }
} ?: PermissionStatus.Unknown

internal fun iosNotificationStatus(authorizationStatus: Long?): PermissionStatus = when (authorizationStatus) {
    UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional,
    UNAuthorizationStatusEphemeral -> PermissionStatus.Granted
    UNAuthorizationStatusDenied -> PermissionStatus.Denied
    UNAuthorizationStatusNotDetermined -> PermissionStatus.NotDetermined
    else -> PermissionStatus.Unknown
}

// requestAuthorization's error is separate from a person's decision to deny authorization.
// https://developer.apple.com/documentation/usernotifications/unusernotificationcenter/requestauthorization(options:completionhandler:)
internal fun iosNotificationRequestStatus(granted: Boolean, failed: Boolean): PermissionStatus = when {
    failed -> PermissionStatus.Unknown
    granted -> PermissionStatus.Granted
    else -> PermissionStatus.Denied
}
