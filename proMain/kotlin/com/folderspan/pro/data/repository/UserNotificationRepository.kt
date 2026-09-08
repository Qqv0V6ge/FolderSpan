package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.mapper.toDomainOrNull
import com.folderspan.pro.data.mapper.toPageResult
import com.folderspan.pro.data.remote.api.UserNotificationApiService
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.UserNotificationPage
import com.folderspan.pro.domain.model.UserNotificationQuery
import com.folderspan.pro.domain.repository.UserNotificationRepository

class DefaultUserNotificationRepository(
    private val api: UserNotificationApiService,
) : UserNotificationRepository {
    override suspend fun list(query: UserNotificationQuery, token: String): ApiResult<UserNotificationPage> =
        when (val result = api.list(query, token)) {
            is ApiResult.Success -> result.data.toPageResult()
            is ApiResult.Failure -> result
        }

    override suspend fun markRead(id: String, token: String): ApiResult<Unit> =
        api.markRead(id.trim(), token)

    override suspend fun markUnread(id: String, token: String): ApiResult<Unit> =
        api.markUnread(id.trim(), token)

    override suspend fun delete(id: String, token: String): ApiResult<Unit> =
        api.delete(id.trim(), token)

    override suspend fun batchMarkRead(ids: List<String>, token: String): ApiResult<Unit> {
        val normalized = ids.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return ApiResult.Success(Unit)
        for (chunk in normalized.chunked(100)) {
            val result = api.batchMarkRead(chunk, token)
            if (result is ApiResult.Failure) return result
        }
        return ApiResult.Success(Unit)
    }

    override suspend fun stream(
        token: String,
        onMessage: suspend (AccountNotification) -> Unit,
    ): ApiResult<Unit> = api.stream(token) { dto ->
        dto.toDomainOrNull()?.let { onMessage(it) }
    }
}
