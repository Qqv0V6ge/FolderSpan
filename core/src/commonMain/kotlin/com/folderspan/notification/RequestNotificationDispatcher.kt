package com.folderspan.notification

import androidx.compose.runtime.snapshots.Snapshot
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.LogKit
import kotlinx.coroutines.flow.MutableStateFlow
import strings.AppStrings

data class RequestNotificationPostResult(
    val inAppStored: Boolean,
    val inAppVisible: Boolean,
    val systemNotificationRequested: Boolean,
    val systemNotificationSuppressed: Boolean,
    val systemNotificationPosted: Boolean
) {
    fun hasDeliveredNotification(): Boolean {
        return inAppVisible || systemNotificationPosted
    }

    fun needsFallbackHandling(): Boolean {
        return !hasDeliveredNotification()
    }
}

object RequestNotificationDispatcher {
    private val applicationForeground = MutableStateFlow(false)

    fun setAppInForeground(isForeground: Boolean) {
        applicationForeground.value = isForeground
    }

    fun post(
        notificationState: NotificationState,
        bundle: RequestNotificationBundle,
    ): RequestNotificationPostResult {
        val inAppStoreResult = runCatching {
            Snapshot.withMutableSnapshot {
                notificationState.upsert(bundle.notification)
            }
        }
        inAppStoreResult.exceptionOrNull()?.let { error ->
            LogKit.w(AppStrings.ui_write_application_internal_notification_failed_requestid_arg0_error_arg1.format(arg0 = (bundle.requestId), arg1 = (error.message).toString()), error)
        }
        val inAppStored = inAppStoreResult.isSuccess
        val inAppVisible = inAppStored &&
            applicationForeground.value &&
            bundle.notification.shouldShowInBanner()
        val systemNotificationRequested = bundle.systemNotification != null
        val alwaysPostSystemNotification =
            bundle.notification.metadata[RequestNotificationFactory.META_REQUEST_KIND] ==
                RequestNotificationKind.DeviceConnect.name ||
                RequestNotificationFactory.isErrorLogMetadata(bundle.notification.metadata)
        val systemNotificationSuppressed =
            systemNotificationRequested && inAppVisible && !alwaysPostSystemNotification
        val systemNotificationPosted = if (systemNotificationSuppressed) {
            LogKit.d(AppStrings.ui_application_notification_is_visible_skip_system_notification_requestid_arg0.format(arg0 = (bundle.requestId)))
            false
        } else {
            bundle.systemNotification?.let { payload ->
                val postResult = runCatching {
                    LocalNotifier.notify(
                        payload.id,
                        payload.displayTitle(),
                        payload.displayBody(),
                        payload.payloadData
                    )
                }
                postResult.exceptionOrNull()?.let { error ->
                    LogKit.w(AppStrings.ui_system_notification_failed_requestid_arg0_error_arg1.format(arg0 = (bundle.requestId), arg1 = (error.message).toString()), error)
                }
                postResult.getOrDefault(false)
            } ?: false
        }
        if (systemNotificationRequested && !systemNotificationPosted && !systemNotificationSuppressed) {
            LogKit.w(AppStrings.ui_system_notification_not_established_requestid_arg0.format(arg0 = (bundle.requestId)))
        }
        return RequestNotificationPostResult(
            inAppStored = inAppStored,
            inAppVisible = inAppVisible,
            systemNotificationRequested = systemNotificationRequested,
            systemNotificationSuppressed = systemNotificationSuppressed,
            systemNotificationPosted = systemNotificationPosted
        )
    }

    fun remove(notificationState: NotificationState, requestId: String) {
        Snapshot.withMutableSnapshot {
            notificationState.removeByRequestId(requestId)
        }
        val systemId = RequestNotificationFactory.systemNotificationIdFor(requestId)
        runCatching {
            LocalNotifier.remove(systemId)
        }
    }
}
