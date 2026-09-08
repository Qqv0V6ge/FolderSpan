package com.folderspan.pro.data.remote

import strings.AppStrings

import com.folderspan.pro.data.mapper.arrayValue
import com.folderspan.pro.data.mapper.intValue
import com.folderspan.pro.data.mapper.numberString
import com.folderspan.pro.data.mapper.stringValue
import com.folderspan.pro.domain.model.MarketplaceQuery
import com.folderspan.pro.domain.model.PluginCardUi
import com.folderspan.pro.domain.repository.PluginRepository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.apiDataOrSelf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class PluginPagingSource(
    private val repository: PluginRepository,
    private val query: MarketplaceQuery,
) : PagingSource<Int, PluginCardUi>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PluginCardUi> {
        val page = params.key ?: 1
        return when (val result = repository.list(query.forPage(page, params.loadSize))) {
            is ApiResult.Success -> {
                val payload = result.data.toPluginPagePayload()
                val hasMore = page * params.loadSize < payload.total
                LoadResult.Page(
                    data = payload.items,
                    prevKey = if (page == 1) null else page - 1,
                    nextKey = if (hasMore) page + 1 else null,
                )
            }

            is ApiResult.Failure -> LoadResult.Error(IllegalStateException(result.message, result.cause))
        }
    }

    override fun getRefreshKey(state: PagingState<Int, PluginCardUi>): Int? =
        state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.let { page ->
                page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
            }
        }
}

private data class PluginPagePayload(
    val items: List<PluginCardUi>,
    val total: Int,
)

private fun JsonElement.toPluginPagePayload(): PluginPagePayload {
    val data = apiDataOrSelf() as? JsonObject ?: return PluginPagePayload(emptyList(), 0)
    val records = data.arrayValue("records")
        ?: data.arrayValue("items")
        ?: data.arrayValue("list")
        ?: JsonArray(emptyList())
    val items = records.mapNotNull { it.toPluginCardUi() }
    val total = data.intValue("total") ?: items.size
    return PluginPagePayload(items = items, total = total)
}

private fun JsonElement.toPluginCardUi(): PluginCardUi? {
    val obj = this as? JsonObject ?: return null
    val id = obj.stringValue("pluginId")
        ?: obj.stringValue("id")
        ?: obj.stringValue("uuid")
        ?: return null
    return PluginCardUi(
        title = obj.stringValue("title") ?: obj.stringValue("name") ?: AppStrings.ui_unnamed_plugin,
        category = obj.stringValue("category") ?: AppStrings.ui_uncategorized,
        summary = obj.stringValue("summary") ?: obj.stringValue("description") ?: AppStrings.ui_no_profile_yet,
        rating = obj.stringValue("rating") ?: obj.numberString("score") ?: "-",
        ratingCount = obj.stringValue("ratingCount") ?: obj.numberString("rating_count") ?: "0",
        downloads = obj.stringValue("downloads") ?: obj.numberString("downloadCount") ?: "0",
        version = obj.stringValue("version") ?: obj.stringValue("latestVersion") ?: "-",
        pluginId = id,
        authorId = obj.stringValue("authorId") ?: obj.stringValue("author_id") ?: "-",
        status = obj.stringValue("status") ?: "-",
    )
}
