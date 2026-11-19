package com.folderspan.data.main.webrtc

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WebRtcShareCodeTest {
    @Test
    fun officialRoomMatchesWireSampleWithoutNormalizingTurnCredentials() {
        val room = WebRtcShareCode.decodeOfficialRoom(ROOM_SAMPLE, GATEWAY)

        assertEquals("official", room.source)
        assertEquals("工作室的房间 🧭", room.name)
        assertEquals("A".repeat(43), room.roomId)
        assertEquals("stun:[2001:db8::2]:3478", room.stunUrl)
        assertEquals(
            listOf("turn:192.0.2.4:3478?transport=udp", "turns:[2001:db8::3]:5349?transport=tcp"),
            room.turnUrls,
        )
        assertEquals("turn-user", room.turnUsername)
        assertEquals(" secret\t ", room.turnPassword)
        assertEquals(ROOM_SAMPLE, WebRtcShareCode.encodeOfficialRoom(room))
        assertFalse(room.toString().contains(room.turnPassword))
    }

    @Test
    fun roomCodesRejectOtherVersionsAndPurposes() {
        val wrongPurpose = rewrite(ROOM_SAMPLE, "official-room", "direct-invite")
        val wrongVersion = rewrite(ROOM_SAMPLE, "\"version\":1", "\"version\":2")
        assertEquals(
            WebRtcShareCodeError.WrongKind,
            assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.decodeOfficialRoom(wrongPurpose, GATEWAY) }.reason,
        )
        assertEquals(
            WebRtcShareCodeError.UnsupportedVersion,
            assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.decodeOfficialRoom(wrongVersion, GATEWAY) }.reason,
        )
    }

    @Test
    fun roomCodeCannotSelectAnUntrustedGatewayOrIncludeAccountCredentials() {
        val replaced = rewrite(ROOM_SAMPLE, "gateway.example.test", "other.example.test")
        assertEquals(
            WebRtcShareCodeError.UntrustedGateway,
            assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.decodeOfficialRoom(replaced, GATEWAY) }.reason,
        )
        for (code in listOf(
            rewrite(ROOM_SAMPLE, GATEWAY, "wss://username@gateway.example.test/api/v1/webrtc/ws"),
            rewrite(ROOM_SAMPLE, GATEWAY, "$GATEWAY?token=do-not-log"),
            rewrite(ROOM_SAMPLE, GATEWAY, "ws://gateway.example.test/api/v1/webrtc/ws"),
            rewrite(ROOM_SAMPLE, "\"wssUrl\":", "\"token\":\"do-not-log\",\"wssUrl\":"),
            rewrite(ROOM_SAMPLE, "turns:[2001:db8::3]:5349?transport=tcp", "https://relay.example.test/"),
        )) {
            val error = assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.decodeOfficialRoom(code, GATEWAY) }
            assertEquals(WebRtcShareCodeError.Invalid, error.reason)
            assertFalse(error.stackTraceToString().contains("do-not-log"))
        }
    }

    private fun rewrite(code: String, before: String, after: String): String =
        base64.encode(base64.decode(code).decodeToString().replace(before, after).encodeToByteArray())

    companion object {
        private const val GATEWAY = "wss://gateway.example.test/api/v1/webrtc/ws"
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        private const val ROOM_SAMPLE =
            "eyJ2ZXJzaW9uIjoxLCJraW5kIjoib2ZmaWNpYWwtcm9vbSIsInNvdXJjZSI6Im9mZmljaWFsIiwibmFtZSI6IuW3peS9nOWupOea" +
            "hOaIv-mXtCDwn6etIiwicm9vbUlkIjoiQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQSIsIndzc1Vy" +
            "bCI6IndzczovL2dhdGV3YXkuZXhhbXBsZS50ZXN0L2FwaS92MS93ZWJydGMvd3MiLCJzdHVuVXJsIjoic3R1bjpbMjAwMTpkYjg6" +
            "OjJdOjM0NzgiLCJ0dXJuVXJscyI6WyJ0dXJuOjE5Mi4wLjIuNDozNDc4P3RyYW5zcG9ydD11ZHAiLCJ0dXJuczpbMjAwMTpkYjg6" +
            "OjNdOjUzNDk_dHJhbnNwb3J0PXRjcCJdLCJ0dXJuVXNlcm5hbWUiOiJ0dXJuLXVzZXIiLCJ0dXJuUGFzc3dvcmQiOiIgc2VjcmV0" +
            "XHQgIn0"
    }
}
