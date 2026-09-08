package com.folderspan.pro.data.mapper

import strings.AppStrings
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.remote.dto.MessageEnvelopeDto
import com.folderspan.pro.data.remote.dto.MessageItemDto
import com.folderspan.pro.data.remote.dto.MessageListDataDto
import com.folderspan.pro.domain.model.Announcement
import com.folderspan.pro.domain.model.AnnouncementPage
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.domain.model.AppUpdatePage
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle

internal fun MessageEnvelopeDto<MessageListDataDto>.toAnnouncementPageResult(): ApiResult<AnnouncementPage> {
    if (code != 0) {
        return ApiResult.Failure(msg.orEmpty().ifBlank { "Unable to load announcements" }, apiCode = code)
    }
    val payload = data ?: MessageListDataDto()
    return ApiResult.Success(
        AnnouncementPage(
            items = payload.list.mapNotNull(MessageItemDto::toDomainOrNull),
            total = payload.total.coerceAtLeast(0),
            page = payload.page.coerceAtLeast(1),
            pageSize = payload.pageSize.coerceIn(1, 100),
        ),
    )
}

internal fun MessageItemDto.toDomainOrNull(): Announcement? {
    if (id <= 0L) return null
    return Announcement(
        id = id,
        type = type.trim().ifEmpty { "announcement" },
        title = title.trim(),
        content = content,
        version = version,
        platform = platform,
        link = link,
        locale = locale?.trim()?.takeIf(String::isNotEmpty),
        publishedAtEpochMillis = publishedAt.toEpochMillis(),
        actions = link.toAnnouncementLinkActions(),
    )
}

internal fun MessageEnvelopeDto<MessageListDataDto>.toAppUpdatePageResult(): ApiResult<AppUpdatePage> {
    if (code != 0) {
        return ApiResult.Failure(msg.orEmpty().ifBlank { "Unable to load version history" }, apiCode = code)
    }
    val payload = data ?: MessageListDataDto()
    return ApiResult.Success(
        AppUpdatePage(
            items = payload.list.mapNotNull(MessageItemDto::toAppUpdateOrNull),
            total = payload.total.coerceAtLeast(0),
            page = payload.page.coerceAtLeast(1),
            pageSize = payload.pageSize.coerceIn(1, 100),
        ),
    )
}

internal fun MessageEnvelopeDto<MessageItemDto>.toAppUpdateResult(): ApiResult<AppUpdate?> {
    if (code != 0) {
        return ApiResult.Failure(msg.orEmpty().ifBlank { "Unable to check for updates" }, apiCode = code)
    }
    return ApiResult.Success(data?.toAppUpdateOrNull())
}

internal fun MessageItemDto.toAppUpdateOrNull(): AppUpdate? {
    val normalizedVersion = version.trim()
    if (id <= 0L && normalizedVersion.isEmpty()) return null
    return AppUpdate(
        id = id,
        title = title.trim(),
        content = content,
        version = normalizedVersion,
        platform = platform,
        link = link,
        publishedAtEpochMillis = publishedAt.toEpochMillis(),
        channel = AppUpdateChannel.fromToken(channel).token,
    )
}

internal fun String.toAnnouncementLinkActions(): List<NotificationAction> {
    val normalized = trim()
    if (normalized.isEmpty()) return emptyList()
    return listOf(
        NotificationAction(
            label = AppStrings.ui_open,
            style = NotificationActionStyle.Primary,
            kind = NotificationActionKind.Url,
            url = normalized,
        ),
    )
}
