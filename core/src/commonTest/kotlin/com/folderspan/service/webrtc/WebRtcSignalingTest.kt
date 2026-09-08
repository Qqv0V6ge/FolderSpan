package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.SignalingIceCandidate
import com.folderspan.service.webrtc.signaling.SignalingMessage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
