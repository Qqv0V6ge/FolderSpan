package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.mapper.toAnnouncementPageResult
import com.folderspan.pro.data.remote.api.MessageApiService
import com.folderspan.pro.domain.model.AnnouncementPage
import com.folderspan.pro.domain.model.AnnouncementQuery
import com.folderspan.pro.domain.repository.AnnouncementRepository

class DefaultAnnouncementRepository(
    private val api: MessageApiService,
) : AnnouncementRepository {
    override suspend fun list(query: AnnouncementQuery): ApiResult<AnnouncementPage> =
        when (val result = api.listMessages(query)) {
            is ApiResult.Success -> result.data.toAnnouncementPageResult()
            is ApiResult.Failure -> result
        }
}
