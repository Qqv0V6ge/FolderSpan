package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.mapper.toAppUpdatePageResult
import com.folderspan.pro.data.mapper.toAppUpdateResult
import com.folderspan.pro.data.remote.api.MessageApiService
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdatePage
import com.folderspan.pro.domain.repository.AppUpdateRepository

class DefaultAppUpdateRepository(
    private val api: MessageApiService,
) : AppUpdateRepository {
    override suspend fun latest(
        platform: String,
        channel: String,
    ): ApiResult<AppUpdate?> =
        when (val result = api.latestAppUpdate(platform, channel)) {
            is ApiResult.Success -> result.data.toAppUpdateResult()
            is ApiResult.Failure -> result
        }

    override suspend fun list(
        platform: String,
        channel: String,
        page: Int,
        pageSize: Int,
    ): ApiResult<AppUpdatePage> =
        when (val result = api.listAppUpdates(platform, channel, page, pageSize)) {
            is ApiResult.Success -> result.data.toAppUpdatePageResult()
            is ApiResult.Failure -> result
        }
}
