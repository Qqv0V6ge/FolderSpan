package com.folderspan.notification

import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.ErrorLogFeedbackPayload
import com.folderspan.utils.LogCapture
import kotlin.time.Clock

object ErrorLogReporter {
    private const val THROTTLE_WINDOW_MS = 5 * 60 * 1000L

    private var notificationState: NotificationState? = null
    private var lastPostedAt: Long = 0L
    private var lastSummary: String = ""

    fun attach(notificationState: NotificationState) {
        this.notificationState = notificationState
        LogCapture.setErrorListener(::onCapturedError)
    }

    fun report(summary: String) {
        onCapturedError(summary)
    }

    fun consumePendingFeedback(summary: String = lastSummary): ErrorLogFeedbackPayload {
        val resolved = summary.trim().ifBlank { lastSummary }.ifBlank {
            strings.AppStrings.notification_error_log_body
        }
        return ErrorLogFeedbackPayload(
            summary = resolved.take(500),
            logBytes = LogCapture.snapshot(),
        )
    }

    private fun onCapturedError(summary: String) {
        val state = notificationState ?: return
        if (!LogCapture.isEnabled()) return
        val now = Clock.System.now().toEpochMilliseconds()
        lastSummary = summary.trim().ifBlank { lastSummary }
        if (lastPostedAt != 0L && now - lastPostedAt < THROTTLE_WINDOW_MS) {
            RequestNotificationDispatcher.post(
                state,
                RequestNotificationFactory.buildErrorLogNotification(
                    summary = lastSummary,
                    timestamp = now,
                ),
            )
            return
        }
        lastPostedAt = now
        RequestNotificationDispatcher.post(
            state,
            RequestNotificationFactory.buildErrorLogNotification(
                summary = lastSummary,
                timestamp = now,
            ),
        )
    }
}
