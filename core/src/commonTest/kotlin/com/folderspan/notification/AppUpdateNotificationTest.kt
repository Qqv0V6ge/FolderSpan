package com.folderspan.notification

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.main.NotificationState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateNotificationTest : ChineseLocalizationTest() {
    @AfterTest
    fun tearDownDispatcher() {
        RequestNotificationDispatcher.setAppInForeground(false)
    }

    @Test
    fun twoPostsShareTheSameNotificationId() {
        val first = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "https://example.test/a",
            timestamp = 10L,
        )
        val second = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.3.0",
            link = "https://example.test/b",
            timestamp = 20L,
        )

        assertEquals(RequestNotificationFactory.APP_UPDATE_REQUEST_ID, first.requestId)
        assertEquals(first.requestId, second.requestId)
        assertEquals(first.notification.id, second.notification.id)
        assertNull(first.systemNotification)
        assertNull(second.systemNotification)
        assertFalse(first.notification.displayOptions.sendSystemNotification)
        assertFalse(first.notification.displayOptions.showInBanner)
        assertTrue(first.notification.displayOptions.showInBell)
    }

    @Test
    fun openActionOpensHttpsLinkAndDefaultTapDoesNot() {
        val opened = mutableListOf<String>()
        val https = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "https://example.test/app",
        )
        assertFalse(
            RequestNotificationFactory.consumeAppUpdateNotificationAction(
                metadata = https.notification.metadata,
                actionId = NotificationActionKeys.ACTION_DEFAULT,
            ) { url -> opened += url },
        )
        assertEquals(emptyList(), opened)
        assertTrue(
            RequestNotificationFactory.consumeAppUpdateNotificationAction(
                metadata = https.notification.metadata,
                actionId = NotificationActionKeys.ACTION_OPEN,
            ) { url -> opened += url },
        )
        assertEquals(listOf("https://example.test/app"), opened)

        opened.clear()
        val invalid = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "javascript:alert(1)",
        )
        assertTrue(
            RequestNotificationFactory.consumeAppUpdateNotificationAction(
                metadata = invalid.notification.metadata,
                actionId = NotificationActionKeys.ACTION_OPEN,
            ) { url -> opened += url },
        )
        assertEquals(emptyList(), opened)
        assertNull(RequestNotificationFactory.appUpdateHttpLink(invalid.notification.metadata))
    }

    @Test
    fun httpsUpdateUsesOpenDownloadSystemAction() {
        val https = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "https://example.test/app",
        )
        val actions = notificationActionsFor(https.notification.metadata)
        assertEquals(listOf(NotificationActionKeys.ACTION_OPEN), actions.map { action -> action.id })
        assertEquals(listOf(AppStrings.settings_about_software_open_link), actions.map { action -> action.title })

        val invalid = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "",
        )
        assertEquals(emptyList(), notificationActionsFor(invalid.notification.metadata))
    }

    @Test
    fun dispatcherUpsertsTheSameLocalNotification() {
        RequestNotificationDispatcher.setAppInForeground(true)
        val notificationState = NotificationState()
        val first = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.2.0",
            link = "https://example.test/a",
            timestamp = 10L,
        )
        val second = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.3.0",
            link = "https://example.test/b",
            timestamp = 20L,
        )

        RequestNotificationDispatcher.post(notificationState, first)
        RequestNotificationDispatcher.post(notificationState, second)

        val stored = notificationState.notifications.filter { item ->
            RequestNotificationFactory.isAppUpdateMetadata(item.metadata)
        }
        assertEquals(1, stored.size)
        assertEquals("1.3.0", stored.single().metadata[RequestNotificationFactory.META_APP_UPDATE_VERSION])
        assertEquals(
            RequestNotificationFactory.notificationIdFor(RequestNotificationFactory.APP_UPDATE_REQUEST_ID),
            stored.single().id,
        )
    }
}
