package com.folderspan.pro.data.mapper

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.remote.dto.UserNotificationEnvelopeDto
import com.folderspan.pro.data.remote.dto.UserNotificationListDataDto
import com.folderspan.pro.data.remote.dto.UserNotificationActionDto
import com.folderspan.pro.data.remote.dto.UserNotificationDto
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.pro.domain.model.UserNotificationPage
import com.folderspan.pro.domain.model.UserNotificationStatus

internal fun UserNotificationEnvelopeDto<UserNotificationListDataDto>.toPageResult(): ApiResult<UserNotificationPage> {
    if (code != 0) return ApiResult.Failure(msg.orEmpty().ifBlank { "Unable to load notifications" }, apiCode = code)
    val payload = data ?: UserNotificationListDataDto()
    return ApiResult.Success(
        UserNotificationPage(
            items = payload.list.mapNotNull(UserNotificationDto::toDomainOrNull),
            total = payload.total.coerceAtLeast(0),
            page = payload.page.coerceAtLeast(1),
            pageSize = payload.pageSize.coerceIn(1, 100),
        ),
    )
}

internal fun UserNotificationDto.toDomainOrNull(): AccountNotification? {
    val normalizedId = id.trim()
    if (normalizedId.isEmpty()) return null
    return AccountNotification(
        id = normalizedId,
        userUuid = userUuid.trim(),
        title = title.trim(),
        content = content,
        locale = locale?.trim()?.takeIf(String::isNotEmpty),
        type = type.trim().ifEmpty { "general" },
        status = if (status.equals(UserNotificationStatus.Read.wireValue, ignoreCase = true)) {
            UserNotificationStatus.Read
        } else {
            UserNotificationStatus.Unread
        },
        actions = actions.asSequence()
            .mapNotNull(UserNotificationActionDto::toDomainOrNull)
            .take(MAX_NOTIFICATION_ACTIONS)
            .toList(),
        readAtEpochMillis = readAt?.toEpochMillis(),
        createdAtEpochMillis = createdAt.toEpochMillis(),
    )
}

private fun UserNotificationActionDto.toDomainOrNull(): NotificationAction? {
    if (label.isBlank()) return null
    val mappedStyle = NotificationActionStyle.entries
        .firstOrNull { item -> item.wireValue.equals(style, ignoreCase = true) }
        ?: return null
    val mappedKind = NotificationActionKind.entries
        .firstOrNull { item -> item.wireValue.equals(kind, ignoreCase = true) }
        ?: return null
    val normalizedUrl = url?.trim()?.takeIf(String::isNotEmpty)
    val normalizedRoute = route?.trim()?.takeIf(String::isNotEmpty)
    when (mappedKind) {
        NotificationActionKind.Url -> {
            if (normalizedUrl == null || normalizedRoute != null || params.isNotEmpty()) return null
        }

        NotificationActionKind.Route -> {
            if (normalizedRoute == null || normalizedUrl != null) return null
        }
    }
    return NotificationAction(
        label = label,
        style = mappedStyle,
        kind = mappedKind,
        url = normalizedUrl,
        route = normalizedRoute,
        params = params,
    )
}

internal fun Long.toEpochMillis(): Long = if (this in 1 until 10_000_000_000L) this * 1_000L else this

private const val MAX_NOTIFICATION_ACTIONS = 3
