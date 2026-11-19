package com.folderspan.data.main.webrtc

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebRtcShareCodeTest {
    @Test
    fun directInviteMatchesWireSampleWithIpv6() {
        val invite = WebRtcShareCode.decodeDirectInvite("  $DIRECT_SAMPLE\n", NOW)

        assertEquals("device-a", invite.deviceId)
        assertEquals("2001:db8::42", invite.host)
        assertEquals(48484, invite.port)
        assertEquals("AB".repeat(32), invite.fingerprintSha256)
        assertEquals("A".repeat(43), invite.credential)
        assertEquals(1893456000000, invite.expiresAtEpochMillis)
        assertEquals("stun:[2001:db8::2]:3478", invite.stunUrl)
        assertEquals(DIRECT_SAMPLE, WebRtcShareCode.encodeDirectInvite(invite))
        assertFalse(invite.toString().contains(invite.credential))
        assertEquals(
            WebRtcShareCodeError.Expired,
            assertFailsWith<WebRtcShareCodeException> {
                WebRtcShareCode.decodeDirectInvite(DIRECT_SAMPLE, invite.expiresAtEpochMillis)
            }.reason,
        )
    }

    @Test
    fun temporaryIdAuthenticatesDeviceAndCarriesNoServerOrTurnConfiguration() {
        val original = WebRtcShareCode.decodeDirectInvite(DIRECT_SAMPLE, NOW)
        val token = WebRtcShareCode.newTemporaryDeviceId(original.fingerprintSha256)
        val invite = original.copy(host = "", port = 0, credential = token,
            turnUrl = "turn:relay.example.test", turnUsername = "tester", turnPassword = "private-turn")
        val id = WebRtcShareCode.displayDeviceInvite(invite)
        assertEquals(48, id.length)
        assertEquals(token, WebRtcShareCode.parseTemporaryDeviceId(id))
        assertTrue(WebRtcShareCode.temporaryIdMatches(token, invite.fingerprintSha256))
        assertFalse(WebRtcShareCode.temporaryIdMatches(token, "09".repeat(32)))
        assertFalse(id.contains(invite.turnPassword))
        assertFalse(id.contains(invite.deviceId))
        assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.parseTemporaryDeviceId("fsd1-unsafe") }
    }

    @Test
    fun signalingServerAcceptsSelfHostedWssWithoutUrlCredentials() {
        assertTrue(WebRtcShareCode.isSecureSignalingUrl("wss://example.test:8443/custom/invites"))
        assertFalse(WebRtcShareCode.isSecureSignalingUrl("ws://example.test/invites"))
        assertFalse(WebRtcShareCode.isSecureSignalingUrl("wss://user@example.test/invites"))
        assertFalse(WebRtcShareCode.isSecureSignalingUrl("wss://example.test/invites?secret=1"))
    }

    @Test
    fun deviceInviteCarriesRendezvousAndTurnSettings() {
        val invite = WebRtcShareCode.decodeDirectInvite(DIRECT_SAMPLE, NOW).copy(
            host = "", port = 0, stunUrl = "",
            turnUrl = "turn:relay.example.test:3478;turns:relay.example.test:443?transport=tcp",
            turnUsername = "user", turnPassword = " pass ",
        )
        val decoded = WebRtcShareCode.decodeDirectInvite(WebRtcShareCode.encodeDirectInvite(invite), NOW)
        assertEquals(invite, decoded)
        assertTrue(decoded.usesRendezvous)
        assertFalse(decoded.toString().contains(" pass "))
        assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.encodeDirectInvite(invite.copy(port = 12040)) }
        assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.encodeDirectInvite(invite.copy(turnUrl = "")) }
    }

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
    fun versionAndPurposeAreCheckedBeforeUsingThePayload() {
        expectInvalidDirect(ROOM_SAMPLE, WebRtcShareCodeError.WrongKind)
        assertEquals(
            WebRtcShareCodeError.WrongKind,
            assertFailsWith<WebRtcShareCodeException> {
                WebRtcShareCode.decodeOfficialRoom(DIRECT_SAMPLE, GATEWAY)
            }.reason,
        )
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"version\":1", "\"version\":2"), WebRtcShareCodeError.UnsupportedVersion)
    }

    @Test
    fun invalidInviteDoesNotReturnNetworkParametersOrExposeInput() {
        expectInvalidDirect("not a share code")
        expectInvalidDirect(base64.encode(byteArrayOf(0xc3.toByte(), 0x28)))
        expectInvalidDirect("A".repeat(8193), WebRtcShareCodeError.TooLarge)
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"port\":48484", "\"port\":65536"))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"port\":48484", "\"port\":\"48484\""))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "2001:db8::42", "example.test/path"))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "2001:db8::42", "2001:::42"))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "AB".repeat(32), "ZA".repeat(32)))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "A".repeat(43), "!".repeat(43)))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "stun:[2001:db8::2]:3478", "turn:relay.example.test"))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"host\":", "\"h\\u006fst\":\"duplicate\",\"host\":"))
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"version\":1", "\"version\":2,\"version\":1"))
        for (field in listOf("role", "turnUrl", "authorization")) {
            expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"host\":", "\"$field\":\"do-not-log\",\"host\":"))
        }
        expectInvalidDirect(rewrite(DIRECT_SAMPLE, "\"host\":", "\"extra\":${"[".repeat(20)}1${"]".repeat(20)},\"host\":"))
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

    private fun expectInvalidDirect(code: String, expected: WebRtcShareCodeError = WebRtcShareCodeError.Invalid) {
        val error = assertFailsWith<WebRtcShareCodeException> { WebRtcShareCode.decodeDirectInvite(code, NOW) }
        assertEquals(expected, error.reason)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("do-not-log"))
    }

    private fun rewrite(code: String, before: String, after: String): String =
        base64.encode(base64.decode(code).decodeToString().replace(before, after).encodeToByteArray())

    companion object {
        private const val NOW = 1800000000000L
        private const val GATEWAY = "wss://gateway.example.test/api/v1/webrtc/ws"
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        private const val DIRECT_SAMPLE =
            "eyJ2ZXJzaW9uIjoxLCJraW5kIjoiZGlyZWN0LWludml0ZSIsImRldmljZUlkIjoiZGV2aWNlLWEiLCJob3N0IjoiMjAwMTpkYjg6" +
            "OjQyIiwicG9ydCI6NDg0ODQsImZpbmdlcnByaW50U2hhMjU2IjoiQUJBQkFCQUJBQkFCQUJBQkFCQUJBQkFCQUJBQkFCQUJBQkFC" +
            "QUJBQkFCQUJBQkFCQUJBQkFCQUJBQkFCQUJBQiIsImNyZWRlbnRpYWwiOiJBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB" +
            "QUFBQUFBQUFBQUFBIiwiZXhwaXJlc0F0RXBvY2hNaWxsaXMiOjE4OTM0NTYwMDAwMDAsInN0dW5VcmwiOiJzdHVuOlsyMDAxOmRi" +
            "ODo6Ml06MzQ3OCJ9"

        private const val ROOM_SAMPLE =
            "eyJ2ZXJzaW9uIjoxLCJraW5kIjoib2ZmaWNpYWwtcm9vbSIsInNvdXJjZSI6Im9mZmljaWFsIiwibmFtZSI6IuW3peS9nOWupOea" +
            "hOaIv-mXtCDwn6etIiwicm9vbUlkIjoiQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQSIsIndzc1Vy" +
            "bCI6IndzczovL2dhdGV3YXkuZXhhbXBsZS50ZXN0L2FwaS92MS93ZWJydGMvd3MiLCJzdHVuVXJsIjoic3R1bjpbMjAwMTpkYjg6" +
            "OjJdOjM0NzgiLCJ0dXJuVXJscyI6WyJ0dXJuOjE5Mi4wLjIuNDozNDc4P3RyYW5zcG9ydD11ZHAiLCJ0dXJuczpbMjAwMTpkYjg6" +
            "OjNdOjUzNDk_dHJhbnNwb3J0PXRjcCJdLCJ0dXJuVXNlcm5hbWUiOiJ0dXJuLXVzZXIiLCJ0dXJuUGFzc3dvcmQiOiIgc2VjcmV0" +
            "XHQgIn0"
    }
}
