package com.folderspan.permission

import kotlinx.coroutines.test.runTest
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertEquals

class IosNotificationPermissionTest {
    @Test
    fun recognizesTheSystemsAuthorizationStates() {
        assertEquals(PermissionStatus.Granted, iosNotificationStatus(UNAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.Granted, iosNotificationStatus(UNAuthorizationStatusProvisional))
        assertEquals(PermissionStatus.Granted, iosNotificationStatus(UNAuthorizationStatusEphemeral))
        assertEquals(PermissionStatus.Denied, iosNotificationStatus(UNAuthorizationStatusDenied))
        assertEquals(PermissionStatus.NotDetermined, iosNotificationStatus(UNAuthorizationStatusNotDetermined))
    }

    @Test
    fun missingAndUnrecognizedAuthorizationSettingsStayUnknown() {
        assertEquals(PermissionStatus.Unknown, iosNotificationStatus(null))
        assertEquals(PermissionStatus.Unknown, iosNotificationStatus(Long.MAX_VALUE))
    }

    @Test
    fun readsTheCurrentAuthorizationWithoutRequestingIt() = runTest {
        val status = readIosNotificationPermissionStatus { onResult -> onResult(UNAuthorizationStatusDenied) }

        assertEquals(PermissionStatus.Denied, status)
    }

    @Test
    fun aFailedStatusQueryDoesNotPretendPermissionWasNotRequested() = runTest {
        assertEquals(PermissionStatus.Unknown, readIosNotificationPermissionStatus { onResult -> onResult(null) })
        assertEquals(PermissionStatus.Unknown, readIosNotificationPermissionStatus { error("Unavailable") })
    }

    @Test
    fun anUnansweredQueryTimesOutAndIgnoresLateNativeResults() = runTest {
        var nativeResult: ((Long?) -> Unit)? = null

        val status = readIosNotificationPermissionStatus { onResult -> nativeResult = onResult }
        nativeResult?.invoke(UNAuthorizationStatusAuthorized)

        assertEquals(PermissionStatus.Unknown, status)
    }

    @Test
    fun aRequestErrorIsSeparateFromThePersonsAuthorizationDecision() {
        assertEquals(PermissionStatus.Unknown, iosNotificationRequestStatus(granted = false, failed = true))
        assertEquals(PermissionStatus.Unknown, iosNotificationRequestStatus(granted = true, failed = true))
        assertEquals(PermissionStatus.Granted, iosNotificationRequestStatus(granted = true, failed = false))
        assertEquals(PermissionStatus.Denied, iosNotificationRequestStatus(granted = false, failed = false))
    }
}
