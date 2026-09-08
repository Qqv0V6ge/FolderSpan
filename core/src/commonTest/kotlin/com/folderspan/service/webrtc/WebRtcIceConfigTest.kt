package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcIceConfigTest {
    @Test
    fun buildWebRtcIceServersUsesStunOnlyByDefault() {
        val servers = buildWebRtcIceServers()

        assertEquals(
            listOf(WebRtcIceServerConfig(urls = listOf(DEFAULT_WEBRTC_STUN_URL))),
            servers
        )
    }

    @Test
    fun buildWebRtcIceServersSkipsBlankUrlsAndKeepsTurnCredentials() {
        val servers = buildWebRtcIceServers(
            stunUrl = " ",
            turnUrl = "turn:119.91.209.238:3478",
            turnUsername = "user",
            turnPassword = "secret"
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(
                    urls = listOf("turn:119.91.209.238:3478"),
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
            WebRtcIceServerConfig(urls = listOf("stun:119.91.209.238:3478")),
            WebRtcIceServerConfig(urls = listOf("turn:119.91.209.238:3478"))
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
                        WebRtcIceServerConfig(urls = listOf("stun:119.91.209.238:3478"))
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
            turnUrl = "turn:119.91.209.238:3478",
            turnUsername = " ",
            turnPassword = ""
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(
                    urls = listOf("turn:119.91.209.238:3478"),
                    username = "",
                    password = ""
                )
            ),
            servers
        )
    }
}
