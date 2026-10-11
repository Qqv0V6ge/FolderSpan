package com.folderspan.notification

import strings.AppStrings

import com.folderspan.ui.state.main.NotificationState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestNotificationDispatcherTest {
    @AfterTest
    fun tearDown() {
        RequestNotificationDispatcher.setAppInForeground(false)
    }

    @Test
    fun postTreatsBellOnlyNotificationAsStoredButNotVisible() {
        RequestNotificationDispatcher.setAppInForeground(true)
        val notificationState = NotificationState()
        val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
            deviceId = "device-1",
            deviceName = AppStrings.ui_test_device_connection_failure_dialog_testing_equipment,
            timestamp = 1000L,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = false,
                sendSystemNotification = false
            )
        )

        val result = RequestNotificationDispatcher.post(notificationState, bundle)

        assertTrue(result.inAppStored)
        assertFalse(result.inAppVisible)
        assertFalse(result.systemNotificationRequested)
        assertFalse(result.systemNotificationSuppressed)
        assertFalse(result.systemNotificationPosted)
        assertFalse(result.hasDeliveredNotification())
        assertTrue(result.needsFallbackHandling())
        val stored = notificationState.findByRequestId(bundle.requestId)
        assertNotNull(stored)
        assertEquals(bundle.notification.id, stored.id)
    }

    @Test
    fun postSuppressesSystemNotificationForForegroundNonConnectBanner() {
        RequestNotificationDispatcher.setAppInForeground(true)
        val notificationState = NotificationState()
        val bundle = RequestNotificationFactory.buildDeviceShareNotification(
            deviceId = "device-2",
            deviceName = AppStrings.ui_test_request_notification_dispatcher_test_equipment_2,
            timestamp = 1500L,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = true,
                sendSystemNotification = true
            )
        )

        val result = RequestNotificationDispatcher.post(notificationState, bundle)

        assertTrue(result.inAppStored)
        assertTrue(result.inAppVisible)
        assertTrue(result.systemNotificationRequested)
        assertTrue(result.systemNotificationSuppressed)
        assertFalse(result.systemNotificationPosted)
        assertTrue(result.hasDeliveredNotification())
        assertFalse(result.needsFallbackHandling())
    }

    @Test
    fun postDoesNotSuppressDeviceConnectSystemNotificationWhenForegroundBannerIsVisible() {
        RequestNotificationDispatcher.setAppInForeground(true)
        val notificationState = NotificationState()
        val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
            deviceId = "device-connect-foreground",
            deviceName = AppStrings.ui_test_request_notification_dispatcher_frontend_connection_to_device,
            timestamp = 1600L,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = true,
                sendSystemNotification = true,
            ),
        )

        val result = RequestNotificationDispatcher.post(notificationState, bundle)

        assertTrue(result.inAppStored)
        assertTrue(result.inAppVisible)
        assertTrue(result.systemNotificationRequested)
        assertFalse(result.systemNotificationSuppressed)
    }

    @Test
    fun postDoesNotSuppressSystemNotificationWhenAppIsBackground() {
        RequestNotificationDispatcher.setAppInForeground(false)
        val notificationState = NotificationState()
        val bundle = RequestNotificationFactory.buildDeviceConnectNotification(
            deviceId = "device-background",
            deviceName = AppStrings.ui_test_request_notification_dispatcher_backend_equipment,
            timestamp = 1750L,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = true,
                sendSystemNotification = true,
            ),
        )

        val result = RequestNotificationDispatcher.post(notificationState, bundle)

        assertTrue(result.inAppStored)
        assertFalse(result.inAppVisible)
        assertTrue(result.systemNotificationRequested)
        assertFalse(result.systemNotificationSuppressed)
    }

    @Test
    fun postTreatsHiddenInAppNotificationWithoutSystemDeliveryAsUndelivered() {
        val notificationState = NotificationState()
        val bundle = RequestNotificationFactory.buildCustomNotification(
            kind = "background_only",
            title = AppStrings.ui_test_request_notification_dispatcher_event_backend,
            message = AppStrings.ui_test_request_notification_dispatcher_this_notification_will_not_appear_in,
            timestamp = 2000L,
            config = NotificationFactoryConfig(
                showInBell = false,
                showInBanner = false,
                sendSystemNotification = false
            )
        )

        val result = RequestNotificationDispatcher.post(notificationState, bundle)

        assertTrue(result.inAppStored)
        assertFalse(result.inAppVisible)
        assertFalse(result.systemNotificationRequested)
        assertFalse(result.systemNotificationSuppressed)
        assertFalse(result.systemNotificationPosted)
        assertFalse(result.hasDeliveredNotification())
        assertTrue(result.needsFallbackHandling())
        val stored = notificationState.findByRequestId(bundle.requestId)
        assertNotNull(stored)
        assertEquals(bundle.notification.id, stored.id)
    }
}
