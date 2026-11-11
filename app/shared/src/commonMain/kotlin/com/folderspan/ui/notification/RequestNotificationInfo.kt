package com.folderspan.ui.notification

import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.notification.RequestNotificationKind
import com.folderspan.notification.SYNC_RUN_NOTIFICATION_KIND
import com.folderspan.ui.state.main.Notification

data class RequestNotificationInfo(
    val requestId: String,
    val kind: RequestNotificationKind,
    val deviceId: String,
    val deviceName: String
)

fun Notification.toRequestInfo(): RequestNotificationInfo? {
    if (metadata[RequestNotificationFactory.META_NOTIFICATION_KIND] == SYNC_RUN_NOTIFICATION_KIND) return null
    val requestId = metadata[RequestNotificationFactory.META_REQUEST_ID] ?: return null
    val kindName = metadata[RequestNotificationFactory.META_REQUEST_KIND] ?: return null
    val kind = runCatching { RequestNotificationKind.valueOf(kindName) }.getOrNull() ?: return null
    val deviceId = metadata[RequestNotificationFactory.META_DEVICE_ID] ?: return null
    val deviceName = metadata[RequestNotificationFactory.META_DEVICE_NAME] ?: deviceId
    return RequestNotificationInfo(requestId, kind, deviceId, deviceName)
}
