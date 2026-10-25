package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcIceConfigTest {
    @Test
    fun buildWebRtcIceServersUsesNoServersByDefault() {
        val servers = buildWebRtcIceServers()

        assertEquals(emptyList(), servers)
    }

    @Test
    fun buildWebRtcIceServersKeepsExplicitStunAndTurnConfiguration() {
        val servers = buildWebRtcIceServers(
            stunUrl = " stun:ice.example.test:3478 ",
            turnUrl = " turns:relay.example.test:5349 ",
            turnUsername = " user ",
            turnPassword = " password "
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(urls = listOf("stun:ice.example.test:3478")),
                WebRtcIceServerConfig(
                    urls = listOf("turns:relay.example.test:5349"),
                    username = "user",
                    password = " password "
                )
            ),
            servers
        )
    }

    @Test
    fun buildWebRtcIceServersSkipsBlankUrlsAndKeepsTurnCredentials() {
        val servers = buildWebRtcIceServers(
            stunUrl = " ",
            turnUrl = "turn:relay.example.test:3478",
            turnUsername = "user",
            turnPassword = "secret"
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(
                    urls = listOf("turn:relay.example.test:3478"),
                    username = "user",
                    password = "secret"
                )
            ),
            servers
        )
    }

    @Test
    fun buildWebRtcIceServerAttemptsRemovesCredentiallessTurnBeforeStunOnlyFallback() {
        val servers = listOf(
            WebRtcIceServerConfig(urls = listOf("stun:ice.example.test:3478")),
            WebRtcIceServerConfig(urls = listOf("turn:relay.example.test:3478"))
        )

        assertEquals(
            listOf(
                WebRtcIceServerAttempt(
                    label = "configured",
                    iceServers = servers
                ),
                WebRtcIceServerAttempt(
                    label = "without-credentialless-turn",
                    iceServers = listOf(
                        WebRtcIceServerConfig(urls = listOf("stun:ice.example.test:3478"))
                    )
                ),
                WebRtcIceServerAttempt(
                    label = "no-ice-servers",
                    iceServers = emptyList()
                )
            ),
            buildWebRtcIceServerAttempts(servers)
        )
    }

    @Test
    fun buildWebRtcIceServersKeepsBlankTurnCredentialsBlank() {
        val servers = buildWebRtcIceServers(
            stunUrl = " ",
            turnUrl = "turn:relay.example.test:3478",
            turnUsername = " ",
            turnPassword = ""
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(
                    urls = listOf("turn:relay.example.test:3478"),
                    username = "",
                    password = ""
                )
            ),
            servers
        )
    }
}
