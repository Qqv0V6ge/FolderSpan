package com.folderspan.pro.data.mapper

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.remote.dto.FeedbackAttachmentDto
import com.folderspan.pro.data.remote.dto.FeedbackCaseDataDto
import com.folderspan.pro.data.remote.dto.FeedbackCategoriesDataDto
import com.folderspan.pro.data.remote.dto.FeedbackEventDto
import com.folderspan.pro.data.remote.dto.FeedbackListDataDto
import com.folderspan.pro.data.remote.dto.FeedbackOperationDataDto
import com.folderspan.pro.data.remote.dto.FeedbackSummaryDto
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackCase
import com.folderspan.pro.domain.model.FeedbackCategory
import com.folderspan.pro.domain.model.FeedbackEvent
import com.folderspan.pro.domain.model.FeedbackOperationResult
import com.folderspan.pro.domain.model.FeedbackPage
import com.folderspan.pro.domain.model.FeedbackSummary
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import strings.AppStrings

internal fun JsonElement.toFeedbackCategoriesResult(): ApiResult<List<FeedbackCategory>> =
    mapFeedbackPayload {
        defaultJson.decodeFromJsonElement<FeedbackCategoriesDataDto>(apiDataOrSelf())
            .list
            .mapNotNull { item ->
                val key = item.key.trim()
                if (key.isEmpty()) null else FeedbackCategory(key, item.label.trim().ifEmpty { key })
            }
    }

internal fun JsonElement.toFeedbackPageResult(): ApiResult<FeedbackPage> =
    mapFeedbackPayload {
        val data = defaultJson.decodeFromJsonElement<FeedbackListDataDto>(apiDataOrSelf())
        FeedbackPage(
            items = data.list.map(FeedbackSummaryDto::toDomain),
            total = data.total.coerceAtLeast(0),
        )
    }

internal fun JsonElement.toFeedbackCaseResult(): ApiResult<FeedbackCase> =
    mapFeedbackPayload {
        val data = defaultJson.decodeFromJsonElement<FeedbackCaseDataDto>(apiDataOrSelf())
        FeedbackCase(
            feedback = requireNotNull(data.feedback) { "Missing feedback ticket" }.toDomain(),
            events = data.events.map(FeedbackEventDto::toDomain),
            attachments = data.attachments.map(FeedbackAttachmentDto::toDomain),
            canSupplement = data.canSupplement,
            canWithdraw = data.canWithdraw,
            caseType = data.caseType,
        )
    }

internal fun JsonElement.toFeedbackAttachmentResult(): ApiResult<FeedbackAttachment> =
    mapFeedbackPayload {
        defaultJson.decodeFromJsonElement<FeedbackAttachmentDto>(apiDataOrSelf()).toDomain()
    }

internal fun JsonElement.toFeedbackOperationResult(): ApiResult<FeedbackOperationResult> =
    mapFeedbackPayload {
        val data = defaultJson.decodeFromJsonElement<FeedbackOperationDataDto>(apiDataOrSelf())
        FeedbackOperationResult(data.success, data.status, data.version)
    }

internal fun JsonElement.toBooleanOperationResult(): ApiResult<Boolean> =
    mapFeedbackPayload {
        defaultJson.decodeFromJsonElement<Boolean>(apiDataOrSelf())
    }

internal fun JsonElement.toSubmissionReferenceResult(): ApiResult<String> =
    mapFeedbackPayload {
        defaultJson.decodeFromJsonElement<String>(apiDataOrSelf()).trim()
    }

private inline fun <T> mapFeedbackPayload(block: () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (throwable: Throwable) {
        ApiResult.Failure(
            message = AppStrings.ui_operation_failed_please_try_again_later,
            cause = if (throwable is SerializationException || throwable is IllegalArgumentException) throwable else null,
        )
    }

private fun FeedbackSummaryDto.toDomain(): FeedbackSummary {
    val resolvedId = requireNotNull(id) { "Missing feedback id" }
    val resolvedUuid = uuid?.trim().orEmpty()
    require(resolvedUuid.isNotEmpty()) { "Missing feedback uuid" }
    return FeedbackSummary(
        id = resolvedId,
        uuid = resolvedUuid,
        type = type,
        category = category,
        content = content,
        contact = contact,
        status = status,
        statusNote = statusNote,
        appVersion = appVersion,
        platform = platform,
        createdAt = createdAt,
        updatedAt = updatedAt,
        priority = priority,
        dueAt = dueAt,
        unreadCount = unreadCount.coerceAtLeast(0),
        version = version.coerceAtLeast(0),
    )
}

private fun FeedbackEventDto.toDomain(): FeedbackEvent = FeedbackEvent(
    uuid = uuid,
    actorType = actorType,
    actorName = actorName,
    eventType = eventType,
    fromStatus = fromStatus,
    toStatus = toStatus,
    message = message,
    createdAt = createdAt,
)

private fun FeedbackAttachmentDto.toDomain(): FeedbackAttachment = FeedbackAttachment(
    uuid = uuid,
    originalName = originalName,
    contentType = contentType,
    size = size.coerceAtLeast(0L),
    createdAt = createdAt,
)
