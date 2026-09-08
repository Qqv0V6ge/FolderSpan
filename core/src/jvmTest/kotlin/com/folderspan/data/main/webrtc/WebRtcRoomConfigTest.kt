package com.folderspan.data.main.webrtc

import strings.AppStrings

import com.folderspan.service.webrtc.signaling.generateRoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRtcRoomConfigTest {
    @Test
    fun officialRoomBuildsConfigWithOfficialUrlAndHeaders() {
        val previousProvider = WebRtcOfficialGateway.provider
        WebRtcOfficialGateway.provider = object : WebRtcOfficialGatewayProvider {
            override fun websocketUrl(): String = "wss://gateway.example/api/v1/webrtc/ws"

            override fun websocketHeaders(): Result<Map<String, String>> =
                Result.success(mapOf("Authorization" to "Bearer token-value"))
        }
        try {
            val room = webRtcRoomProfile(source = WebRtcRoomSource.Official)

            val config = room.toWebRtcConfigResult().getOrThrow()

            assertEquals("wss://gateway.example/api/v1/webrtc/ws", config.wssUrl)
            assertEquals(room.roomId, config.roomId)
            assertEquals(mapOf("Authorization" to "Bearer token-value"), config.headers)
        } finally {
            WebRtcOfficialGateway.provider = previousProvider
        }
    }

    @Test
    fun officialRoomConfigFailsWhenOfficialHeadersAreUnavailable() {
        val previousProvider = WebRtcOfficialGateway.provider
        WebRtcOfficialGateway.provider = object : WebRtcOfficialGatewayProvider {
            override fun websocketUrl(): String = "wss://gateway.example/api/v1/webrtc/ws"

            override fun websocketHeaders(): Result<Map<String, String>> =
                Result.failure(IllegalStateException(AppStrings.ui_log_in_before_connecting_official_webrtc_room))
        }
        try {
            val room = webRtcRoomProfile(source = WebRtcRoomSource.Official)

            val result = room.toWebRtcConfigResult()

            assertTrue(result.isFailure)
            assertEquals(AppStrings.ui_log_in_before_connecting_official_webrtc_room, result.exceptionOrNull()?.message)
        } finally {
            WebRtcOfficialGateway.provider = previousProvider
        }
    }

    @Test
    fun roomConfigMatchingIgnoresDynamicHeaders() {
        val room = webRtcRoomProfile(source = WebRtcRoomSource.Official)
        val activeConfig = room.toInput().toWebRtcConfig().copy(
            headers = mapOf("X-Nonce" to "dynamic-nonce"),
        )

        assertTrue(room.matchesActiveConfig(activeConfig))
    }
}

private fun webRtcRoomProfile(source: WebRtcRoomSource): WebRtcRoomProfile =
    WebRtcRoomProfile(
        id = 1L,
        name = "Office",
        wssUrl = "wss://other.example/ws",
        roomId = generateRoomId(),
        stunUrl = "stun:example.com:3478",
        turnUrl = "",
        turnUsername = "",
        turnPassword = "",
        source = source,
        pinned = false,
        sortOrder = 0L,
        createdAt = 1L,
        updatedAt = 1L,
    )
