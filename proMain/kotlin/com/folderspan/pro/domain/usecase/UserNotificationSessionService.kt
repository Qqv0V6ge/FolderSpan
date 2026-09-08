package com.folderspan.pro.domain.usecase

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.UserNotificationPage
import com.folderspan.pro.domain.model.UserNotificationQuery
import com.folderspan.pro.domain.repository.UserNotificationRepository
import com.folderspan.pro.domain.repository.UserRepository

class UserNotificationSessionService(
    private val repository: UserNotificationRepository,
    userRepository: UserRepository,
    private val sessionProvider: () -> AuthSession? = SessionManager::currentSession,
) {
    private val executor = AuthorizedUserRequestExecutor(userRepository::refreshToken)

    suspend fun list(query: UserNotificationQuery): ApiResult<UserNotificationPage> =
        authorized { token -> repository.list(query, token) }

    suspend fun markRead(id: String): ApiResult<Unit> =
        authorized { token -> repository.markRead(id, token) }

    suspend fun markUnread(id: String): ApiResult<Unit> =
        authorized { token -> repository.markUnread(id, token) }

    suspend fun delete(id: String): ApiResult<Unit> =
        authorized { token -> repository.delete(id, token) }

    suspend fun batchMarkRead(ids: List<String>): ApiResult<Unit> =
        authorized { token -> repository.batchMarkRead(ids, token) }

    suspend fun stream(onMessage: suspend (AccountNotification) -> Unit): ApiResult<Unit> =
        authorized { token -> repository.stream(token, onMessage) }

    private suspend fun <T> authorized(request: suspend (String) -> ApiResult<T>): ApiResult<T> =
        executor.execute(
            session = sessionProvider(),
            actionLabel = "notifications",
            onUnauthorized = SessionManager::clear,
            request = request,
        )
}
