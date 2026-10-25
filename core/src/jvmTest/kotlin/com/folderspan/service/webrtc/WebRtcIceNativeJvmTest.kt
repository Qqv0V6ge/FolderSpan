package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.*
import com.folderspan.test.runSuspendTest
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.TlsCertPolicy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class WebRtcIceNativeJvmTest {
    @Test
    fun truncatedStunErrorStaysUnknownWhileHostConnectivityWorks() = runSuspendTest {
        // Native ICE binds its sockets to candidate interfaces. Use an address on the
        // same interface instead of relying on cross-interface loopback routing on Windows.
        val localAddresses: Set<InetAddress> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            .toSet()
        val address = localAddresses.sortedByDescending { it.isSiteLocalAddress }.firstOrNull()
            ?: error("STUN error callback test requires an active non-loopback local IPv4 interface")
        val socket = DatagramSocket(0, address)
        val stunUrl = "stun:${address.hostAddress}:${socket.localPort}"
        val requests = AtomicInteger()
        val responses = AtomicInteger()
        val serviceFailure = AtomicReference<Throwable?>(null)
        val service = thread(name = "webrtc-test-stun-error") {
            while (!socket.isClosed) {
                try {
                    val request = DatagramPacket(ByteArray(2048), 2048)
                    socket.receive(request)
                    // The fixture is bound to a real interface, but must not serve other hosts.
                    if (request.address !in localAddresses) continue
                    if (request.length < 20 || request.data[0] != 0.toByte() || request.data[1] != 1.toByte()) continue
                    requests.incrementAndGet()
                    val response = request.data.copyOf(28)
                    response[0] = 1; response[1] = 17; response[2] = 0; response[3] = 8
                    response[20] = 0; response[21] = 9; response[22] = 0; response[23] = 4
                    response[24] = 0; response[25] = 0; response[26] = 4; response[27] = 1
                    socket.send(DatagramPacket(response, response.size, request.socketAddress))
                    responses.incrementAndGet()
                } catch (error: SocketException) {
                    if (!socket.isClosed) serviceFailure.compareAndSet(null, error)
                    break
                } catch (error: Throwable) {
                    serviceFailure.compareAndSet(null, error)
                    break
                }
            }
        }
        var testFailure: Throwable? = null
        try {
            withRealWebRtcDataChannelPair(iceServers = buildWebRtcIceServers(stunUrl)) { outgoing, incoming, local, remote ->
                try {
                    withTimeout(5_000L) {
                        while (local.lastIceServerErrorCode == null || remote.lastIceServerErrorCode == null || responses.get() < 2) delay(10)
                    }
                } catch (error: TimeoutCancellationException) {
                    throw IllegalStateException(
                        "STUN error callbacks missing: requests=${requests.get()} responses=${responses.get()} " +
                            "codes=${local.lastIceServerErrorCode}/${remote.lastIceServerErrorCode}",
                        error,
                    )
                }
                // Upstream STUN binding errors expose attr.number(), so 401 is reported as 1.
                assertEquals(1, local.lastIceServerErrorCode)
                assertEquals(1, remote.lastIceServerErrorCode)
                assertTrue(requests.get() >= 2, "Both native peers must reach the local STUN fixture")
                assertTrue(responses.get() >= 2, "The fixture must actually send both STUN error responses")
                val servers = buildWebRtcIceServers(stunUrl)
                assertEquals(servers.icePathFailureMessage(), servers.icePathFailureMessage(local.lastIceServerErrorCode))
                assertEquals(WebRtcIcePathKind.Direct, local.getSelectedIcePath().kind)
                val received = async(start = CoroutineStart.UNDISPATCHED) { incoming.onMessage.first() }
                assertTrue(outgoing.send(byteArrayOf(1, 2, 3)))
                assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(5_000L) { received.await() })
                println("WEBRTC_LOCAL_ICE_SERVICE response=401 reportedCode=1 diagnosis=Unknown path=Direct carrier=open " +
                    "requests=${requests.get()} responses=${responses.get()}")
            }
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            socket.close()
            service.join(2_000L)
            val cleanupFailure = runCatching { assertFalse(service.isAlive) }.exceptionOrNull()
            val failures = listOfNotNull(serviceFailure.get(), cleanupFailure)
            val primary = testFailure
            if (primary != null) {
                failures.forEach(primary::addSuppressed)
            } else if (failures.isNotEmpty()) {
                failures.drop(1).forEach(failures.first()::addSuppressed)
                throw failures.first()
            }
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
