package com.folderspan.service.session

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.INADDR_BROADCAST
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.memset
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_REUSEADDR

@OptIn(ExperimentalForeignApi::class)
internal actual object DeviceLanBeaconUdp {
    actual suspend fun broadcast(payload: ByteArray, port: Int) {
        withContext(Dispatchers.Default) {
            memScoped {
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                if (fd < 0) return@withContext
                try {
                    val one = alloc<IntVar>()
                    one.value = 1
                    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
                    val address = alloc<sockaddr_in>()
                    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                    address.sin_family = AF_INET.convert()
                    address.sin_port = port.toNetworkOrderPort()
                    address.sin_addr.s_addr = INADDR_BROADCAST
                    payload.usePinned { pinned ->
                        sendto(
                            fd,
                            pinned.addressOf(0),
                            payload.size.convert(),
                            0,
                            address.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().convert(),
                        )
                    }
                } finally {
                    close(fd)
                }
            }
        }
    }

    actual suspend fun receive(port: Int, timeoutMs: Long): List<Pair<String, ByteArray>> {
        return withContext(Dispatchers.Default) {
            memScoped {
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                if (fd < 0) return@withContext emptyList()
                try {
                    val one = alloc<IntVar>()
                    one.value = 1
                    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
                    val bindAddress = alloc<sockaddr_in>()
                    memset(bindAddress.ptr, 0, sizeOf<sockaddr_in>().convert())
                    bindAddress.sin_family = AF_INET.convert()
                    bindAddress.sin_port = port.toNetworkOrderPort()
                    bindAddress.sin_addr.s_addr = 0u
                    if (bind(fd, bindAddress.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                        return@withContext emptyList()
                    }
                    val packets = ArrayList<Pair<String, ByteArray>>()
                    val buffer = ByteArray(2048)
                    val from = alloc<sockaddr_in>()
                    val fromLen = alloc<socklen_tVar>()
                    fromLen.value = sizeOf<sockaddr_in>().convert()
                    buffer.usePinned { pinned ->
                        val read = recvfrom(
                            fd,
                            pinned.addressOf(0),
                            buffer.size.convert(),
                            0,
                            from.ptr.reinterpret(),
                            fromLen.ptr,
                        )
                        if (read > 0) {
                            packets += sockaddrInHost(from) to buffer.copyOf(read.toInt())
                        }
                    }
                    packets
                } finally {
                    close(fd)
                }
            }
        }
    }

    actual suspend fun listen(
        port: Int,
        onPacket: suspend (host: String, payload: ByteArray) -> Unit,
    ) {
        while (currentCoroutineContext().isActive) {
            receive(port = port, timeoutMs = 1_000L).forEach { (host, payload) ->
                if (isLinkOrSiteLocalHost(host)) {
                    onPacket(host, payload)
                }
            }
        }
    }
}

private fun sockaddrInHost(address: sockaddr_in): String {
    val n = address.sin_addr.s_addr.toInt()
    return "${n and 0xFF}.${(n ushr 8) and 0xFF}.${(n ushr 16) and 0xFF}.${(n ushr 24) and 0xFF}"
}

private fun Int.toNetworkOrderPort(): UShort {
    val value = this and 0xFFFF
    val networkOrder = ((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)
    return networkOrder.toUShort()
}
