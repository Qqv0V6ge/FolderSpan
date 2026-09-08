package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.RouteBuilder
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.mapper.toDomainOrNull
import com.folderspan.pro.data.remote.dto.UserNotificationDto
import com.folderspan.pro.data.repository.DefaultUserNotificationRepository
import com.folderspan.pro.domain.model.UserNotificationQuery
import com.folderspan.pro.domain.model.UserNotificationStatus
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.HeadersBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UserNotificationApiServiceTest {
    @Test
    fun routeBuilderUsesConfiguredMessagePrefix() {
        val routes = RouteBuilder(
            GatewayConfig(
                baseUrl = "https://example.test/root/",
                messagePrefix = "/custom/messages/",
            ),
        )

        assertEquals(
            "https://example.test/root/custom/messages",
            routes.message(),
        )
        assertEquals(
            "https://example.test/root/custom/messages/user/notifications",
            routes.message("/user/notifications"),
        )
    }

    @Test
    fun listUsesExpectedRouteQueryAndAuthorization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = UserNotificationApiService(recordingClient(requests), testConfig())

        val result = service.list(
            UserNotificationQuery(
                status = UserNotificationStatus.Unread,
                page = 2,
                pageSize = 250,
            ),
            token = "access-token",
        )

        assertIs<ApiResult.Success<*>>(result, result.toString())
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v1/messages/user/notifications", request.url.encodedPath)
        assertEquals("unread", request.url.parameters["status"])
        assertEquals("2", request.url.parameters["page"])
        assertEquals("100", request.url.parameters["pageSize"])
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun listTreatsNotFoundAsAnEmptyCompatiblePage() = runTest {
        val client = jsonClient {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = UserNotificationApiService(client, testConfig()).list(
            UserNotificationQuery(page = 3, pageSize = 25),
            token = "access-token",
        )

        val envelope = assertIs<ApiResult.Success<*>>(result).data
            as com.folderspan.pro.data.remote.dto.UserNotificationEnvelopeDto<*>
        val data = envelope.data as com.folderspan.pro.data.remote.dto.UserNotificationListDataDto
        assertEquals(emptyList(), data.list)
        assertEquals(3, data.page)
        assertEquals(25, data.pageSize)
    }

    @Test
    fun readOperationsUsePatchAndChunkBatchRequestsAtOneHundred() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = UserNotificationApiService(recordingClient(requests), testConfig())

        service.markRead("notice-id", "token")
        DefaultUserNotificationRepository(service).batchMarkRead(
            ids = (1..205).map { "notice-$it" } + listOf("notice-1", " "),
            token = "token",
        )

        assertEquals(HttpMethod.Patch, requests.first().method)
        assertEquals("/api/v1/messages/user/notifications/notice-id/read", requests.first().url.encodedPath)
        assertEquals(4, requests.size)
        requests.drop(1).forEach { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/messages/user/notifications/batch-read", request.url.encodedPath)
        }
        assertEquals(100, bodyIdCount(requests[1]))
        assertEquals(100, bodyIdCount(requests[2]))
        assertEquals(5, bodyIdCount(requests[3]))
    }

    @Test
    fun unreadAndDeleteOperationsUseSwaggerMethodsAndRoutes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = UserNotificationApiService(recordingClient(requests), testConfig())

        service.markUnread("notice-unread", "unread-token")
        service.delete("notice-delete", "delete-token")

        assertEquals(HttpMethod.Patch, requests[0].method)
        assertEquals(
            "/api/v1/messages/user/notifications/notice-unread/unread",
            requests[0].url.encodedPath,
        )
        assertEquals("Bearer unread-token", requests[0].headers[HttpHeaders.Authorization])
        assertEquals(HttpMethod.Delete, requests[1].method)
        assertEquals(
            "/api/v1/messages/user/notifications/notice-delete",
            requests[1].url.encodedPath,
        )
        assertEquals("Bearer delete-token", requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun streamAcceptHeaderReplacesTheDefaultJsonAccept() {
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }

        headers.useEventStreamAccept()

        assertEquals(
            listOf(ContentType.Text.EventStream.toString()),
            headers.getAll(HttpHeaders.Accept),
        )
    }

    @Test
    fun streamUsesTheDedicatedStreamingClient() = runTest {
        val restClient = HttpClient(MockEngine {
            error("The REST client must not execute the SSE request")
        })
        val streamServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorizationHeader: String? = null
        var acceptHeader: String? = null
        streamServer.createContext("/api/v1/messages/user/notifications/stream") { exchange ->
            authorizationHeader = exchange.requestHeaders.getFirst(HttpHeaders.Authorization)
            acceptHeader = exchange.requestHeaders.getFirst(HttpHeaders.Accept)
            val body = "event: connected\ndata: {}\n\n".encodeToByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        streamServer.start()
        val streamClient = HttpClient { install(SSE) }
        try {
            val service = UserNotificationApiService(
                client = restClient,
                config = GatewayConfig(baseUrl = "http://127.0.0.1:${streamServer.address.port}"),
                streamClient = streamClient,
            )

            val result = service.stream(token = "stream-token") {}

            assertIs<ApiResult.Success<*>>(result, result.toString())
            assertEquals("Bearer stream-token", authorizationHeader)
            assertTrue(
                acceptHeader
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .all { it == ContentType.Text.EventStream.toString() },
                acceptHeader,
            )
        } finally {
            restClient.close()
            streamClient.close()
            streamServer.stop(0)
        }
    }

    @Test
    fun streamDecoderConsumesOnlyValidMessageEvents() {
        assertEquals(null, decodeUserNotificationSseEvent("connected", "{\"status\":\"ready\"}"))
        assertEquals(null, decodeUserNotificationSseEvent("message", "not-json"))

        val notification = decodeUserNotificationSseEvent(
            event = "message",
            data = """{"id":"notice-1","user_uuid":"user-1","title":"Server","content":"Ready","type":"system","status":"unread","created_at":1700000000000}""",
        )

        assertEquals("notice-1", notification?.id)
        assertEquals("user-1", notification?.userUuid)
    }

    @Test
    fun streamDecoderSupportsPresentAbsentAndStructurallyMalformedActions() {
        val withActions = decodeUserNotificationSseEvent(
            event = "message",
            data = """{"id":"notice-actions","actions":[{"label":"Open","style":"primary","kind":"route","route":"settings","params":{"source":"notification"}}]}""",
        )
        val withoutActions = decodeUserNotificationSseEvent(
            event = "message",
            data = """{"id":"notice-empty"}""",
        )
        val malformedAction = decodeUserNotificationSseEvent(
            event = "message",
            data = """{"id":"notice-malformed","actions":[{"label":"Broken"}]}""",
        )

        assertEquals(1, withActions?.actions?.size)
        assertEquals("settings", withActions?.actions?.single()?.route)
        assertEquals(mapOf("source" to "notification"), withActions?.actions?.single()?.params)
        assertEquals(emptyList(), withoutActions?.actions)
        assertEquals("", malformedAction?.actions?.single()?.kind)
    }

    @Test
    fun announcementContentSurvivesApiDecodeAndDomainMappingVerbatim() {
        val content = "  Intro \\[literal]\n" +
            "[Docs](https://example.test/a?x=1%202)\n" +
            "[Ticket](route:feedback_tickets?ticketUuid=ticket%2F7)  "
        val payload = defaultJson.encodeToString(
            UserNotificationDto(
                id = "announcement-copy",
                content = content,
            ),
        )

        val decoded = decodeUserNotificationSseEvent(event = "message", data = payload)

        assertEquals(content, decoded?.content)
        assertEquals(content, decoded?.toDomainOrNull()?.content)
    }

    private fun bodyIdCount(request: HttpRequestData): Int {
        val body = (request.body as TextContent).text
        assertContains(body, "\"ids\"")
        return Regex("notice-").findAll(body).count()
    }

    private fun recordingClient(requests: MutableList<HttpRequestData>): HttpClient = jsonClient { request ->
        requests += request
        respond(
            content = if (request.method == HttpMethod.Get) {
                """{"code":0,"data":{"list":[],"total":0,"page":2,"pageSize":100}}"""
            } else {
                """{"code":0,"data":true}"""
            },
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun jsonClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(defaultJson) }
            defaultRequest {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        }

    private fun testConfig() = GatewayConfig(baseUrl = "https://example.test")
}
