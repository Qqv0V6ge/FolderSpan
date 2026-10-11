package com.folderspan.notification

import strings.AppStrings

import com.folderspan.permission.PermissionAction
import com.folderspan.permission.PlatformPermission
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.main.NotificationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionReminderNotificationTest : ChineseLocalizationTest() {
    @Test
    fun buildPermissionReminderNotificationBuildsStableMetadata() {
        val missingPermissions = listOf(
            PlatformPermission(
                id = "notifications",
                action = PermissionAction.Request,
                title = AppStrings.permission_notifications_title,
                description = ""
            ),
            PlatformPermission(
                id = "all_files_access",
                action = PermissionAction.OpenSettings,
                title = AppStrings.permission_all_files_title,
                description = ""
            ),
            PlatformPermission(
                id = "notifications",
                action = PermissionAction.Request,
                title = AppStrings.permission_notifications_title,
                description = ""
            )
        )

        val bundle = RequestNotificationFactory.buildPermissionReminderNotification(
            missingPermissions = missingPermissions,
            timestamp = 1000L,
            config = NotificationFactoryConfig(sendSystemNotification = false)
        )

        assertEquals("permission_reminder:all_files_access,notifications", bundle.requestId)
        assertEquals(NotificationType.Warning, bundle.notification.type)
        assertEquals(AppStrings.ui_permissions, bundle.notification.category)
        assertEquals(
            RequestNotificationFactory.KIND_PERMISSION_REMINDER,
            bundle.notification.metadata[RequestNotificationFactory.META_NOTIFICATION_KIND]
        )
        assertEquals(
            RequestNotificationFactory.OPEN_TARGET_PERMISSION_SETTINGS,
            bundle.notification.metadata[RequestNotificationFactory.META_OPEN_TARGET]
        )
        assertEquals(
            "all_files_access,notifications",
            bundle.notification.metadata[RequestNotificationFactory.META_MISSING_PERMISSION_IDS]
        )
        assertTrue(RequestNotificationFactory.isPermissionReminderMetadata(bundle.notification.metadata))
        assertTrue(bundle.notification.message.contains(AppStrings.permission_all_files_title))
        assertTrue(bundle.notification.shouldShowInBell())
        assertFalse(bundle.notification.shouldShowInBanner())
        assertFalse(bundle.notification.displayOptions.sendSystemNotification)
        assertNull(bundle.systemNotification)
    }

    @Test
    fun buildDeviceConnectNotificationUsesProvidedDisplayOptions() {
        val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
            deviceId = "device-1",
            deviceName = AppStrings.ui_test_device_connection_failure_dialog_testing_equipment,
            timestamp = 2000L,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = true,
                sendSystemNotification = false
            )
        )

        assertTrue(bundle.notification.shouldShowInBell())
        assertTrue(bundle.notification.shouldShowInBanner())
        assertFalse(bundle.notification.displayOptions.sendSystemNotification)
        assertNull(bundle.systemNotification)
    }

    @Test
    fun buildCustomNotificationHonorsDisplayOverrides() {
        val bundle = RequestNotificationFactory.buildCustomNotification(
            kind = "sync_finished",
            title = AppStrings.ui_synchronization_completed,
            message = AppStrings.ui_test_permission_reminder_notification_completed_3_synchronized_tasks,
            timestamp = 3000L,
            config = NotificationFactoryConfig(
                showInBell = false,
                showInBanner = true,
                sendSystemNotification = false
            )
        )

        assertFalse(bundle.notification.shouldShowInBell())
        assertTrue(bundle.notification.shouldShowInBanner())
        assertFalse(bundle.notification.displayOptions.sendSystemNotification)
        assertNull(bundle.systemNotification)
    }

    @Test
    fun missingPermissionSignatureNormalizesAndSorts() {
        val signature = StartupPermissionReminderCoordinator.missingPermissionSignature(
            listOf(" notifications ", "all_files_access", "notifications", "")
        )

        assertEquals("all_files_access,notifications", signature)
    }

    @Test
    fun shouldPostReminderOnlyWhenSignatureChanges() {
        assertTrue(StartupPermissionReminderCoordinator.shouldPostReminder(previousSignature = null, currentSignature = "a"))
        assertFalse(StartupPermissionReminderCoordinator.shouldPostReminder(previousSignature = "a", currentSignature = "a"))
        assertFalse(StartupPermissionReminderCoordinator.shouldPostReminder(previousSignature = "a", currentSignature = " "))
    }
}
