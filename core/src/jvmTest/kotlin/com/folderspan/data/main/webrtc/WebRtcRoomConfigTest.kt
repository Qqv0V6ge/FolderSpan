package com.folderspan.data.main.webrtc

import strings.AppStrings

import com.folderspan.service.webrtc.signaling.generateRoomId
import com.folderspan.service.webrtc.models.WebRtcIceServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcRoomConfigTest {
    @Test
    fun savedRoomKeepsExplicitIceServersAndCredentials() {
        val room = webRtcRoomProfile(source = WebRtcRoomSource.Other).copy(
            stunUrl = "stun:ice.example.test:3478",
            turnUrl = "turns:relay.example.test:5349",
            turnUsername = "saved-user",
            turnPassword = "saved-password",
        )

        val config = room.toWebRtcConfigResult().getOrThrow()

        assertEquals(
            listOf(
                WebRtcIceServerConfig(urls = listOf(room.stunUrl)),
                WebRtcIceServerConfig(
                    urls = listOf(room.turnUrl),
                    username = room.turnUsername,
                    password = room.turnPassword,
                ),
            ),
            config.iceServers,
        )
    }

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

    @Test
    fun officialCatalogKeyUsesRoomIdAndOtherUsesLocalId() {
        val official = officialWebRtcRoomProfile(name = "Office", roomId = "room-official")
        val other = webRtcRoomProfile(source = WebRtcRoomSource.Other).copy(id = 12L)

        assertEquals("official:room-official", official.catalogKey)
        assertEquals("other:12", other.catalogKey)
    }

    @Test
    fun officialRoomCanSaveWithNameOnly() {
        val official = WebRtcRoomInput(
            name = "Office",
            wssUrl = "",
            roomId = "",
            stunUrl = "",
            turnUrl = "",
            turnUsername = "",
            turnPassword = "",
            source = WebRtcRoomSource.Official,
        )
        val other = official.copy(source = WebRtcRoomSource.Other)

        assertTrue(official.canSave())
        assertFalse(other.canSave())
        assertFalse(official.copy(name = "  ").canSave())
        assertTrue(official.copy(name = "x".repeat(OfficialRoomNameMaxLength)).canSave())
        assertFalse(official.copy(name = "x".repeat(OfficialRoomNameMaxLength + 1)).canSave())
    }

    @Test
    fun roomFormRejectsInvalidIceAndConfigReturnsFailure() {
        val input = webRtcRoomProfile(WebRtcRoomSource.Other).toInput()
        assertTrue(input.copy(turnUrl = "turn:relay.example;turns:[2001:db8::1]:443?transport=tcp").canSave())
        for (invalid in listOf("turn:bad:0", "turn:relay.example;", "turn:[::zz]")) {
            val room = input.copy(turnUrl = invalid)
            assertFalse(room.canSave())
            assertTrue(room.toWebRtcConfigResult().isFailure)
            assertFalse(room.copy(source = WebRtcRoomSource.Official).canSave())
        }
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
