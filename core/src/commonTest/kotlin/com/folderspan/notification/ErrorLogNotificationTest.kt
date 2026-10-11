package com.folderspan.notification

import strings.AppStrings

import com.folderspan.data.main.device.DeviceType
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.NotificationType
import com.folderspan.utils.LogCapture
import io.github.aakira.napier.LogLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorLogNotificationTest : ChineseLocalizationTest() {
    @AfterTest
    fun tearDown() {
        RequestNotificationDispatcher.setAppInForeground(false)
        LogCapture.setErrorListener(null)
        LogCapture.setEnabled(false)
        LogCapture.clear()
    }

    @Test
    fun captureOffPostsNothing() {
        val notificationState = NotificationState()
        ErrorLogReporter.attach(notificationState)
        LogCapture.setEnabled(false)
        LogCapture.append(LogLevel.ERROR, "tag", null, "boom")
        assertEquals(0, errorLogNotifications(notificationState).size)
    }

    @Test
    fun firstErrorPostsOneNotification() {
        val notificationState = NotificationState()
        ErrorLogReporter.attach(notificationState)
        LogCapture.setEnabled(true)
        LogCapture.append(LogLevel.ERROR, "tag", null, "first-error")
        val stored = errorLogNotifications(notificationState)
        assertEquals(1, stored.size)
        assertEquals(NotificationType.Error, stored.single().type)
        assertTrue(stored.single().displayOptions.sendSystemNotification)
        assertTrue(stored.single().displayOptions.showInBanner)
        assertEquals(
            RequestNotificationFactory.ERROR_LOG_REQUEST_ID,
            stored.single().metadata[RequestNotificationFactory.META_REQUEST_ID],
        )
    }

    @Test
    fun secondErrorWithinFiveMinutesUpserts() {
        val notificationState = NotificationState()
        ErrorLogReporter.attach(notificationState)
        LogCapture.setEnabled(true)
        LogCapture.append(LogLevel.ERROR, "tag", null, "first-error")
        LogCapture.append(LogLevel.ERROR, "tag", null, "second-error")
        val stored = errorLogNotifications(notificationState)
        assertEquals(1, stored.size)
        assertEquals("second-error", stored.single().metadata[RequestNotificationFactory.META_ERROR_SUMMARY])
    }

    @Test
    fun nativeActionsContainFeedback() {
        val actions = errorLogNotificationActions(DeviceType.JVM)
        assertEquals(listOf(NotificationActionKeys.ACTION_OPEN), actions.map { action -> action.id })
        assertEquals(listOf(AppStrings.ui_feedback), actions.map { action -> action.title })
    }

    @Test
    fun webActionsAreEmpty() {
        assertEquals(emptyList(), errorLogNotificationActions(DeviceType.JS))
    }

    private fun errorLogNotifications(state: NotificationState) =
        state.notifications.filter { item -> RequestNotificationFactory.isErrorLogMetadata(item.metadata) }
}
