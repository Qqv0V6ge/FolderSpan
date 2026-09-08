package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.cache.ApiCacheKey
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.data.remote.dto.PluginListQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class PluginApiServiceCacheTest {
    @Test
    fun networkSuccessReturnsNetworkPayloadAndRefreshesCache() = runTest {
        val cacheStore = InMemoryApiResponseCacheStore()
        val service = PluginApiService(
            client = clientResponding("""{"code":0,"data":{"source":"network"}}"""),
            config = GatewayConfig(baseUrl = "https://example.test"),
            cacheStore = cacheStore,
        )

        val result = service.list(
            query = PluginListQuery(page = 1, keyword = "zip"),
            cachePolicy = ApiCachePolicy(ttl = 5.minutes),
        )

        val payload = assertIs<ApiResult.Success<JsonElement>>(result).data
        assertEquals("network", payload.jsonObject["data"]?.jsonObject?.get("source")?.jsonPrimitive?.content)
        assertEquals(payload, cacheStore.singlePayload())
    }

    @Test
    fun networkFailureReturnsFreshCachedPayload() = runTest {
        val cacheStore = InMemoryApiResponseCacheStore()
        val service = PluginApiService(
            client = clientFailing(),
            config = GatewayConfig(baseUrl = "https://example.test"),
            cacheStore = cacheStore,
        )
        val key = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/v1/plugins/simple",
            queryParameters = listOf("page" to "1", "pageSize" to "10", "keyword" to "zip"),
        )
        cacheStore.writeJson(key, """{"code":0,"data":{"source":"cache"}}""")

        val result = service.list(
            query = PluginListQuery(page = 1, keyword = "zip"),
            cachePolicy = ApiCachePolicy(ttl = 5.minutes),
        )

        val payload = assertIs<ApiResult.Success<JsonElement>>(result).data
        assertEquals("cache", payload.jsonObject["data"]?.jsonObject?.get("source")?.jsonPrimitive?.content)
    }

    @Test
    fun unauthorizedResponseDoesNotUseCachedPayload() = runTest {
        val cacheStore = InMemoryApiResponseCacheStore()
        val service = PluginApiService(
            client = clientResponding("""{"message":"unauthorized"}""", HttpStatusCode.Unauthorized),
            config = GatewayConfig(baseUrl = "https://example.test"),
            cacheStore = cacheStore,
        )
        val key = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/v1/plugins/simple",
            queryParameters = listOf("page" to "1", "pageSize" to "10", "keyword" to "zip"),
        )
        cacheStore.writeJson(key, """{"code":0,"data":{"source":"cache"}}""")

        val result = service.list(
            query = PluginListQuery(page = 1, keyword = "zip"),
            cachePolicy = ApiCachePolicy(ttl = 5.minutes),
        )

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun businessErrorDoesNotUseCachedPayload() = runTest {
        val cacheStore = InMemoryApiResponseCacheStore()
        val service = PluginApiService(
            client = clientResponding("""{"code":1001,"message":"denied"}"""),
            config = GatewayConfig(baseUrl = "https://example.test"),
            cacheStore = cacheStore,
        )
        val key = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/v1/plugins/simple",
            queryParameters = listOf("page" to "1", "pageSize" to "10", "keyword" to "zip"),
        )
        cacheStore.writeJson(key, """{"code":0,"data":{"source":"cache"}}""")

        val result = service.list(
            query = PluginListQuery(page = 1, keyword = "zip"),
            cachePolicy = ApiCachePolicy(ttl = 5.minutes),
        )

        assertIs<ApiResult.Failure>(result)
    }

    private fun clientResponding(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
        }

    private fun clientFailing(): HttpClient =
        HttpClient(
            MockEngine {
                error("network down")
            },
        ) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
        }

    private class InMemoryApiResponseCacheStore : ApiResponseCacheStore {
        private val values = mutableMapOf<String, JsonElement>()

        override suspend fun read(key: ApiCacheKey, namespace: String): JsonElement? =
            values[key.storageKey]

        override suspend fun write(
            key: ApiCacheKey,
            payload: JsonElement,
            policy: ApiCachePolicy,
        ) {
            values[key.storageKey] = payload
        }

        override suspend fun remove(key: ApiCacheKey, namespace: String) {
            values.remove(key.storageKey)
        }

        fun singlePayload(): JsonElement = values.values.single()

        fun writeJson(key: ApiCacheKey, json: String) {
            values[key.storageKey] = com.folderspan.pro.core.network.defaultJson.parseToJsonElement(json)
        }
    }
}
