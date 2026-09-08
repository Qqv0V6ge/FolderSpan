package com.folderspan.notification

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import strings.AppStrings

@RunWith(AndroidJUnit4::class)
class LocalNotificationDeviceTest {
    @Test
    fun deviceConnectNotificationDeliversActionAndBodyClicksExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val events = mutableListOf<NotificationPayload>()
        val eventChannel = Channel<NotificationPayload>(Channel.UNLIMITED)
        LocalNotifier.addClickListener { payload ->
            synchronized(events) {
                events += payload
            }
            eventChannel.trySend(payload)
        }

        val actionNotificationId = 26_001
        val bodyNotificationId = 26_002
        try {
            assertTrue(
                LocalNotifier.notify(
                    id = actionNotificationId,
                    title = ACTION_NOTIFICATION_TITLE,
                    body = "Tap Approve",
                    payloadData = deviceConnectPayload("api26-action"),
                )
            )
            val actionNotification = withTimeout(NOTIFICATION_TIMEOUT_MILLIS) {
                waitForNotification(notificationManager, actionNotificationId)
            }
            assertEquals(
                listOf(AppStrings.android_action_approve, AppStrings.android_action_reject),
                actionNotification.notification.actions?.map { action -> action.title.toString() },
            )

            val actionEvent = withTimeout(INTERACTION_TIMEOUT_MILLIS) { eventChannel.receive() }
            assertEquals(NotificationActionKeys.ACTION_APPROVE, actionEvent[NotificationActionKeys.ACTION_ID])
            assertEquals("api26-action", actionEvent[RequestNotificationFactory.META_REQUEST_ID])
            delay(EXACTLY_ONCE_SETTLE_MILLIS)
            assertEquals(1, synchronized(events) { events.size })
            assertFalse(notificationManager.hasActiveNotification(actionNotificationId))

            assertTrue(
                LocalNotifier.notify(
                    id = bodyNotificationId,
                    title = BODY_NOTIFICATION_TITLE,
                    body = "Tap the notification body",
                    payloadData = deviceConnectPayload("api26-body"),
                )
            )
            withTimeout(NOTIFICATION_TIMEOUT_MILLIS) {
                waitForNotification(notificationManager, bodyNotificationId)
            }

            val bodyEvent = withTimeout(INTERACTION_TIMEOUT_MILLIS) { eventChannel.receive() }
            assertEquals(NotificationActionKeys.ACTION_DEFAULT, bodyEvent[NotificationActionKeys.ACTION_ID])
            assertEquals("api26-body", bodyEvent[RequestNotificationFactory.META_REQUEST_ID])
            delay(EXACTLY_ONCE_SETTLE_MILLIS)
            assertEquals(2, synchronized(events) { events.size })
            assertFalse(notificationManager.hasActiveNotification(bodyNotificationId))
        } finally {
            LocalNotifier.remove(actionNotificationId)
            LocalNotifier.remove(bodyNotificationId)
        }
    }

    private suspend fun waitForNotification(
        notificationManager: NotificationManager,
        notificationId: Int,
    ): StatusBarNotification {
        while (true) {
            val activeNotification = notificationManager.activeNotifications
                .firstOrNull { item -> item.id == notificationId }
            if (activeNotification != null) {
                return activeNotification
            }
            delay(NOTIFICATION_POLL_MILLIS)
        }
    }

    private fun NotificationManager.hasActiveNotification(notificationId: Int): Boolean {
        return activeNotifications.any { item -> item.id == notificationId }
    }

    private fun deviceConnectPayload(requestId: String): NotificationPayload {
        return mapOf(
            RequestNotificationFactory.META_REQUEST_ID to requestId,
            RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.DeviceConnect.name,
        )
    }

    private companion object {
        const val ACTION_NOTIFICATION_TITLE = "FolderSpan API 26 DeviceConnect Action"
        const val BODY_NOTIFICATION_TITLE = "FolderSpan API 26 DeviceConnect Body"
        const val NOTIFICATION_POLL_MILLIS = 100L
        const val NOTIFICATION_TIMEOUT_MILLIS = 10_000L
        const val INTERACTION_TIMEOUT_MILLIS = 120_000L
        const val EXACTLY_ONCE_SETTLE_MILLIS = 800L
    }
}
