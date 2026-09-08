package com.folderspan.pro.testing

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.domain.model.*
import com.folderspan.pro.domain.repository.FeedbackRepository
import com.folderspan.pro.domain.repository.UserRepository
import kotlinx.serialization.json.JsonElement

class FakeFeedbackRepository : FeedbackRepository {
    var categoriesResult: ApiResult<List<FeedbackCategory>> = ApiResult.Success(emptyList())
    val submittedTokens = mutableListOf<String?>()
    val submittedDrafts = mutableListOf<FeedbackDraft>()
    var submitHandler: suspend (FeedbackDraft, String?) -> ApiResult<String> = { _, _ -> ApiResult.Success("ticket") }
    var listHandler: suspend (FeedbackListQuery, String) -> ApiResult<FeedbackPage> = { _, _ -> ApiResult.Success(FeedbackPage(emptyList(), 0)) }
    var detailHandler: suspend (String, String) -> ApiResult<FeedbackCase> = { _, _ -> ApiResult.Failure("unexpected detail") }
    var updateHandler: suspend (FeedbackUpdate, String) -> ApiResult<Boolean> = { _, _ -> ApiResult.Success(true) }
    var deleteHandler: suspend (List<String>, String) -> ApiResult<Boolean> = { _, _ -> ApiResult.Success(true) }
    var markReadHandler: suspend (String, String) -> ApiResult<FeedbackOperationResult> = { _, _ -> ApiResult.Success(operationResult()) }
    var supplementHandler: suspend (String, String, String) -> ApiResult<FeedbackOperationResult> = { _, _, _ -> ApiResult.Success(operationResult()) }
    var withdrawHandler: suspend (String, String?, String) -> ApiResult<FeedbackOperationResult> = { _, _, _ -> ApiResult.Success(operationResult()) }
    var uploadHandler: suspend (String, FeedbackUpload, String, FeedbackTransferProgressCallback) -> ApiResult<FeedbackAttachment> = { _, _, _, _ -> ApiResult.Failure("unexpected upload") }
    var downloadHandler: suspend (String, String, String, FeedbackTransferProgressCallback) -> ApiResult<FeedbackDownload> = { _, _, _, _ -> ApiResult.Failure("unexpected download") }
    var deleteAttachmentHandler: suspend (String, String, String) -> ApiResult<Boolean> = { _, _, _ -> ApiResult.Failure("unexpected attachment delete") }

    override suspend fun categories() = categoriesResult
    override suspend fun submit(draft: FeedbackDraft, token: String?): ApiResult<String> {
        submittedDrafts += draft
        submittedTokens += token
        return submitHandler(draft, token)
    }
    override suspend fun list(query: FeedbackListQuery, token: String) = listHandler(query, token)
    override suspend fun detail(uuid: String, token: String) = detailHandler(uuid, token)
    override suspend fun update(update: FeedbackUpdate, token: String) = updateHandler(update, token)
    override suspend fun delete(uuids: List<String>, token: String) = deleteHandler(uuids, token)
    override suspend fun markRead(uuid: String, token: String) = markReadHandler(uuid, token)
    override suspend fun supplement(uuid: String, content: String, token: String) = supplementHandler(uuid, content, token)
    override suspend fun withdraw(uuid: String, reason: String?, token: String) = withdrawHandler(uuid, reason, token)
    override suspend fun upload(uuid: String, upload: FeedbackUpload, token: String, onProgress: FeedbackTransferProgressCallback) = uploadHandler(uuid, upload, token, onProgress)
    override suspend fun download(uuid: String, attachmentUuid: String, token: String, onProgress: FeedbackTransferProgressCallback) = downloadHandler(uuid, attachmentUuid, token, onProgress)
    override suspend fun deleteAttachment(uuid: String, attachmentUuid: String, token: String) =
        deleteAttachmentHandler(uuid, attachmentUuid, token)
}

class FakeUserRepository(
    var refreshHandler: suspend (String) -> JsonResult = { ApiResult.Failure("refresh failed") },
) : UserRepository {
    override suspend fun cachedMe(token: String): JsonResult? = null
    override suspend fun me(token: String) = unexpected()
    override suspend fun listDevices(token: String) = unexpected()
    override suspend fun deleteDevice(id: Long, token: String) = unexpected()
    override suspend fun updateProfile(command: UpdateProfileCommand, token: String) = unexpected()
    override suspend fun uploadAvatar(upload: ProfileAvatarUpload, token: String) = unexpected()
    override suspend fun removeAvatar(token: String) = unexpected()
    override suspend fun withdrawProfileReview(token: String) = unexpected()
    override suspend fun changePassword(command: ChangePasswordCommand, token: String) = unexpected()
    override suspend fun refreshToken(refreshToken: String) = refreshHandler(refreshToken)

    private fun unexpected(): ApiResult<JsonElement> = ApiResult.Failure("unexpected user request")
}

fun feedbackSummary(
    uuid: String,
    unreadCount: Int = 0,
    status: String = "submitted",
): FeedbackSummary = FeedbackSummary(
    id = uuid.hashCode().toLong(),
    uuid = uuid,
    type = "feedback",
    category = "bug",
    content = "content-$uuid",
    contact = "",
    status = status,
    statusNote = "",
    appVersion = "1.0.0",
    platform = "linux",
    createdAt = "created",
    updatedAt = "updated",
    priority = "normal",
    dueAt = null,
    unreadCount = unreadCount,
    version = 1,
)

fun feedbackCase(uuid: String, unreadCount: Int = 0): FeedbackCase = FeedbackCase(
    feedback = feedbackSummary(uuid, unreadCount),
    events = emptyList(),
    attachments = emptyList(),
    canSupplement = true,
    canWithdraw = true,
    caseType = "feedback",
)

private fun operationResult() = FeedbackOperationResult(success = true, status = "submitted", version = 2)
