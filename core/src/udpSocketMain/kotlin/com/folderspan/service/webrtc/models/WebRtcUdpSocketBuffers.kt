package com.folderspan.service.webrtc.models

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import io.github.aakira.napier.Napier
import java.io.File
import java.net.InetAddress

private const val UDP_BUFFER_BYTES = 4 * 1024 * 1024
private const val SOL_SOCKET = 1
private const val SO_TYPE = 3
private const val SOCK_DGRAM = 2
private const val SO_SNDBUF = 7
private const val SO_RCVBUF = 8

private interface SocketOptions : Library {
    fun getsockname(fd: Int, address: Pointer, length: IntByReference): Int
    fun getsockopt(fd: Int, level: Int, option: Int, value: IntByReference, length: IntByReference): Int
    fun setsockopt(fd: Int, level: Int, option: Int, value: IntByReference, length: Int): Int
}

private val socketOptions by lazy {
    runCatching { Native.load("c", SocketOptions::class.java) }
        .onFailure { Napier.w("WebRTC UDP buffer configuration is unavailable", it) }
        .getOrNull()
}

/** Called only with locally gathered candidates, never with remote signaling input. */
internal fun configureWebRtcUdpSocketBuffers(candidate: String): Int {
    if (!Platform.isLinux() && !Platform.isAndroid()) return 0
    val fields = candidate.removePrefix("a=").trim().split(Regex("\\s+"))
    if (fields.size < 8 || !fields[0].startsWith("candidate:") ||
        !fields[2].equals("udp", ignoreCase = true) || fields[6] != "typ" || fields[7] != "host"
    ) return 0
    val port = fields[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: return 0
    val host = fields[4]
    // Never resolve a candidate hostname (including mDNS) on the native callback thread.
    if (':' !in host && !host.all { it in '0'..'9' || it == '.' }) return 0
    return try {
        val expectedAddress = InetAddress.getByName(host).address
        val native = socketOptions ?: return 0
        val value = IntByReference()
        val length = IntByReference()
        var matched = 0
        Memory(128).use { address ->
            // ponytail: the bindings expose no ICE send buffer option. Match only this
            // process's local UDP endpoint; replace this scan when the binding exposes them.
            // Never duplicate or close descriptors: their lifetime belongs to WebRTC.
            for (entry in File("/proc/self/fd").list().orEmpty()) {
                val fd = entry.toIntOrNull() ?: continue
                length.value = 4
                if (native.getsockopt(fd, SOL_SOCKET, SO_TYPE, value, length) != 0 || value.value != SOCK_DGRAM) continue
                length.value = 128
                if (native.getsockname(fd, address, length) != 0) continue
                val addressBytes = when {
                    address.getShort(0).toInt() == 2 && length.value >= 16 -> address.getByteArray(4, 4)
                    address.getShort(0).toInt() == 10 && length.value >= 28 -> address.getByteArray(8, 16)
                    else -> continue
                }
                val localPort = ((address.getByte(2).toInt() and 255) shl 8) or (address.getByte(3).toInt() and 255)
                // Android's shared ICE sockets bind the wildcard address.
                if (localPort != port || (addressBytes.any { it != 0.toByte() } &&
                        !InetAddress.getByAddress(addressBytes).address.contentEquals(expectedAddress))
                ) continue
                for (option in intArrayOf(SO_RCVBUF, SO_SNDBUF)) {
                    length.value = 4
                    // Linux reports twice the requested size. Preserve any larger buffer.
                    if (native.getsockopt(fd, SOL_SOCKET, option, value, length) == 0 && value.value < UDP_BUFFER_BYTES * 2) {
                        value.value = UDP_BUFFER_BYTES
                        if (native.setsockopt(fd, SOL_SOCKET, option, value, 4) != 0) {
                            Napier.w("WebRTC UDP buffer option $option failed: errno=${Native.getLastError()}")
                        }
                    }
                }
                matched++
            }
        }
        matched
    } catch (error: Exception) {
        Napier.w("WebRTC UDP buffer configuration failed", error)
        0
    }
}
