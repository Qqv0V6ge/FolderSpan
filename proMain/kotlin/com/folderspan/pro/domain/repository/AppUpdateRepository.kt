package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.domain.model.AppUpdatePage

interface AppUpdateRepository {
    suspend fun latest(
        platform: String,
        channel: String = AppUpdateChannel.Release.token,
    ): ApiResult<AppUpdate?>

    suspend fun list(
        platform: String,
        channel: String = AppUpdateChannel.Release.token,
        page: Int = 1,
        pageSize: Int = 20,
    ): ApiResult<AppUpdatePage>
}
