package com.folderspan.pro.data.mapper

import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebRtcRoomApiMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun writeRequestOmitsSignalingFields() {
        val request = WebRtcRoomInput(
            name = " Office ",
            wssUrl = "wss://example.test/ws",
            roomId = "local-id",
            stunUrl = " stun:example.test:3478 ",
            turnUrl = "turn:example.test:3478",
            turnUsername = "alice",
            turnPassword = "turn-secret",
            source = WebRtcRoomSource.Official,
        ).toWriteRequest()

        assertEquals("Office", request.name)
        assertEquals("stun:example.test:3478", request.stunUrl)
        assertEquals("turn:example.test:3478", request.turnUrl)
        assertEquals("alice", request.turnUsername)
        assertEquals("turn-secret", request.turnPassword)
    }

    @Test
    fun listPayloadMapsOfficialRoomsAndUnixSeconds() {
        val payload = json.parseToJsonElement(
            """
            {"code":0,"data":{"rooms":[{
              "roomId":"room-001",
              "name":"Office",
              "stunUrl":"stun:example.test:3478",
              "turnUrl":"",
              "turnUsername":"",
              "turnPassword":"turn-secret",
              "createdAt":1764144000,
              "updatedAt":1764147600,
              "maxDevices":1
            }],"total":3}}
            """.trimIndent(),
        )

        val page = payload.toOfficialWebRtcRoomPage(page = 2, pageSize = 20)
        val room = page.rooms.single()

        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(20, page.pageSize)
        assertEquals("room-001", room.roomId)
        assertEquals("Office", room.name)
        assertEquals(WebRtcRoomSource.Official, room.source)
        assertEquals("", room.turnPassword)
        assertEquals("", room.wssUrl)
        assertEquals(false, room.pinned)
        assertEquals(1, room.maxDevices)
        assertEquals(1764144000L * 1000L, room.createdAt)
        assertEquals(1764147600L * 1000L, room.updatedAt)
    }

    @Test
    fun detailPayloadKeepsTurnPasswordAndMaxDevices() {
        val payload = json.parseToJsonElement(
            """
            {"code":0,"data":{
              "roomId":"room-001",
              "name":"Office",
              "stunUrl":"stun:example.test:3478",
              "turnUrl":"",
              "turnUsername":"",
              "turnPassword":"turn-secret",
              "createdAt":1764144000,
              "updatedAt":1764147600,
              "maxDevices":2
            }}
            """.trimIndent(),
        )

        val room = payload.toOfficialWebRtcRoomProfile()

        assertEquals("turn-secret", room?.turnPassword)
        assertEquals(2, room?.maxDevices)
    }

    @Test
    fun profileMappingRequiresRoomIdAndName() {
        val missingRoomId = json.parseToJsonElement("""{"name":"Office","roomId":""}""")
        val missingName = json.parseToJsonElement("""{"name":"","roomId":"room-001"}""")
        val valid = json.parseToJsonElement("""{"name":"Office","roomId":"room-001"}""")

        assertNull(missingRoomId.toOfficialWebRtcRoomProfile())
        assertNull(missingName.toOfficialWebRtcRoomProfile())
        assertEquals("room-001", valid.toOfficialWebRtcRoomProfile()?.roomId)
    }
}
