package com.folderspan.service.http

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

actual fun getNetworkInterfacesInfo(): List<NetworkInterfaceInfo> {
    val result = mutableListOf<NetworkInterfaceInfo>()
    val interfaces = NetworkInterface.getNetworkInterfaces()
    interfaces.iterator().forEach { nif ->
        val isUp = runCatching { nif.isUp }.getOrDefault(false)
        val isLoopback = runCatching { nif.isLoopback }.getOrDefault(false)

        val ips = mutableListOf<IpAddressInfo>()
        nif.interfaceAddresses.forEach { interfaceAddress ->
            when (val inetAddress = interfaceAddress.address) {
                is Inet4Address -> ips.add(
                    IpAddressInfo(
                        inetAddress.hostAddress,
                        isIPv6 = false,
                        prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                    ),
                )
                is Inet6Address -> {
                    val pure = inetAddress.hostAddress.replace("%.*$".toRegex(), "")
                    ips.add(IpAddressInfo(pure, isIPv6 = true, prefixLength = interfaceAddress.networkPrefixLength.toInt()))
                }
            }
        }
        if (ips.isNotEmpty()) {
            result.add(
                NetworkInterfaceInfo(
                    name = nif.name,
                    displayName = nif.displayName ?: nif.name,
                    isUp = isUp,
                    isLoopback = isLoopback,
                    addresses = ips
                )
            )
        }
    }
    return result
}
