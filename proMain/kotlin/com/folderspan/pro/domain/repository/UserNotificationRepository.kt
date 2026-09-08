package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.UserNotificationPage
import com.folderspan.pro.domain.model.UserNotificationQuery

interface UserNotificationRepository {
    suspend fun list(query: UserNotificationQuery, token: String): ApiResult<UserNotificationPage>
    suspend fun markRead(id: String, token: String): ApiResult<Unit>
    suspend fun markUnread(id: String, token: String): ApiResult<Unit>
    suspend fun delete(id: String, token: String): ApiResult<Unit>
    suspend fun batchMarkRead(ids: List<String>, token: String): ApiResult<Unit>
    suspend fun stream(token: String, onMessage: suspend (AccountNotification) -> Unit): ApiResult<Unit>
}
