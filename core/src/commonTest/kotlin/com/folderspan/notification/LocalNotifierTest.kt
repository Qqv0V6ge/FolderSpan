package com.folderspan.notification

import com.folderspan.data.main.device.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNotifierTest {
    @Test
    fun deviceShareUsesSaveViewRejectActions() {
        val actions = notificationActionsFor(
            mapOf(RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.DeviceShare.name)
        )

        assertEquals(
            listOf(
                NotificationActionKeys.ACTION_SAVE,
                NotificationActionKeys.ACTION_VIEW,
                NotificationActionKeys.ACTION_REJECT,
            ),
            actions.map { action -> action.id },
        )
    }

    @Test
    fun otherRequestsUseApproveRejectActions() {
        val actions = notificationActionsFor(
            mapOf(RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.LinkShare.name)
        )

        assertEquals(
            listOf(NotificationActionKeys.ACTION_APPROVE, NotificationActionKeys.ACTION_REJECT),
            actions.map { action -> action.id },
        )
    }

    @Test
    fun appUpdateUsesOpenDownloadAction() {
        val actions = notificationActionsFor(
            RequestNotificationFactory.buildAppUpdateNotification(
                version = "1.4.0",
                link = "https://example.test/download",
            ).notification.metadata,
        )

        assertEquals(
            listOf(NotificationActionKeys.ACTION_OPEN),
            actions.map { action -> action.id },
        )
    }

    @Test
    fun errorLogUsesFeedbackActionOnNativeAndNoneOnWeb() {
        assertEquals(
            listOf(NotificationActionKeys.ACTION_OPEN),
            errorLogNotificationActions(DeviceType.Android).map { action -> action.id },
        )
        assertEquals(emptyList(), errorLogNotificationActions(DeviceType.JS))
    }

    @Test
    fun permissionReminderUsesOpenDeleteActions() {
        val actions = notificationActionsFor(
            mapOf(
                RequestNotificationFactory.META_NOTIFICATION_KIND to
                    RequestNotificationFactory.KIND_PERMISSION_REMINDER,
                RequestNotificationFactory.META_OPEN_TARGET to
                    RequestNotificationFactory.OPEN_TARGET_PERMISSION_SETTINGS,
            )
        )

        assertEquals(
            listOf(NotificationActionKeys.ACTION_OPEN, NotificationActionKeys.ACTION_DELETE),
            actions.map { action -> action.id },
        )
    }

    @Test
    fun sanitizingDropsLibraryKeysAndStringifiesValues() {
        val sanitized = sanitizeLibraryPayload(
            mapOf(
                "request_id" to "request-1",
                "action_id" to "approve",
                "notification_id" to 42,
                "attempt" to 3,
                "optional" to null,
            )
        )

        assertEquals(
            mapOf(
                "request_id" to "request-1",
                "attempt" to "3",
                "optional" to "",
            ),
            sanitized,
        )
    }

    @Test
    fun identicalClickImmediatelyAfterActionIsSuppressedOnceWithinWindow() {
        val normalizer = NotificationEventNormalizer(dedupWindowMillis = 500L)
        val payload = mapOf("request_id" to "request-1")

        normalizer.recordAction(payload, nowMillis = 1_000L)

        assertTrue(normalizer.shouldSuppressClick(payload, nowMillis = 1_500L))
        assertFalse(normalizer.shouldSuppressClick(payload, nowMillis = 1_500L))
        normalizer.recordAction(payload, nowMillis = 2_000L)
        assertFalse(normalizer.shouldSuppressClick(payload, nowMillis = 2_501L))
        assertFalse(normalizer.shouldSuppressClick(mapOf("request_id" to "request-2"), nowMillis = 1_100L))
    }
}
