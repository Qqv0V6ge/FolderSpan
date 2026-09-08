package com.folderspan.pro.data.repository

import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.data.remote.api.SettingApiService
import com.folderspan.pro.domain.model.DeviceSettingEntry
import com.folderspan.pro.domain.model.RemoteSettingEntryKey
import com.folderspan.pro.domain.model.RemoteSettingSave
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingRepositoryEntriesTest {
    @Test
    fun genericSaveSettingPostsCallerProvidedTypeAndTarget() = runTest {
        var requestBody = ""
        val client = recordingClient { request ->
            requestBody = request.body.bodyText()
            respond(
                content = """{"code":0,"data":{"updatedAt":9}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.saveSetting(
            type = "network",
            targetId = "device-1",
            entry = DeviceSettingEntry("app.networks.snapshot", JsonPrimitive("payload")),
            token = "token-value",
        )

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("network", body["type"]?.toString()?.trim('"'))
        assertEquals("device-1", body["targetId"]?.toString()?.trim('"'))
        assertEquals("app.networks.snapshot", body["key"]?.toString()?.trim('"'))
        assertEquals(JsonPrimitive("payload"), body["value"])
    }

    @Test
    fun genericListSettingsUsesCallerProvidedTypeAndTimestamp() = runTest {
        var requestUrl: Url? = null
        val client = recordingClient { request ->
            requestUrl = request.url
            respond(
                content = """{"code":0,"data":[{"key":"app.syncTasks.snapshot","value":{"version":1},"updated_at":77}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        val result = repository.listSettings(
            type = "sync",
            targetId = "device-1",
            timestamp = 44,
            token = "token-value",
        )

        assertEquals("https://example.test/api/v1/settings/entries?type=sync&targetId=device-1&timestamp=44", requestUrl.toString())
        val entries = (result as com.folderspan.pro.core.common.ApiResult.Success).data
        assertEquals("app.syncTasks.snapshot", entries.single().key)
        assertEquals(77L, entries.single().updatedAt)
    }

    @Test
    fun genericBatchOperationsPostAllCallerProvidedItems() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val bodies = mutableListOf<String>()
        val client = recordingClient { request ->
            methods.add(request.method)
            bodies.add(request.body.bodyText())
            respond(
                content = """{"code":0,"data":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.batchSaveSettings(
            items = listOf(
                RemoteSettingSave("role", "device-1", "app.roles.snapshot", JsonPrimitive("roles")),
                RemoteSettingSave("sync", "device-1", "app.syncTasks.snapshot", JsonPrimitive("sync")),
            ),
            token = "token-value",
        )
        repository.batchDeleteSettings(
            items = listOf(RemoteSettingEntryKey("network", "device-1", "app.networks.snapshot")),
            token = "token-value",
        )

        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), methods)
        val saveBody = Json.parseToJsonElement(bodies.first()).jsonObject
        assertEquals(2, saveBody["items"]!!.jsonArray.size)
        assertEquals("role", saveBody["items"]!!.jsonArray.first().jsonObject["type"]?.toString()?.trim('"'))
        val deleteBody = Json.parseToJsonElement(bodies.last()).jsonObject
        assertEquals("network", deleteBody["items"]!!.jsonArray.single().jsonObject["type"]?.toString()?.trim('"'))
    }

    @Test
    fun saveDeviceSettingPostsSingleEntryForDeviceTarget() = runTest {
        var requestUrl: Url? = null
        var requestMethod: HttpMethod? = null
        var authorization: String? = null
        var requestBody = ""
        val client = recordingClient { request ->
            requestUrl = request.url
            requestMethod = request.method
            authorization = request.headers[HttpHeaders.Authorization]
            requestBody = request.body.bodyText()
            respond(
                content = """{"code":0,"data":{}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.saveDeviceSetting(
            targetId = "device-1",
            entry = DeviceSettingEntry(
                key = "fileShare.port",
                value = JsonPrimitive(12040),
            ),
            token = "token-value",
        )

        assertEquals(HttpMethod.Post, requestMethod)
        assertEquals("https://example.test/api/v1/settings/entries", requestUrl.toString())
        assertEquals("Bearer token-value", authorization)
        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("device", body["type"]?.toString()?.trim('"'))
        assertEquals("device-1", body["targetId"]?.toString()?.trim('"'))
        assertEquals("fileShare.port", body["key"]?.toString()?.trim('"'))
        assertEquals(JsonPrimitive(12040), body["value"])
    }

    @Test
    fun listDeviceSettingsGetsEntriesForDeviceTarget() = runTest {
        var requestUrl: Url? = null
        var requestMethod: HttpMethod? = null
        val client = recordingClient { request ->
            requestUrl = request.url
            requestMethod = request.method
            respond(
                content = """{"code":0,"data":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.listDeviceSettings(
            targetId = "device-1",
            timestamp = 1,
            token = "token-value",
        )

        assertEquals(HttpMethod.Get, requestMethod)
        assertEquals("https://example.test/api/v1/settings/entries?type=device&targetId=device-1&timestamp=1", requestUrl.toString())
    }

    @Test
    fun deleteTargetSettingsDeletesDeviceTargetWithQueryParameters() = runTest {
        var requestUrl: Url? = null
        var requestMethod: HttpMethod? = null
        val client = recordingClient { request ->
            requestUrl = request.url
            requestMethod = request.method
            respond(
                content = """{"code":0,"data":{}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.deleteTargetSettings(
            type = "device",
            targetId = "device-1",
            token = "token-value",
        )

        assertEquals(HttpMethod.Delete, requestMethod)
        assertEquals("https://example.test/api/v1/settings/targets?type=device&targetId=device-1", requestUrl.toString())
    }

    @Test
    fun listDeviceSettingsParsesEntryUpdatedAt() = runTest {
        val client = recordingClient {
            respond(
                content = """{"code":0,"data":[{"key":"fileShare.port","value":12040,"updatedAt":42}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = DefaultSettingRepository(
            SettingApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        val result = repository.listDeviceSettings(
            targetId = "device-1",
            timestamp = 1,
            token = "token-value",
        )

        val entries = (result as com.folderspan.pro.core.common.ApiResult.Success).data
        assertEquals(42L, entries.single().updatedAt)
    }
}

private fun recordingClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(
        MockEngine { request ->
            handler(request)
        },
    ) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

private fun OutgoingContent.bodyText(): String =
    when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }
