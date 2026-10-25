package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.androidWebRtcConfiguration
import com.folderspan.service.webrtc.models.buildWebRtcIceServers
import org.webrtc.PeerConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcIceConfigurationAndroidTest {
    @Test
    fun nativeConfigurationKeepsUdpIpv6AndSecureTurn() {
        val config = androidWebRtcConfiguration(buildWebRtcIceServers("", "turns:relay.example:443", "u", " p "))
        assertEquals(PeerConnection.IceTransportsType.ALL, config.iceTransportsType)
        assertTrue(config.maxIPv6Networks > 0)
        assertFalse(config.disableIPv6OnWifi)
        assertEquals(PeerConnection.TcpCandidatePolicy.ENABLED, config.tcpCandidatePolicy)
        assertEquals(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE, config.iceServers.single().tlsCertPolicy)
        assertEquals(" p ", config.iceServers.single().password)
    }
}
