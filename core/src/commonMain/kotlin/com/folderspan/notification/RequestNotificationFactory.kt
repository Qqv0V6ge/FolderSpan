package com.folderspan.notification

import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.LocalizedMessageKeys
import com.folderspan.localization.renderOrLegacy
import com.folderspan.permission.PlatformPermission
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationDisplayOptions
import com.folderspan.ui.state.main.NotificationType
import com.folderspan.utils.httpOrHttpsUrlOrNull
import strings.AppStrings
import kotlin.math.absoluteValue
import kotlin.time.Clock

enum class RequestNotificationKind {
    DeviceConnect,
    DeviceShare,
    LinkShare,
    LinkShareUpload
}

data class NotificationFactoryConfig(
    val showInBell: Boolean = true,
    val showInBanner: Boolean = false,
    val sendSystemNotification: Boolean = true
)

data class SystemNotificationPayload(
    val id: Int,
    val title: String,
    val body: String,
    val payloadData: Map<String, String>,
    val localizedTitle: LocalizedMessage? = null,
    val localizedBody: LocalizedMessage? = null,
) {
    fun displayTitle(): String = localizedTitle.renderOrLegacy(title)

    fun displayBody(): String = localizedBody.renderOrLegacy(body)
}

data class RequestNotificationBundle(
    val requestId: String,
    val notification: Notification,
    val systemNotification: SystemNotificationPayload?
)

object RequestNotificationFactory {
    const val META_REQUEST_ID = "request_id"
    const val META_REQUEST_KIND = "request_kind"
    const val META_DEVICE_ID = "device_id"
    const val META_DEVICE_NAME = "device_name"
    const val META_NOTIFICATION_KIND = "notification_kind"
    const val META_OPEN_TARGET = "open_target"
    const val META_MISSING_PERMISSION_IDS = "missing_permission_ids"

    const val KIND_PERMISSION_REMINDER = "permission_reminder"
    const val KIND_APP_UPDATE = "app_update"
    const val KIND_ERROR_LOG = "error_log_capture"
    const val APP_UPDATE_REQUEST_ID = "app_update"
    const val ERROR_LOG_REQUEST_ID = "error_log_capture"
    const val OPEN_TARGET_PERMISSION_SETTINGS = "permission_settings"
    const val OPEN_TARGET_FEEDBACK = "feedback"
    const val META_APP_UPDATE_LINK = "app_update_link"
    const val META_APP_UPDATE_VERSION = "app_update_version"
    const val META_ERROR_SUMMARY = "error_summary"

    fun requestId(kind: RequestNotificationKind, deviceId: String): String {
        return "${kind.name}:$deviceId"
    }

    fun notificationIdFor(requestId: String): Long {
        return stableIntId(requestId).toLong()
    }

    fun systemNotificationIdFor(requestId: String): Int {
        return stableIntId(requestId)
    }

    fun permissionReminderRequestId(missingPermissionIds: List<String>): String {
        val normalized = missingPermissionIds
            .map { item -> item.trim() }
            .filter { item -> item.isNotEmpty() }
            .distinct()
            .sorted()
        return "permission_reminder:${normalized.joinToString(",")}"
    }

    fun isPermissionReminderMetadata(metadata: Map<String, String>): Boolean {
        return metadata[META_NOTIFICATION_KIND] == KIND_PERMISSION_REMINDER &&
            metadata[META_OPEN_TARGET] == OPEN_TARGET_PERMISSION_SETTINGS
    }

    fun isAppUpdateMetadata(metadata: Map<String, String>): Boolean {
        return metadata[META_NOTIFICATION_KIND] == KIND_APP_UPDATE
    }

    fun isErrorLogMetadata(metadata: Map<String, String>): Boolean {
        return metadata[META_NOTIFICATION_KIND] == KIND_ERROR_LOG &&
            metadata[META_OPEN_TARGET] == OPEN_TARGET_FEEDBACK
    }

    fun errorLogSummary(metadata: Map<String, String>): String {
        return metadata[META_ERROR_SUMMARY].orEmpty()
    }

