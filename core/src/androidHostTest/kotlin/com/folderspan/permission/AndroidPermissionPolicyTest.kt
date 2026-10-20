package com.folderspan.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidPermissionPolicyTest {
    @Test
    fun notificationsRemainVisibleBeforeRuntimeNotificationPermissionWasIntroduced() {
        val notification = androidPlatformPermissions(32).find { it.id == PermissionIds.Notifications }

        assertNotNull(notification)
        assertEquals(PermissionAction.OpenSettings, notification.action)
    }

    @Test
    fun modernAndroidRequestsNotificationPermission() {
        val notification = androidPlatformPermissions(33).find { it.id == PermissionIds.Notifications }

        assertNotNull(notification)
        assertEquals(PermissionAction.Request, notification.action)
    }

    @Test
    fun localNetworkPermissionAppearsOnlyWhereItIsRequired() {
        assertFalse(androidPlatformPermissions(36).any { it.id == PermissionIds.LocalNetwork })
        assertTrue(androidPlatformPermissions(37).any { it.id == PermissionIds.LocalNetwork })
    }

    @Test
    fun shizukuKeepsItsRequestAndExplainsAuthorizationInTheExternalManager() {
        val permission = androidPlatformPermissions(37).single { it.id == PermissionIds.Shizuku }

        assertEquals(PermissionAction.Request, permission.action)
        assertFalse(permission.settingsInstructions.isNullOrBlank())
    }

    @Test
    fun rootKeepsItsRequestAndExplainsAuthorizationInTheExternalManager() {
        val permission = androidPlatformPermissions(37).single { it.id == PermissionIds.Root }

        assertEquals(PermissionAction.Request, permission.action)
        assertFalse(permission.settingsInstructions.isNullOrBlank())
    }

    @Test
    fun disablingNotificationsInSettingsIsDeniedOnOlderAndroid() {
        assertEquals(
            PermissionStatus.Denied,
            androidNotificationPermissionStatus(32, runtimePermissionGranted = true, notificationsEnabled = false),
        )
    }

    @Test
    fun enabledNotificationsDoNotRequireRuntimePermissionOnOlderAndroid() {
        assertEquals(
            PermissionStatus.Granted,
            androidNotificationPermissionStatus(32, runtimePermissionGranted = false, notificationsEnabled = true),
        )
    }

    @Test
    fun systemNotificationSwitchStillMattersWhenRuntimePermissionIsGranted() {
        assertEquals(
            PermissionStatus.Denied,
            androidNotificationPermissionStatus(33, runtimePermissionGranted = true, notificationsEnabled = false),
        )
    }

    @Test
    fun runtimeNotificationPermissionIsRequiredOnModernAndroid() {
        assertEquals(
            PermissionStatus.Denied,
            androidNotificationPermissionStatus(33, runtimePermissionGranted = false, notificationsEnabled = true),
        )
    }

    @Test
    fun enabledNotificationsWithRuntimePermissionAreGranted() {
        assertEquals(
            PermissionStatus.Granted,
            androidNotificationPermissionStatus(33, runtimePermissionGranted = true, notificationsEnabled = true),
        )
    }

    @Test
    fun anAlreadyGrantedRuntimePermissionCannotRestoreTheDisabledNotificationSwitch() {
        assertTrue(
            shouldOpenAndroidNotificationSettings(
                sdkInt = 33,
                runtimePermissionGranted = true,
                notificationsEnabled = false,
            )
        )
    }

    @Test
    fun anUnrequestedNotificationPermissionStillUsesTheRuntimePrompt() {
        assertFalse(
            shouldOpenAndroidNotificationSettings(
                sdkInt = 33,
                runtimePermissionGranted = false,
                notificationsEnabled = false,
            )
        )
    }

    @Test
    fun enabledNotificationsDoNotNeedASettingsRoundTrip() {
        assertFalse(
            shouldOpenAndroidNotificationSettings(
                sdkInt = 33,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
            )
        )
    }

    @Test
    fun olderAndroidCanOnlyChangeNotificationAuthorizationInSettings() {
        assertTrue(
            shouldOpenAndroidNotificationSettings(
                sdkInt = 32,
                runtimePermissionGranted = false,
                notificationsEnabled = false,
            )
        )
    }

    @Test
    fun firstRequestDoesNotOpenSettingsJustBecauseThereIsNoRationale() {
        assertFalse(
            shouldOpenAndroidRuntimePermissionSettings(
                granted = false,
                previouslyDenied = false,
                shouldShowRationale = false,
            )
        )
    }

    @Test
    fun aDeniedPermissionWithRationaleCanBeRequestedAgain() {
        assertFalse(
            shouldOpenAndroidRuntimePermissionSettings(
                granted = false,
                previouslyDenied = true,
                shouldShowRationale = true,
            )
        )
    }

    @Test
    fun aPreviouslyDeniedPermissionWithoutRationaleOpensSettings() {
        assertTrue(
            shouldOpenAndroidRuntimePermissionSettings(
                granted = false,
                previouslyDenied = true,
                shouldShowRationale = false,
            )
        )
    }

    @Test
    fun grantingPermissionSupersedesPreviousDenial() {
        assertFalse(
            shouldOpenAndroidRuntimePermissionSettings(
                granted = true,
                previouslyDenied = true,
                shouldShowRationale = false,
            )
        )
    }
}
