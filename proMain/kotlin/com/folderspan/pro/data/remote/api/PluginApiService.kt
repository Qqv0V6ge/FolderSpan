package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import com.folderspan.pro.data.remote.dto.PluginListQuery
import com.folderspan.pro.data.remote.dto.PluginVersionQuery
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * plugin-api wrapper for requests defined under http/plugin-api.
 */
class PluginApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    cacheStore: ApiResponseCacheStore = FileApiResponseCacheStore(),
) : BaseApiService(client, config, cacheStore) {

    suspend fun ping(cachePolicy: ApiCachePolicy? = null): JsonResult {
        val url = routes.plugin("/ping")
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let { cacheKey(method = "GET", url = url) },
        ) {
            client.get(url)
        }
    }

    suspend fun list(
        query: PluginListQuery = PluginListQuery(),
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.pluginSimple()
        val queryParameters = query.toCachePairs()
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    queryParameters = queryParameters,
                )
            },
        ) {
            client.get(url) {
                applyListQuery(query)
            }
        }
    }

    suspend fun detail(
        pluginId: String,
        authToken: String? = null,
        unlockToken: String? = null,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.plugin("/$pluginId")
        val queryParameters = listOfNotNull(unlockToken?.let { "token" to it })
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    queryParameters = queryParameters,
                    userScope = authToken,
                )
            },
        ) {
            client.get(url) {
                authToken?.let { auth(it) }
                unlockToken?.let { parameter("token", it) }
            }
        }
    }

    suspend fun versions(
        pluginId: String,
        query: PluginVersionQuery = PluginVersionQuery(),
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.plugin("/$pluginId/versions")
        val queryParameters = query.toCachePairs()
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    queryParameters = queryParameters,
                )
            },
        ) {
            client.get(url) {
                applyVersionQuery(query)
            }
        }
    }

    private fun HttpRequestBuilder.applyListQuery(query: PluginListQuery) {
        query.page?.let { parameter("page", it) }
        query.pageSize?.let { parameter("pageSize", it) }
        query.keyword?.let { parameter("keyword", it) }
        query.isFree?.let { parameter("isFree", it) }
        query.securityChecked?.let { parameter("securityChecked", it) }
        query.authorId?.let { parameter("authorId", it) }
        query.category?.takeIf { it.isNotEmpty() }?.let { parameter("category", it.joinToString(",")) }
        query.tag?.takeIf { it.isNotEmpty() }?.let { parameter("tag", it.joinToString(",")) }
        query.minPrice?.let { parameter("minPrice", it) }
        query.maxPrice?.let { parameter("maxPrice", it) }
        query.status?.let { parameter("status", it) }
        query.license?.let { parameter("license", it) }
        query.orderBy?.let { parameter("orderBy", it) }
        query.sort?.let { parameter("sort", it) }
    }

    private fun HttpRequestBuilder.applyVersionQuery(query: PluginVersionQuery) {
        query.page?.let { parameter("page", it) }
        query.pageSize?.let { parameter("pageSize", it) }
        query.status?.let { parameter("status", it) }
        query.platform?.let { parameter("platform", it) }
        query.keyword?.let { parameter("keyword", it) }
    }

    private fun PluginListQuery.toCachePairs(): List<Pair<String, String>> = buildList {
        page?.let { add("page" to it.toString()) }
        pageSize?.let { add("pageSize" to it.toString()) }
        keyword?.let { add("keyword" to it) }
        isFree?.let { add("isFree" to it.toString()) }
        securityChecked?.let { add("securityChecked" to it.toString()) }
        authorId?.let { add("authorId" to it) }
        category?.takeIf { it.isNotEmpty() }?.let { add("category" to it.joinToString(",")) }
        tag?.takeIf { it.isNotEmpty() }?.let { add("tag" to it.joinToString(",")) }
        minPrice?.let { add("minPrice" to it.toString()) }
        maxPrice?.let { add("maxPrice" to it.toString()) }
        status?.let { add("status" to it) }
        license?.let { add("license" to it) }
        orderBy?.let { add("orderBy" to it) }
        sort?.let { add("sort" to it) }
    }

    private fun PluginVersionQuery.toCachePairs(): List<Pair<String, String>> = buildList {
        page?.let { add("page" to it.toString()) }
        pageSize?.let { add("pageSize" to it.toString()) }
        status?.let { add("status" to it) }
        platform?.let { add("platform" to it) }
        keyword?.let { add("keyword" to it) }
    }

}
