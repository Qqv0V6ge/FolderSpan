package com.folderspan.service.webrtc.models

import com.sun.jna.Platform
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRtcUdpSocketBuffersTest {
    @Test
    fun onlyMatchingUdpSocketIsEnlargedAndRemainsUsable() {
        if (!Platform.isLinux() && !Platform.isAndroid()) return
        val address = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, address).use { target ->
            target.receiveBufferSize = 16 * 1024
            target.sendBufferSize = 16 * 1024
            DatagramSocket(0, address).use { other ->
                ServerSocket(target.localPort, 1, address).use { tcp ->
                    val before = target.receiveBufferSize
                    val sendBefore = target.sendBufferSize
                    val otherBefore = other.receiveBufferSize
                    val tcpBefore = tcp.receiveBufferSize
                    val candidate = "candidate:1 1 udp 1 127.0.0.1 ${target.localPort} typ host"
                    assertEquals(1, configureWebRtcUdpSocketBuffers(candidate))
                    assertTrue(target.receiveBufferSize > before)
                    assertTrue(target.sendBufferSize > sendBefore)
                    repeat(10) { assertEquals(1, configureWebRtcUdpSocketBuffers(candidate)) }
                    assertEquals(otherBefore, other.receiveBufferSize)
                    assertEquals(tcpBefore, tcp.receiveBufferSize)
                    other.send(DatagramPacket(byteArrayOf(42), 1, address, target.localPort))
                    target.soTimeout = 2_000
                    val received = DatagramPacket(ByteArray(1), 1)
                    target.receive(received)
                    assertEquals(42, received.data[0].toInt())
                }
            }
        }
    }

    @Test
    fun matchesWildcardUdpSocketUsedByAndroidIce() {
        if (!Platform.isLinux() && !Platform.isAndroid()) return
        DatagramSocket(0).use { socket ->
            socket.receiveBufferSize = 16 * 1024
            val before = socket.receiveBufferSize
            val candidate = "candidate:1 1 udp 1 127.0.0.1 ${socket.localPort} typ host"
            assertEquals(1, configureWebRtcUdpSocketBuffers(candidate))
            assertTrue(socket.receiveBufferSize > before)
        }
    }

    @Test
    fun ignoresNonLocalUdpCandidates() {
        if (!Platform.isLinux() && !Platform.isAndroid()) return
        for (candidate in listOf(
            "", "candidate:1 1 udp", "candidate:1 1 udp 1 device.local 12345 typ host",
            "candidate:1 1 tcp 1 127.0.0.1 12345 typ host",
            "candidate:1 1 udp 1 127.0.0.1 12345 typ relay",
            "candidate:1 1 udp 1 127.0.0.1 65536 typ host",
        )) assertEquals(0, configureWebRtcUdpSocketBuffers(candidate))
    }
}
