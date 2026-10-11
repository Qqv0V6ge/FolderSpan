@file:OptIn(ExperimentalForeignApi::class)

package com.folderspan.service.http

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

actual fun getNetworkInterfacesInfo(): List<NetworkInterfaceInfo> = memScoped {
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return@memScoped emptyList()

    val result = linkedMapOf<String, MutableInterfaceInfo>()
    var cursor = ifap.value
    while (cursor != null) {
        val ifa = cursor.pointed
        val name = ifa.ifa_name?.toKString().orEmpty()
        if (name.isNotEmpty()) {
            val flags = ifa.ifa_flags.toInt()
            val entry = result.getOrPut(name) {
                MutableInterfaceInfo(
                    name = name,
                    displayName = name,
                    isUp = flags and IFF_UP != 0,
                    isLoopback = flags and IFF_LOOPBACK != 0
                )
            }

            val addr = ifa.ifa_addr
            if (addr != null) {
                val family = addr.pointed.sa_family.toInt()
                val isIpv6 = family == AF_INET6
                if (family == AF_INET || family == AF_INET6) {
                    val host = allocArray<ByteVar>(NI_MAXHOST)
                    val addrLen: UInt = if (family == AF_INET) {
                        sizeOf<sockaddr_in>().convert()
                    } else {
                        sizeOf<sockaddr_in6>().convert()
                    }
                    val resultCode = getnameinfo(
                        addr,
                        addrLen,
                        host,
                        NI_MAXHOST.convert(),
                        null,
                        0u,
                        NI_NUMERICHOST
                    )
                    if (resultCode == 0) {
                        val address = host.toKString().substringBefore("%")
                        if (address.isNotEmpty()) {
                            val prefixLength = if (family == AF_INET) {
                                ifa.ifa_netmask
                                    ?.reinterpret<sockaddr_in>()
                                    ?.pointed
                                    ?.sin_addr
                                    ?.s_addr
                                    ?.countOneBits()
                            } else {
                                null
                            }
                            entry.addresses.add(IpAddressInfo(address, isIpv6, prefixLength))
                        }
                    }
                }
            }
        }
        cursor = ifa.ifa_next
    }

    freeifaddrs(ifap.value)
    result.values.map { info ->
        NetworkInterfaceInfo(
            name = info.name,
            displayName = info.displayName,
            isUp = info.isUp,
            isLoopback = info.isLoopback,
            addresses = info.addresses.toList()
        )
    }
}

private class MutableInterfaceInfo(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean
) {
    val addresses: MutableList<IpAddressInfo> = mutableListOf()
}
