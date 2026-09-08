package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.data.remote.api.PluginApiService
import com.folderspan.pro.data.remote.dto.PluginListQuery
import com.folderspan.pro.domain.model.MarketplaceQuery
import com.folderspan.pro.domain.repository.PluginRepository

class DefaultPluginRepository(
    private val pluginApiService: PluginApiService,
) : PluginRepository {
    override suspend fun list(query: MarketplaceQuery): JsonResult =
        pluginApiService.list(
            PluginListQuery(
                page = query.page,
                pageSize = query.pageSize,
                keyword = query.keyword,
                isFree = query.isFree,
                securityChecked = query.securityChecked,
                authorId = query.authorId,
                category = query.category,
                tag = query.tag,
                minPrice = query.minPrice,
                maxPrice = query.maxPrice,
                status = query.status,
                license = query.license,
                orderBy = query.orderBy,
                sort = query.sort,
            ),
        )
}
