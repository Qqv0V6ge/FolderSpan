package com.folderspan.service.http

// 抽象所有平台的网卡/IP 信息
data class IpAddressInfo(
    val address: String, // 不带端口，IPv6 去除 zoneId
    val isIPv6: Boolean,
    val prefixLength: Int? = null,
)

data class NetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<IpAddressInfo>
)

// 返回当前平台完整的网卡与 IP 信息
expect fun getNetworkInterfacesInfo(): List<NetworkInterfaceInfo>
