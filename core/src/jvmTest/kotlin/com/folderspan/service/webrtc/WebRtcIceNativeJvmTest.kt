package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import com.folderspan.test.runSuspendTest
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.TlsCertPolicy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.*

class WebRtcIceNativeJvmTest {
    @Test
    fun truncatedStunErrorStaysUnknownWhileHostConnectivityWorks() = runSuspendTest {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val service = thread(name = "webrtc-test-stun-error") {
            while (!socket.isClosed) {
                try {
                    val request = DatagramPacket(ByteArray(2048), 2048)
                    socket.receive(request)
                    if (request.length < 20 || request.data[0] != 0.toByte() || request.data[1] != 1.toByte()) continue
                    val response = request.data.copyOf(28)
                    response[0] = 1; response[1] = 17; response[2] = 0; response[3] = 8
                    response[20] = 0; response[21] = 9; response[22] = 0; response[23] = 4
                    response[24] = 0; response[25] = 0; response[26] = 4; response[27] = 1
                    socket.send(DatagramPacket(response, response.size, request.socketAddress))
                } catch (error: SocketException) {
                    if (!socket.isClosed) throw error
                }
            }
        }
        try {
            withRealWebRtcDataChannelPair(iceServers = buildWebRtcIceServers("stun:127.0.0.1:${socket.localPort}")) { outgoing, incoming, local, remote ->
                withTimeout(5_000L) {
                    while (local.lastIceServerErrorCode == null || remote.lastIceServerErrorCode == null) delay(10)
                }
                // Upstream STUN binding errors expose attr.number(), so 401 is reported as 1.
                assertEquals(1, local.lastIceServerErrorCode)
                assertEquals(1, remote.lastIceServerErrorCode)
                val servers = buildWebRtcIceServers("stun:127.0.0.1:${socket.localPort}")
                assertEquals(servers.icePathFailureMessage(), servers.icePathFailureMessage(local.lastIceServerErrorCode))
                assertEquals(WebRtcIcePathKind.Direct, local.getSelectedIcePath().kind)
                val received = async(start = CoroutineStart.UNDISPATCHED) { incoming.onMessage.first() }
                assertTrue(outgoing.send(byteArrayOf(1, 2, 3)))
                assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(5_000L) { received.await() })
                println("WEBRTC_LOCAL_ICE_SERVICE response=401 reportedCode=1 diagnosis=Unknown path=Direct carrier=open")
            }
        } finally {
            socket.close()
            service.join(2_000L)
            assertFalse(service.isAlive)
        }
    }

    @Test
    fun effectiveNativeConfigurationAllowsUdpAndIpv6AndChecksTurnCertificates() {
        val config = jvmWebRtcConfiguration(buildWebRtcIceServers("", "turns:relay.example:443?transport=tcp", "u", " p "))
        assertEquals(RTCIceTransportPolicy.ALL, config.iceTransportPolicy)
        assertFalse(config.portAllocatorConfig.isUdpDisabled)
        assertFalse(config.portAllocatorConfig.isUdpRelayDisabled)
        assertTrue(config.portAllocatorConfig.isIpv6Enabled)
        assertTrue(config.portAllocatorConfig.isIpv6OnWifiEnabled)
        assertEquals(TlsCertPolicy.SECURE, config.iceServers.single().tlsCertPolicy)
        assertEquals(" p ", config.iceServers.single().password)
        assertEquals("   ", jvmWebRtcConfiguration(buildWebRtcIceServers("", "turn:relay.example", "user", "   ")).iceServers.single().password)
    }

    @Test
    fun realSelectedPairAndRestartPreserveReliableDataChannel() = runSuspendTest {
        verifyRealWebRtcIcePathAndRestart()
    }
}
