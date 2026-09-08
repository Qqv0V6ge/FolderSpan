package com.folderspan.service.session

import com.folderspan.utils.LogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import strings.AppStrings

internal actual object DeviceLanBeaconUdp {
    actual suspend fun broadcast(payload: ByteArray, port: Int) {
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                destinations().forEach { address ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(address, port)))
                    }.onFailure { error ->
                        LogKit.d(AppStrings.ui_lan_beacon_udp_send_failed_dest_arg0_message_arg1.format(arg0 = (address.hostAddress).toString(), arg1 = (error.message).toString()))
                    }
                }
            }
        }
    }

    actual suspend fun receive(port: Int, timeoutMs: Long): List<Pair<String, ByteArray>> {
        return withContext(Dispatchers.IO) {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = timeoutMs.coerceAtLeast(1L).toInt()
                socket.bind(InetSocketAddress(port))
                val packets = ArrayList<Pair<String, ByteArray>>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val host = packet.address?.hostAddress ?: continue
                        if (!isLinkOrSiteLocalHost(host)) continue
                        packets += host to packet.data.copyOf(packet.length)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                packets
            }
        }
    }

    actual suspend fun listen(
        port: Int,
        onPacket: suspend (host: String, payload: ByteArray) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 1_000
                socket.bind(InetSocketAddress(port))
                val buffer = ByteArray(2048)
                while (currentCoroutineContext().isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val host = packet.address?.hostAddress ?: continue
                        if (!isLinkOrSiteLocalHost(host)) continue
                        onPacket(host, packet.data.copyOf(packet.length))
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                }
            }
        }
    }

    private fun destinations(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        runCatching { addresses += InetAddress.getByName("255.255.255.255") }
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
            if (!network.isUp || network.isLoopback) return@forEach
            network.interfaceAddresses.orEmpty().forEach { item ->
                val broadcast = item.broadcast
                if (broadcast is Inet4Address) {
                    addresses += broadcast
                }
            }
        }
        return addresses.distinct()
    }
}
