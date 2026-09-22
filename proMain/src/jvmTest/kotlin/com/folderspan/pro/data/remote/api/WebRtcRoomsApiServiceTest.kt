package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.RouteBuilder
import com.folderspan.pro.core.network.cache.ApiCacheKey
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.remote.dto.WebRtcRoomWriteRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebRtcRoomsApiServiceTest {
    @Test
    fun routeBuilderBuildsWebrtcRoomRoutesFromConfig() {
        val routes = RouteBuilder(
            GatewayConfig(
                baseUrl = "https://example.test/root/",
                webrtcPrefix = "/custom/webrtc/",
            ),
        )

        assertEquals("https://example.test/root/custom/webrtc/rooms", routes.webrtc("/rooms"))
        assertEquals("https://example.test/root/custom/webrtc/rooms/room-1", routes.webrtc("/rooms/room-1"))
    }

    @Test
    fun jsonOperationsUseExpectedMethodsRoutesQueriesBodiesAndAuth() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = WebRtcRoomsApiService(recordingJsonClient(requests), testConfig())
        val writeRequest = WebRtcRoomWriteRequest(
            name = "Office",
            stunUrl = "stun:example.test:3478",
            turnUrl = "turn:example.test:3478",
            turnUsername = "alice",
            turnPassword = "turn-secret",
        )

        service.listRooms(page = 2, pageSize = 250, token = "list-token")
        service.getRoom("room-1", token = "detail-token")
        service.createRoom(writeRequest, token = "create-token")
        service.updateRoom("room-1", writeRequest, token = "update-token")
        service.deleteRoom("room-1", token = "delete-token")

        val list = requests[0]
        assertRequest(list, HttpMethod.Get, "/api/v1/webrtc/rooms", "list-token")
        assertEquals("2", list.url.parameters["page"])
        assertEquals("100", list.url.parameters["pageSize"])

        assertRequest(requests[1], HttpMethod.Get, "/api/v1/webrtc/rooms/room-1", "detail-token")

        val create = requests[2]
        assertRequest(create, HttpMethod.Post, "/api/v1/webrtc/rooms", "create-token")
        assertContains(create.body.textContent(), "\"name\":\"Office\"")
        assertContains(create.body.textContent(), "\"turnPassword\":\"turn-secret\"")
        assertFalse(create.body.textContent().contains("wssUrl"))
        assertFalse(create.body.textContent().contains("roomId"))

        assertRequest(requests[3], HttpMethod.Patch, "/api/v1/webrtc/rooms/room-1", "update-token")
        assertRequest(requests[4], HttpMethod.Delete, "/api/v1/webrtc/rooms/room-1", "delete-token")
    }

    @Test
    fun listRoomsDoesNotWriteApiCache() = runTest {
        val cacheStore = InMemoryApiResponseCacheStore()
        val service = WebRtcRoomsApiService(
            client = recordingJsonClient(mutableListOf()),
            config = testConfig(),
            cacheStore = cacheStore,
        )

        val result = service.listRooms(page = 1, pageSize = 20, token = "token")

        assertIs<ApiResult.Success<*>>(result)
        assertTrue(cacheStore.isEmpty())
    }

    @Test
    fun listRoomsMapsHttpNotFoundToFailureStatus() = runTest {
        val service = WebRtcRoomsApiService(
            client = clientResponding(
                body = """{"code":40400,"msg":"empty"}""",
                status = HttpStatusCode.NotFound,
            ),
            config = testConfig(),
        )

        val result = service.listRooms(page = 1, pageSize = 20, token = "token")

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals(HttpStatusCode.NotFound.value, failure.statusCode)
    }

    private fun testConfig() = GatewayConfig(baseUrl = "https://example.test")

    private fun recordingJsonClient(requests: MutableList<HttpRequestData>): HttpClient =
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(
                    content = """{"code":0,"data":{"rooms":[],"total":0}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(defaultJson) }
            defaultRequest { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
        }

    private fun clientResponding(
        body: String,
        status: HttpStatusCode,
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
            install(ContentNegotiation) { json(defaultJson) }
        }

    private fun assertRequest(
        request: HttpRequestData,
        method: HttpMethod,
        path: String,
        token: String,
    ) {
        assertEquals(method, request.method, "Unexpected request: ${request.method.value} ${request.url}")
        assertEquals(path, request.url.encodedPath)
        assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
    }

    private class InMemoryApiResponseCacheStore : ApiResponseCacheStore {
        private val values = mutableMapOf<String, JsonElement>()

        override suspend fun read(key: ApiCacheKey, namespace: String): JsonElement? = values[key.storageKey]

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

        fun isEmpty(): Boolean = values.isEmpty()
    }

    private fun OutgoingContent.textContent(): String = when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }
}
