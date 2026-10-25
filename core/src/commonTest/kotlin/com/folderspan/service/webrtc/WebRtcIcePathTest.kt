package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebRtcIcePathTest {
    @Test
    fun remoteObservationPreservesDirectionAndRejectsStaleOrMismatchedBindings() {
        val snapshot = WebRtcIceSnapshot("native", "browser", "attempt", 1,
            WebRtcIcePath(localFamily = WebRtcIpFamily.IPv6, remoteFamily = WebRtcIpFamily.IPv4, localTurnTransport = WebRtcTurnTransport.Tls))
        val observed = snapshot.asRemoteObservation("browser", "native", "attempt", 1)
        assertEquals(snapshot.path, observed?.path)
        assertEquals(WebRtcIceObservationSource.Remote, observed?.source)
        assertNull(snapshot.asRemoteObservation("browser", "other", "attempt", 1))
        assertNull(snapshot.asRemoteObservation("browser", "native", "old", 1))
        assertNull(snapshot.asRemoteObservation("browser", "native", "attempt", 2))
        assertNull(snapshot.copy(source = WebRtcIceObservationSource.Remote).asRemoteObservation("browser", "native", "attempt", 1))
    }

    private fun stats(local: Map<String, Any?>, remote: Map<String, Any?>) = mapOf(
        "transport" to WebRtcIceStats("transport", mapOf("selectedCandidatePairId" to "pair")),
        "pair" to WebRtcIceStats("candidate-pair", mapOf("localCandidateId" to "local", "remoteCandidateId" to "remote")),
        "local" to WebRtcIceStats("local-candidate", local),
        "remote" to WebRtcIceStats("remote-candidate", remote),
    )

    private fun candidate(address: String, type: String = "host") =
        mapOf("address" to address, "candidateType" to type, "protocol" to "udp")

    @Test
    fun selectedUdpDirectPathHasBothObservedAddressFamilies() {
        for ((address, family) in listOf("192.0.2.1" to WebRtcIpFamily.IPv4, "2001:db8::1" to WebRtcIpFamily.IPv6)) {
            val path = selectedWebRtcIcePath(stats(candidate(address), candidate(address)))
            assertEquals(WebRtcIcePathKind.Direct, path.kind)
            assertEquals(family, path.localFamily)
            assertEquals(family, path.remoteFamily)
            assertEquals(WebRtcIceProtocol.Udp, path.localProtocol)
        }
    }

    @Test
    fun eitherRelayWinsButOnlyLocalRelayProtocolProvesObserversTurnTransport() {
        val local = candidate("192.0.2.1")
        val relay = candidate("2001:db8::1", "relay") + ("relayProtocol" to "tls")
        val remoteRelay = selectedWebRtcIcePath(stats(local, relay))
        assertEquals(WebRtcIcePathKind.Relay, remoteRelay.kind)
        assertEquals(WebRtcTurnTransport.Unknown, remoteRelay.localTurnTransport)
        val localRelay = selectedWebRtcIcePath(stats(relay, local))
        assertEquals(WebRtcIcePathKind.Relay, localRelay.kind)
        assertEquals(WebRtcIceProtocol.Udp, localRelay.localProtocol)
        assertEquals(WebRtcTurnTransport.Tls, localRelay.localTurnTransport)
    }

    @Test
    fun successfulOrNominatedPairsWithoutExplicitSelectionRemainUnknown() {
        val unselected = stats(candidate("192.0.2.1"), candidate("192.0.2.2")) - "transport"
        assertEquals(WebRtcIcePath(), selectedWebRtcIcePath(unselected + ("other" to WebRtcIceStats("candidate-pair", mapOf("state" to "succeeded", "nominated" to true)))))
        assertEquals(WebRtcIcePath(), selectedWebRtcIcePath(unselected))
        val missing = selectedWebRtcIcePath(stats(mapOf("address" to "private.local"), emptyMap()))
        assertEquals(WebRtcIcePath(), missing)
        assertEquals(WebRtcIcePathKind.Relay, selectedWebRtcIcePath(stats(emptyMap(), mapOf("candidateType" to "relay"))).kind)
    }

    @Test
    fun nativeScopeAndLegacyIpFieldStillDescribeIpv6WithoutPublishingAddress() {
        val local = candidate("fe80::1%eth0")
        val remote = candidate("") + ("ip" to "::1")
        val path = selectedWebRtcIcePath(stats(local, remote))
        assertEquals(WebRtcIpFamily.IPv6, path.localFamily)
        assertEquals(WebRtcIpFamily.IPv6, path.remoteFamily)
    }
}
