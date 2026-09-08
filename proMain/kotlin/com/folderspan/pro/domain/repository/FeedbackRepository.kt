package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.ApiResult
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

interface FeedbackRepository {
    suspend fun categories(): ApiResult<List<FeedbackCategory>>
    suspend fun submit(draft: FeedbackDraft, token: String? = null): ApiResult<String>
    suspend fun list(query: FeedbackListQuery, token: String): ApiResult<FeedbackPage>
    suspend fun detail(uuid: String, token: String): ApiResult<FeedbackCase>
    suspend fun update(update: FeedbackUpdate, token: String): ApiResult<Boolean>
    suspend fun delete(uuids: List<String>, token: String): ApiResult<Boolean>
    suspend fun markRead(uuid: String, token: String): ApiResult<FeedbackOperationResult>
    suspend fun supplement(uuid: String, content: String, token: String): ApiResult<FeedbackOperationResult>
    suspend fun withdraw(uuid: String, reason: String?, token: String): ApiResult<FeedbackOperationResult>
    suspend fun upload(
        uuid: String,
        upload: FeedbackUpload,
        token: String,
        onProgress: FeedbackTransferProgressCallback = {},
    ): ApiResult<FeedbackAttachment>
    suspend fun deleteAttachment(uuid: String, attachmentUuid: String, token: String): ApiResult<Boolean>
    suspend fun download(
        uuid: String,
        attachmentUuid: String,
        token: String,
        onProgress: FeedbackTransferProgressCallback = {},
    ): ApiResult<FeedbackDownload>
}
