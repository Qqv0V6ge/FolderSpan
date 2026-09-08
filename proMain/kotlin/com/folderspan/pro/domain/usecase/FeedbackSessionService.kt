package com.folderspan.pro.domain.usecase

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackCase
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackDraft
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.domain.model.FeedbackOperationResult
import com.folderspan.pro.domain.model.FeedbackPage
import com.folderspan.pro.domain.model.FeedbackUpdate
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.FeedbackTransferProgressCallback
import com.folderspan.pro.domain.repository.FeedbackRepository
import com.folderspan.pro.domain.repository.UserRepository
import strings.AppStrings

/**
 * Applies the app's refresh/retry rules to feedback operations while keeping
 * category loading and genuinely signed-out submissions public.
 */
class FeedbackSessionService(
    private val repository: FeedbackRepository,
    userRepository: UserRepository,
    private val sessionProvider: () -> AuthSession? = SessionManager::currentSession,
) {
    private val executor = AuthorizedUserRequestExecutor(userRepository::refreshToken)

    suspend fun submit(
        draft: FeedbackDraft,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<String> {
        val activeSession = session ?: sessionProvider()
        return if (activeSession == null) {
            repository.submit(draft, token = null)
        } else {
            authorized(activeSession, AppStrings.ui_feedback_submit, onUnauthorized) { token ->
                repository.submit(draft, token)
            }
        }
    }

    suspend fun list(
        query: FeedbackListQuery,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<FeedbackPage> =
        authorized(session, AppStrings.ui_feedback_load_tickets, onUnauthorized) { token ->
            repository.list(query, token)
        }

    suspend fun detail(
        uuid: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<FeedbackCase> =
        authorized(session, AppStrings.ui_feedback_load_detail, onUnauthorized) { token ->
            repository.detail(uuid, token)
        }

    suspend fun update(
        update: FeedbackUpdate,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<Boolean> =
        authorized(session, AppStrings.ui_feedback_update, onUnauthorized) { token ->
            repository.update(update, token)
        }

    suspend fun delete(
        uuid: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<Boolean> =
        authorized(session, AppStrings.ui_feedback_delete, onUnauthorized) { token ->
            repository.delete(listOf(uuid), token)
        }

    suspend fun markRead(
        uuid: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<FeedbackOperationResult> =
        authorized(session, AppStrings.ui_feedback_mark_read, onUnauthorized) { token ->
            repository.markRead(uuid, token)
        }

    suspend fun supplement(
        uuid: String,
        content: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<FeedbackOperationResult> =
        authorized(session, AppStrings.ui_feedback_add_supplement, onUnauthorized) { token ->
            repository.supplement(uuid, content, token)
        }

    suspend fun withdraw(
        uuid: String,
        reason: String?,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<FeedbackOperationResult> =
        authorized(session, AppStrings.ui_feedback_withdraw, onUnauthorized) { token ->
            repository.withdraw(uuid, reason, token)
        }

    suspend fun upload(
        uuid: String,
        upload: FeedbackUpload,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
        onProgress: FeedbackTransferProgressCallback = {},
    ): ApiResult<FeedbackAttachment> =
        authorized(session, AppStrings.ui_feedback_upload_attachment, onUnauthorized) { token ->
            repository.upload(uuid, upload, token, onProgress)
        }

    suspend fun deleteAttachment(
        uuid: String,
        attachmentUuid: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
    ): ApiResult<Boolean> =
        authorized(session, AppStrings.ui_feedback_delete_attachment, onUnauthorized) { token ->
            repository.deleteAttachment(uuid, attachmentUuid, token)
        }

    suspend fun download(
        uuid: String,
        attachmentUuid: String,
        session: AuthSession?,
        onUnauthorized: () -> Unit,
        onProgress: FeedbackTransferProgressCallback = {},
    ): ApiResult<FeedbackDownload> =
        authorized(session, AppStrings.ui_feedback_download_attachment, onUnauthorized) { token ->
            repository.download(uuid, attachmentUuid, token, onProgress)
        }

    private suspend fun <T> authorized(
        session: AuthSession?,
        actionLabel: String,
        onUnauthorized: () -> Unit,
        request: suspend (String) -> ApiResult<T>,
    ): ApiResult<T> = executor.execute(
        session = session ?: sessionProvider(),
        actionLabel = actionLabel,
        onUnauthorized = onUnauthorized,
        request = request,
    )
}
