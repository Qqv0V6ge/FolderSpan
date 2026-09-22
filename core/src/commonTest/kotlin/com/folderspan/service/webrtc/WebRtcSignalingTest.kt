package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingIceCandidate
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webrtc.signaling.asTargetDevice
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcSignalingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun signalingMessageRoundTrip() {
        val message = SignalingMessage(
            type = "offer",
            roomId = "room-1",
            from = SignalingDevice(id = "local", name = "Local"),
            to = SignalingDevice(id = "remote"),
            sdp = "v=0...",
            candidate = SignalingIceCandidate(
                sdpMid = "0",
                sdpMLineIndex = 0,
                candidate = "candidate:1 1 UDP 2122252543 ..."
            ),
            ts = 1234567890L
        )

        val encoded = json.encodeToString(SignalingMessage.serializer(), message)
        val decoded = json.decodeFromString(SignalingMessage.serializer(), encoded)

        assertEquals(message.type, decoded.type)
        assertEquals(message.roomId, decoded.roomId)
        assertEquals(message.from?.id, decoded.from?.id)
        assertEquals(message.to?.id, decoded.to?.id)
        assertEquals(message.sdp, decoded.sdp)
        assertEquals(message.candidate?.candidate, decoded.candidate?.candidate)
        assertEquals(message.ts, decoded.ts)
    }

    @Test
    fun officialPeerJoinedKeepsUserUuidOnTarget() {
        val decoded = json.decodeFromString(
            SignalingMessage.serializer(),
            """
            {
              "type":"peer-joined",
              "roomId":"y7bEYSeFHFxVCnzs2zQFy89nwl-n1eE17Y2OfJX9_60",
              "from":{
                "userUuid":"user-1",
                "id":"TDL1VBZUuKAXnaKDDIzqN36XMR0XtNdcKktJL6hPTN9eei1IFZxYz5GtXDUk3ZAM",
                "name":"Pixel-Android",
                "pathSeparator":"/",
                "host":"172.19.0.1",
                "port":12042,
                "type":"Android",
                "connectType":"UnConnect"
              },
              "ts":1
            }
            """.trimIndent(),
        )

        val target = decoded.from?.asTargetDevice()
        assertEquals("user-1", target?.userUuid)
        assertEquals(
            "TDL1VBZUuKAXnaKDDIzqN36XMR0XtNdcKktJL6hPTN9eei1IFZxYz5GtXDUk3ZAM",
            target?.id,
        )

        val encoded = json.encodeToString(
            SignalingMessage.serializer(),
            SignalingMessage(
                type = "connect-request",
                roomId = decoded.roomId,
                from = SignalingDevice(id = "27b451de84bfa7e88a86211240eea03e"),
                to = target,
                ts = 1L,
            ),
        )
        assertTrue(encoded.contains("\"userUuid\":\"user-1\""))
        assertFalse(encoded.contains("\"name\""))
    }

    @Test
    fun customRoomTargetOmitsBlankUserUuid() {
        val encoded = json.encodeToString(
            SignalingMessage.serializer(),
            SignalingMessage(
                type = "connect-request",
                roomId = "room-1",
                to = SignalingDevice(id = "remote", userUuid = "  ").asTargetDevice(),
            ),
        )
        assertFalse(encoded.contains("userUuid"))
        assertEquals("remote", json.decodeFromString(SignalingMessage.serializer(), encoded).to?.id)
    }
}
