@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import com.folderspan.test.runSuspendTest
import WebRTC.RTCIceTransportPolicy
import WebRTC.RTCTcpCandidatePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebRtcIceNativeIosTest {
    @Test
    fun nativeConfigurationKeepsIpv6AndAllCandidateTransports() {
        val peer = WebRtcPeerConnection(emptyList())
        try {
            val config = peer.peerConnection.ios.configuration
            assertEquals(RTCIceTransportPolicy.RTCIceTransportPolicyAll, config.iceTransportPolicy)
            assertEquals(RTCTcpCandidatePolicy.RTCTcpCandidatePolicyEnabled, config.tcpCandidatePolicy)
            assertFalse(config.disableIPV6OnWiFi)
        } finally { peer.close() }
    }

    @Test
    fun realSelectedPairAndRestartPreserveReliableDataChannel() = runSuspendTest {
        verifyRealWebRtcIcePathAndRestart()
    }
}
