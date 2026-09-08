package com.folderspan.pro.domain.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.domain.model.AnnouncementPage
import com.folderspan.pro.domain.model.AnnouncementQuery

interface AnnouncementRepository {
    suspend fun list(query: AnnouncementQuery): ApiResult<AnnouncementPage>
}
