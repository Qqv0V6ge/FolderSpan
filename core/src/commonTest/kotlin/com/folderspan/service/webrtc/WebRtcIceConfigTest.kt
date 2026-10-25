package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun failedPathDiagnosisDoesNotClaimRelayOrLeakServiceCredentials() {
        val noTurn = emptyList<WebRtcIceServerConfig>().icePathFailureMessage()
        val noCredentials = buildWebRtcIceServers("", "turn:relay.example").icePathFailureMessage()
        val configured = buildWebRtcIceServers("", "turns:relay.example:443", "private-user", "private-password")
        assertTrue(noTurn != noCredentials)
        assertTrue(configured.icePathFailureMessage() != noTurn)
        val summary = configured.toDebugSummary()
        assertFalse(summary.contains("private-"))
        assertFalse(summary.contains("relay.example"))
        assertEquals("configured", buildWebRtcIceServerAttempts(configured).first().label)
        assertEquals(configured, buildWebRtcIceServerAttempts(configured).first().iceServers)
    }

    @Test
    fun observedServiceErrorsAddFactsWithoutGuessingFromConfiguration() {
        val servers = buildWebRtcIceServers("", "turns:private-relay.example:443", "private-user", "private-password")
        val unknown = servers.icePathFailureMessage()
        val unreachable = servers.icePathFailureMessage(701)
        val rejected = servers.icePathFailureMessage(401)
        assertTrue(unreachable != unknown && rejected != unknown && rejected != unreachable)
        assertEquals(rejected, servers.icePathFailureMessage(403))
        assertEquals(rejected, servers.icePathFailureMessage(441))
        assertEquals(unknown, servers.icePathFailureMessage(500))
        assertEquals(unknown, servers.icePathFailureMessage(1))
        assertTrue(emptyList<WebRtcIceServerConfig>().icePathFailureMessage(701).startsWith(emptyList<WebRtcIceServerConfig>().icePathFailureMessage()))
        for (message in listOf(unknown, unreachable, rejected)) assertFalse(message.contains("private-"))
    }

    @Test
    fun multipleTurnTransportsAndAddressFamiliesShareUnmodifiedPassword() {
        val urls = listOf("turn:relay.example:3478?transport=udp", "turn:192.0.2.1:3478?transport=tcp", "turns:[2001:db8::1]:443?transport=tcp")
        val servers = buildWebRtcIceServers("stun:[2001:db8::2]:3478", "  ${urls.joinToString(" ; ")}  ", " user ", " secret ")
        assertEquals(listOf("stun:[2001:db8::2]:3478"), servers[0].urls)
        assertEquals(urls, servers[1].urls)
        assertEquals("user", servers[1].username)
        assertEquals(" secret ", servers[1].password)
        assertEquals(listOf("turns:relay.example:443"), buildWebRtcIceServers("", "turns:relay.example:443").single().urls)
    }

    @Test
    fun invalidTurnEntryRejectsWholeConfigurationWithoutExposingCredentials() {
        for (url in listOf("turn:relay.example;", "https://relay.example", "turn:user:password@relay.example", "turn:256.1.1.1", "turn:relay.example:0", "turn:relay.example:65536", "turn:[2001:::1]", "turn:2001:db8::1", "turns:relay.example?transport=udp", "turn:relay.example?transport=tls", "turn:relay.example/path")) {
            val error = assertFailsWith<IllegalArgumentException>(url) { buildWebRtcIceServers("", url, "private-user", "private-password") }
            assertFalse(error.message.orEmpty().contains("private-"))
        }
        assertFailsWith<IllegalArgumentException> { buildWebRtcIceServers("stun:relay.example?transport=udp") }
        for (address in listOf("::1:", ":2001::1", "192.0.2.1::")) {
            assertFailsWith<IllegalArgumentException> { buildWebRtcIceServers("stun:[$address]") }
        }
    }

    @Test
    fun turnOnlyConfigurationDoesNotAddDefaultStun() {
        val servers = buildWebRtcIceServers(
            turnUrl = "turn:relay.example:3478",
            turnUsername = "user",
            turnPassword = "secret"
        )

        assertEquals(
            listOf(
                WebRtcIceServerConfig(
                    urls = listOf("turn:relay.example:3478"),
                    username = "user",
                    password = "secret"
                )
            ),
            servers
        )
    }
}