    fun buildErrorLogNotification(
        summary: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = ERROR_LOG_NOTIFICATION_CONFIG,
    ): RequestNotificationBundle {
        val trimmed = summary.trim().ifBlank { AppStrings.notification_error_log_body }
        val localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_ERROR_LOG_TITLE)
        val localizedMessage = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_ERROR_LOG_BODY)
        return buildCustomNotificationBundle(
            kind = KIND_ERROR_LOG,
            title = localizedTitle.render(),
            message = localizedMessage.render(),
            type = NotificationType.Error,
            category = LocalizedMessage(LocalizedMessageKeys.CATEGORY_ERROR).render(),
            metadata = mapOf(
                META_OPEN_TARGET to OPEN_TARGET_FEEDBACK,
                META_ERROR_SUMMARY to trimmed.take(500),
            ),
            timestamp = timestamp,
            requestId = ERROR_LOG_REQUEST_ID,
            config = config,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_ERROR),
        )
    }

    fun appUpdateHttpLink(metadata: Map<String, String>): String? {
        return httpOrHttpsUrlOrNull(metadata[META_APP_UPDATE_LINK].orEmpty())
    }

    fun consumeAppUpdateNotificationAction(
        metadata: Map<String, String>,
        actionId: String?,
        openUrl: (String) -> Unit,
    ): Boolean {
        if (!isAppUpdateMetadata(metadata)) return false
        val normalizedAction = actionId?.trim().orEmpty()
            .ifEmpty { NotificationActionKeys.ACTION_DEFAULT }
        if (normalizedAction != NotificationActionKeys.ACTION_OPEN) return false
        appUpdateHttpLink(metadata)?.let(openUrl)
        return true
    }

    fun buildAppUpdateNotification(
        version: String,
        link: String,
        title: String = "",
        message: String = "",
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = APP_UPDATE_NOTIFICATION_CONFIG,
    ): RequestNotificationBundle {
        val localizedTitle = if (title.isBlank()) {
            LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_APP_UPDATE_TITLE)
        } else {
            LocalizedMessage.fromLegacy(title)
        }
        val localizedMessage = if (message.isBlank()) {
            LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_APP_UPDATE_BODY,
                args = mapOf("version" to version),
            )
        } else {
            LocalizedMessage.fromLegacy(message)
        }
        return buildCustomNotificationBundle(
            kind = KIND_APP_UPDATE,
            title = localizedTitle?.render().orEmpty().ifBlank { title },
            message = localizedMessage?.render().orEmpty().ifBlank { message },
            type = NotificationType.Info,
            category = LocalizedMessage(LocalizedMessageKeys.CATEGORY_UPDATE).render(),
            metadata = mapOf(
                META_APP_UPDATE_LINK to link,
                META_APP_UPDATE_VERSION to version,
            ),
            timestamp = timestamp,
            requestId = APP_UPDATE_REQUEST_ID,
            config = config,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_UPDATE),
        )
    }

    fun buildDeviceConnectNotification(
        deviceId: String,
        deviceName: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildRequestNotification(
            kind = RequestNotificationKind.DeviceConnect,
            deviceId = deviceId,
            deviceName = deviceName,
            localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_TITLE),
            localizedMessage = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_BODY,
                args = mapOf("deviceName" to deviceName),
            ),
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_DEVICE),
            type = NotificationType.Info,
            timestamp = timestamp,
            config = config
        )
    }

    fun buildDeviceShareNotification(
        deviceId: String,
        deviceName: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildRequestNotification(
            kind = RequestNotificationKind.DeviceShare,
            deviceId = deviceId,
            deviceName = deviceName,
            localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_TITLE),
            localizedMessage = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_BODY,
                args = mapOf("deviceName" to deviceName),
            ),
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE),
            type = NotificationType.Info,
            timestamp = timestamp,
            config = config
        )
    }

    fun buildLinkShareNotification(
        deviceId: String,
        deviceName: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildRequestNotification(
            kind = RequestNotificationKind.LinkShare,
            deviceId = deviceId,
            deviceName = deviceName,
            localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_TITLE),
            localizedMessage = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_BODY,
                args = mapOf("deviceName" to deviceName),
            ),
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE),
            type = NotificationType.Info,
            timestamp = timestamp,
            config = config
        )
    }

    fun buildLinkShareUploadNotification(
        deviceId: String,
        deviceName: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildRequestNotification(
            kind = RequestNotificationKind.LinkShareUpload,
            deviceId = deviceId,
            deviceName = deviceName,
            localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_TITLE),
            localizedMessage = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_BODY,
                args = mapOf("deviceName" to deviceName),
            ),
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE),
            type = NotificationType.Info,
            timestamp = timestamp,
            config = config
        )
    }

    fun buildCustomNotification(
        kind: String,
        title: String,
        message: String,
        type: NotificationType = NotificationType.Info,
        category: String = "",
        metadata: Map<String, String> = emptyMap(),
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        requestId: String = "custom:$kind:$timestamp",
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildCustomNotificationBundle(
            kind = kind,
            title = title,
            message = message,
            type = type,
            category = category,
            metadata = metadata,
            timestamp = timestamp,
            requestId = requestId,
            config = config,
            localizedTitle = LocalizedMessage.fromLegacy(title),
            localizedMessage = LocalizedMessage.fromLegacy(message),
            localizedCategory = LocalizedMessage.fromLegacy(category),
        )
    }

    fun buildLocalizedCustomNotification(
        kind: String,
        localizedTitle: LocalizedMessage,
        localizedMessage: LocalizedMessage,
        type: NotificationType = NotificationType.Info,
        localizedCategory: LocalizedMessage? = null,
        metadata: Map<String, String> = emptyMap(),
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        requestId: String = "custom:$kind:$timestamp",
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        return buildCustomNotificationBundle(
            kind = kind,
            title = localizedTitle.render(),
            message = localizedMessage.render(),
            type = type,
            category = localizedCategory?.render().orEmpty(),
            metadata = metadata,
            timestamp = timestamp,
            requestId = requestId,
            config = config,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = localizedCategory,
        )
    }

    private fun buildCustomNotificationBundle(
        kind: String,
        title: String,
        message: String,
        type: NotificationType,
        category: String,
        metadata: Map<String, String>,
        timestamp: Long,
        requestId: String,
        config: NotificationFactoryConfig,
        localizedTitle: LocalizedMessage?,
        localizedMessage: LocalizedMessage?,
        localizedCategory: LocalizedMessage?,
    ): RequestNotificationBundle {
        val mergedMetadata = metadata + mapOf(
            META_NOTIFICATION_KIND to kind,
            META_REQUEST_ID to requestId
        )
        val displayOptions = config.toDisplayOptions()
        val notification = Notification(
            id = notificationIdFor(requestId),
            title = title,
            message = message,
            timestamp = timestamp,
            type = type,
            category = category,
            metadata = mergedMetadata,
            displayOptions = displayOptions,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = localizedCategory,
        )
        val systemNotification = if (displayOptions.sendSystemNotification) {
            SystemNotificationPayload(
                id = systemNotificationIdFor(requestId),
                title = title,
                body = message,
                payloadData = mergedMetadata + (META_REQUEST_ID to requestId),
                localizedTitle = localizedTitle,
                localizedBody = localizedMessage,
            )
        } else null
        return RequestNotificationBundle(requestId, notification, systemNotification)
    }

    fun buildPermissionReminderNotification(
        missingPermissions: List<PlatformPermission>,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        config: NotificationFactoryConfig = NotificationFactoryConfig()
    ): RequestNotificationBundle {
        val normalizedMissing = missingPermissions
            .distinctBy { item -> item.id }
            .sortedBy { item -> item.id }
        val missingIds = normalizedMissing.map { item -> item.id }
        val requestId = permissionReminderRequestId(missingIds)
        val metadata = mapOf(
            META_REQUEST_ID to requestId,
            META_NOTIFICATION_KIND to KIND_PERMISSION_REMINDER,
            META_OPEN_TARGET to OPEN_TARGET_PERMISSION_SETTINGS,
            META_MISSING_PERMISSION_IDS to missingIds.joinToString(",")
        )
        val displayOptions = config.toDisplayOptions()
        val localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_PERMISSION_TITLE)
        val localizedMessage = buildPermissionReminderLocalizedMessage(normalizedMissing)
        val localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_PERMISSION)
        val notification = Notification(
            id = notificationIdFor(requestId),
            title = localizedTitle.render(),
            message = localizedMessage.render(),
            timestamp = timestamp,
            type = NotificationType.Warning,
            category = localizedCategory.render(),
            metadata = metadata,
            displayOptions = displayOptions,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = localizedCategory,
        )
        val systemNotification = if (displayOptions.sendSystemNotification) {
            SystemNotificationPayload(
                id = systemNotificationIdFor(requestId),
                title = notification.title,
                body = notification.message,
                payloadData = metadata,
                localizedTitle = localizedTitle,
                localizedBody = localizedMessage,
            )
        } else null
        return RequestNotificationBundle(requestId, notification, systemNotification)
    }

    private fun buildRequestNotification(
        kind: RequestNotificationKind,
        deviceId: String,
        deviceName: String,
        localizedTitle: LocalizedMessage,
        localizedMessage: LocalizedMessage,
        localizedCategory: LocalizedMessage,
        type: NotificationType,
        timestamp: Long,
        config: NotificationFactoryConfig
    ): RequestNotificationBundle {
        val requestId = requestId(kind, deviceId)
        val metadata = mapOf(
            META_REQUEST_ID to requestId,
            META_REQUEST_KIND to kind.name,
            META_DEVICE_ID to deviceId,
            META_DEVICE_NAME to deviceName,
            META_NOTIFICATION_KIND to kind.name
        )
        val displayOptions = config.toDisplayOptions()
        val title = localizedTitle.render()
        val message = localizedMessage.render()
        val category = localizedCategory.render()
        val notification = Notification(
            id = notificationIdFor(requestId),
            title = title,
            message = message,
            timestamp = timestamp,
            type = type,
            category = category,
            metadata = metadata,
            displayOptions = displayOptions,
            localizedTitle = localizedTitle,
            localizedMessage = localizedMessage,
            localizedCategory = localizedCategory,
        )
        val systemNotification = if (displayOptions.sendSystemNotification) {
            SystemNotificationPayload(
                id = systemNotificationIdFor(requestId),
                title = title,
                body = message,
                payloadData = metadata,
                localizedTitle = localizedTitle,
                localizedBody = localizedMessage,
            )
        } else null
        return RequestNotificationBundle(requestId, notification, systemNotification)
    }

    private fun buildPermissionReminderLocalizedMessage(
        missingPermissions: List<PlatformPermission>,
    ): LocalizedMessage {
        val permissionIds = missingPermissions
            .map { item -> item.id }
            .filter { item -> item.isNotBlank() }
        if (permissionIds.isEmpty()) {
            return LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_PERMISSION_BODY)
        }
        return LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_PERMISSION_BODY_WITH_ITEMS,
            args = mapOf("permissionIds" to permissionIds.joinToString(",")),
        )
    }

    private fun stableIntId(input: String): Int {
        val hash = input.hashCode()
        return if (hash == Int.MIN_VALUE) 0 else hash.absoluteValue
    }
}

private val APP_UPDATE_NOTIFICATION_CONFIG = NotificationFactoryConfig(
    showInBell = true,
    showInBanner = false,
    sendSystemNotification = false,
)

private val ERROR_LOG_NOTIFICATION_CONFIG = NotificationFactoryConfig(
    showInBell = true,
    showInBanner = true,
    sendSystemNotification = true,
)

private fun NotificationFactoryConfig.toDisplayOptions(
): NotificationDisplayOptions {
    return NotificationDisplayOptions(
        showInBell = showInBell,
        showInBanner = showInBanner,
        sendSystemNotification = sendSystemNotification
    )
}
