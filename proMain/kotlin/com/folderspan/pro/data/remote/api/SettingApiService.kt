package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.*
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import com.folderspan.pro.data.remote.dto.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * settings-api wrapper for requests defined under http/settings-api.
 */
class SettingApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    cacheStore: ApiResponseCacheStore = FileApiResponseCacheStore(),
    private val headerOverrideProvider: () -> ProApiHeaderOverride? = { null },
) : BaseApiService(client, config, cacheStore) {

    suspend fun ping(
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/ping")
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                settingsAuth(token)
            }
        }
    }

    suspend fun listSettings(
        query: SettingListQuery,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/entries")
        val queryParameters = query.toCachePairs()
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    queryParameters = queryParameters,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                settingsAuth(token)
                parameter("type", query.type)
                parameter("targetId", query.targetId)
                parameter("timestamp", query.timestamp)
            }
        }
    }

    suspend fun saveSetting(
        request: SettingSaveRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/entries")
        val body = cacheBody(SettingSaveRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "POST",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.post(url) {
                settingsAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun batchSaveSettings(
        request: SettingBatchSaveRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/entries/batch")
        val body = cacheBody(SettingBatchSaveRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "POST",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.post(url) {
                settingsAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun batchDeleteSettings(
        request: SettingBatchDeleteRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/entries/batch")
        val body = cacheBody(SettingBatchDeleteRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "DELETE",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.delete(url) {
                settingsAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun listTargets(
        query: SettingTargetQuery,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/targets")
        val queryParameters = query.toCachePairs()
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    queryParameters = queryParameters,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                settingsAuth(token)
                parameter("type", query.type)
                query.keyword?.let { parameter("keyword", it) }
                query.page?.let { parameter("page", it) }
                query.pageSize?.let { parameter("pageSize", it) }
            }
        }
    }

    suspend fun deleteTargetSettings(
        request: SettingDeleteTargetRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/targets")
        val queryParameters = listOf(
            "type" to request.type,
            "targetId" to request.targetId,
        )
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "DELETE",
                    url = url,
                    queryParameters = queryParameters,
                    userScope = token,
                )
            },
        ) {
            client.delete(url) {
                settingsAuth(token)
                parameter("type", request.type)
                parameter("targetId", request.targetId)
            }
        }
    }

    suspend fun cloneTargetSettings(
        request: SettingCloneTargetRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.settings("/targets/clone")
        val body = cacheBody(SettingCloneTargetRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "POST",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.post(url) {
                settingsAuth(token, useHeaderOverride = false)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    private fun HttpRequestBuilder.settingsAuth(token: String, useHeaderOverride: Boolean = true) {
        auth(token)
        if (useHeaderOverride) {
            applyHeaderOverride()
        }
    }

    private fun HttpRequestBuilder.applyHeaderOverride() {
        fun replaceHeader(name: String, value: String) {
            headers.remove(name)
            header(name, value)
        }

        headerOverrideProvider()?.normalized()?.let { override ->
            override.host?.let { replaceHeader(HttpHeaders.Host, it) }
            override.deviceType?.let { replaceHeader(PRO_API_DEVICE_TYPE_HEADER, it) }
            override.deviceKey?.let { replaceHeader(PRO_API_DEVICE_KEY_HEADER, it) }
            override.deviceName?.let { replaceHeader(PRO_API_DEVICE_NAME_HEADER, it) }
            override.appKey?.let { replaceHeader(PRO_API_APP_KEY_HEADER, it) }
        }
    }

    private fun SettingListQuery.toCachePairs(): List<Pair<String, String>> =
        listOf(
            "type" to type,
            "targetId" to targetId,
            "timestamp" to timestamp.toString(),
        )

    private fun SettingTargetQuery.toCachePairs(): List<Pair<String, String>> = buildList {
        add("type" to type)
        keyword?.let { add("keyword" to it) }
        page?.let { add("page" to it.toString()) }
        pageSize?.let { add("pageSize" to it.toString()) }
    }

    private fun ApiCachePolicy.withoutFallback(): ApiCachePolicy =
        if (allowFallback) copy(allowFallback = false) else this
}
