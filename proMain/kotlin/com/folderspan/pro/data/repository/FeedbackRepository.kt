package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.mapper.toBooleanOperationResult
import com.folderspan.pro.data.mapper.toFeedbackAttachmentResult
import com.folderspan.pro.data.mapper.toFeedbackCaseResult
import com.folderspan.pro.data.mapper.toFeedbackCategoriesResult
import com.folderspan.pro.data.mapper.toFeedbackOperationResult
import com.folderspan.pro.data.mapper.toFeedbackPageResult
import com.folderspan.pro.data.mapper.toSubmissionReferenceResult
import com.folderspan.pro.data.remote.api.FeedbackApiService
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import com.folderspan.pro.data.remote.dto.FeedbackSubmitRequest
import com.folderspan.pro.data.remote.dto.FeedbackUpdateRequest
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackCase
import com.folderspan.pro.domain.model.FeedbackCategory
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackDraft
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.domain.model.FeedbackOperationResult
import com.folderspan.pro.domain.model.FeedbackPage
import com.folderspan.pro.domain.model.FeedbackUpdate
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.FeedbackTransferProgressCallback
import com.folderspan.pro.domain.model.SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS
import com.folderspan.pro.domain.repository.FeedbackRepository
import strings.AppStrings

class DefaultFeedbackRepository(
    private val api: FeedbackApiService,
) : FeedbackRepository {
    override suspend fun categories(): ApiResult<List<FeedbackCategory>> =
        api.categories().flatMapSuccess { it.toFeedbackCategoriesResult() }

    override suspend fun submit(draft: FeedbackDraft, token: String?): ApiResult<String> =
        api.submit(
            request = FeedbackSubmitRequest(
                content = draft.content.trim(),
                appVersion = draft.appVersion,
                platform = draft.platform,
                category = draft.category?.trim()?.takeIf(String::isNotEmpty),
                contact = draft.contact?.trim()?.takeIf(String::isNotEmpty),
                type = draft.type.wireValue,
            ),
            token = token,
        ).flatMapSuccess { it.toSubmissionReferenceResult() }

    override suspend fun list(query: FeedbackListQuery, token: String): ApiResult<FeedbackPage> =
        api.list(query, token).flatMapSuccess { it.toFeedbackPageResult() }

    override suspend fun detail(uuid: String, token: String): ApiResult<FeedbackCase> =
        api.detail(uuid, token).flatMapSuccess { it.toFeedbackCaseResult() }

    override suspend fun update(update: FeedbackUpdate, token: String): ApiResult<Boolean> =
        api.update(
            FeedbackUpdateRequest(
                uuid = update.uuid,
                content = update.content.trim(),
                appVersion = update.appVersion,
                platform = update.platform,
                category = update.category?.trim()?.takeIf(String::isNotEmpty),
                contact = update.contact?.trim()?.takeIf(String::isNotEmpty),
                type = update.type?.wireValue,
            ),
            token,
        ).flatMapSuccess { it.toBooleanOperationResult() }

    override suspend fun delete(uuids: List<String>, token: String): ApiResult<Boolean> =
        api.delete(uuids.map(String::trim).filter(String::isNotEmpty).distinct(), token)
            .flatMapSuccess { it.toBooleanOperationResult() }

    override suspend fun markRead(uuid: String, token: String): ApiResult<FeedbackOperationResult> =
        api.markRead(uuid, token).flatMapSuccess { it.toFeedbackOperationResult() }

    override suspend fun supplement(
        uuid: String,
        content: String,
        token: String,
    ): ApiResult<FeedbackOperationResult> =
        api.supplement(uuid, content.trim(), token).flatMapSuccess { it.toFeedbackOperationResult() }

    override suspend fun withdraw(
        uuid: String,
        reason: String?,
        token: String,
    ): ApiResult<FeedbackOperationResult> =
        api.withdraw(uuid, reason?.trim()?.takeIf(String::isNotEmpty), token)
            .flatMapSuccess { it.toFeedbackOperationResult() }

    override suspend fun upload(
        uuid: String,
        upload: FeedbackUpload,
        token: String,
        onProgress: FeedbackTransferProgressCallback,
    ): ApiResult<FeedbackAttachment> {
        if (upload.bytes.size.toLong() > MAX_FEEDBACK_ATTACHMENT_BYTES) {
            return ApiResult.Failure(AppStrings.ui_feedback_attachment_limit)
        }
        val extension = upload.fileName.substringAfterLast('.', "").lowercase()
        if (extension !in SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS) {
            return ApiResult.Failure(AppStrings.ui_feedback_attachment_type_unsupported)
        }
        return api.upload(uuid, upload, token, onProgress)
            .flatMapSuccess { it.toFeedbackAttachmentResult() }
    }

    override suspend fun deleteAttachment(
        uuid: String,
        attachmentUuid: String,
        token: String,
    ): ApiResult<Boolean> =
        api.deleteAttachment(uuid.trim(), attachmentUuid.trim(), token)
            .flatMapSuccess { it.toBooleanOperationResult() }

    override suspend fun download(
        uuid: String,
        attachmentUuid: String,
        token: String,
        onProgress: FeedbackTransferProgressCallback,
    ): ApiResult<FeedbackDownload> = api.download(uuid, attachmentUuid, token, onProgress)
}

private inline fun <T, R> ApiResult<T>.flatMapSuccess(transform: (T) -> ApiResult<R>): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> transform(data)
        is ApiResult.Failure -> this
    }
