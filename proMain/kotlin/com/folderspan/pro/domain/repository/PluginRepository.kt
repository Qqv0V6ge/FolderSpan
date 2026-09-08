package com.folderspan.pro.domain.repository

import com.folderspan.pro.domain.model.MarketplaceQuery

import com.folderspan.pro.core.common.ApiResult
import kotlinx.serialization.json.JsonElement

interface PluginRepository {
    suspend fun list(query: MarketplaceQuery): ApiResult<JsonElement>
}
