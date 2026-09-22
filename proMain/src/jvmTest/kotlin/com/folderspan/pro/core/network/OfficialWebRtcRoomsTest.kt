package com.folderspan.pro.core.network

import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.pro.data.remote.api.WebRtcRoomsApiService
import com.folderspan.pro.test.ChineseLocalizationTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialWebRtcRoomsTest : ChineseLocalizationTest() {
    @Test
    fun listRoomsMapsNotFoundToEmptyPageWithoutCachingTurnPassword() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = ProWebRtcOfficialRoomsClient(
            api = WebRtcRoomsApiService(
                client = clientResponding(
                    requests = requests,
                    body = """{"code":40400,"messageKey":"rooms.empty","msg":"empty"}""",
                    status = HttpStatusCode.NotFound,
                ),
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
            tokenProvider = { "access-token" },
        )

        val page = client.listRooms(page = 1, pageSize = 20).getOrThrow()

        assertEquals(emptyList(), page.rooms)
        assertEquals(0, page.total)
        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertEquals("/api/v1/webrtc/rooms", requests.single().url.encodedPath)
        assertEquals("Bearer access-token", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun listRoomsMapsPayloadWithoutCachingTurnPassword() = runTest {
        val client = ProWebRtcOfficialRoomsClient(
            api = WebRtcRoomsApiService(
                client = clientResponding(
                    requests = mutableListOf(),
                    body = """
                        {"code":0,"data":{"rooms":[{
                          "roomId":"room-001",
                          "name":"Office",
                          "stunUrl":"stun:example.test:3478",
                          "turnUrl":"turn:example.test:3478",
                          "turnUsername":"alice",
                          "turnPassword":"turn-secret",
                          "createdAt":1764144000,
                          "updatedAt":1764147600,
                          "maxDevices":1
                        }],"total":1}}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                ),
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
            tokenProvider = { "access-token" },
        )

        val page = client.listRooms(page = 1, pageSize = 20).getOrThrow()
        val room = page.rooms.single()

        assertEquals(1, page.total)
        assertEquals("room-001", room.roomId)
        assertEquals("Office", room.name)
        assertEquals(WebRtcRoomSource.Official, room.source)
        assertEquals("", room.turnPassword)
        assertEquals("", room.wssUrl)
        assertEquals(false, room.pinned)
        assertEquals(1, room.maxDevices)
        assertEquals(1764144000L * 1000L, room.createdAt)
    }

    @Test
    fun getRoomUsesDetailRouteAndKeepsTurnPassword() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = ProWebRtcOfficialRoomsClient(
            api = WebRtcRoomsApiService(
                client = clientResponding(
                    requests = requests,
                    body = """
                        {"code":0,"data":{
                          "roomId":"room-001",
                          "name":"Office",
                          "stunUrl":"stun:example.test:3478",
                          "turnUrl":"turn:example.test:3478",
                          "turnUsername":"alice",
                          "turnPassword":"turn-secret",
                          "createdAt":1,
                          "updatedAt":1,
                          "maxDevices":3
                        }}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                ),
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
            tokenProvider = { "access-token" },
        )

        val room = client.getRoom("room-001").getOrThrow()

        assertEquals("/api/v1/webrtc/rooms/room-001", requests.single().url.encodedPath)
        assertEquals("turn-secret", room.turnPassword)
        assertEquals(3, room.maxDevices)
    }

    @Test
    fun missingLoginTokenDoesNotCallApi() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = ProWebRtcOfficialRoomsClient(
            api = WebRtcRoomsApiService(
                client = clientResponding(
                    requests = requests,
                    body = """{"code":0,"data":{"rooms":[],"total":0}}""",
                    status = HttpStatusCode.OK,
                ),
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
            tokenProvider = { " " },
        )

        val result = client.listRooms(page = 1, pageSize = 20)

        assertTrue(result.isFailure)
        assertEquals(AppStrings.ui_log_in_before_connecting_official_webrtc_room, result.exceptionOrNull()?.message)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun createRoomUsesWriteRequestWithoutSignalingUrl() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = ProWebRtcOfficialRoomsClient(
            api = WebRtcRoomsApiService(
                client = clientResponding(
                    requests = requests,
                    body = """
                        {"code":0,"data":{
                          "roomId":"created-room",
                          "name":"Office",
                          "stunUrl":"",
                          "turnUrl":"",
                          "turnUsername":"",
                          "turnPassword":"",
                          "createdAt":1,
                          "updatedAt":1,
                          "maxDevices":1
                        }}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                ),
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
            tokenProvider = { "access-token" },
        )

        val profile = client.createRoom(
            WebRtcRoomInput(
                name = "Office",
                wssUrl = "wss://should-not-be-sent",
                roomId = "local-id",
                stunUrl = "",
                turnUrl = "",
                turnUsername = "",
                turnPassword = "",
                source = WebRtcRoomSource.Official,
            )
        ).getOrThrow()

        assertEquals("created-room", profile.roomId)
        assertEquals("Office", profile.name)
        assertEquals(WebRtcRoomSource.Official, profile.source)
        assertEquals(1, requests.size)
    }
}

private fun clientResponding(
    requests: MutableList<HttpRequestData>,
    body: String,
    status: HttpStatusCode,
): HttpClient =
    HttpClient(
        MockEngine { request ->
            requests += request
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) { json(defaultJson) }
        defaultRequest { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
    }
