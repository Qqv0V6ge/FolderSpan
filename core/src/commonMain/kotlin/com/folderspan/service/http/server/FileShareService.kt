package com.folderspan.service.http.server

import com.folderspan.extensions.isSame24Subnet
import com.folderspan.service.http.getNetworkInterfacesInfo

enum class SocketClientIPEnum {
    ALL,
    IPV4_UP
}

/**
 * 启动文件分享服务（由各平台提供具体实现）。
 */
expect suspend fun startFileShareService()

/**
 * 获取本机 IP 地址列表。
 *
 * @param type 仅 IPv4（且网卡启用、非回环）或全部地址
 * @return 本机 IP 地址字符串列表（IPv6 会包裹在 [] 中）
 */
fun getAllIPAddresses(type: SocketClientIPEnum): List<String> {
    val interfaces = getNetworkInterfacesInfo()
    if (type == SocketClientIPEnum.IPV4_UP) {
        return rankedAdvertisableIpv4Hosts(interfaces)
    }

    val addresses = mutableListOf<String>()
    interfaces.forEach { nif ->
        nif.addresses.forEach { ip ->
            when {
                !ip.isIPv6 -> addresses.add(ip.address)
                type == SocketClientIPEnum.ALL -> addresses.add("[${ip.address}]")
            }
        }
    }
    if (type == SocketClientIPEnum.ALL) {
        addresses.addAll(listOf("[::1]", "[0:0:0:0:0:0:0:0]"))
    }
    return addresses
}

/**
 * 汇总本机 IPv4 地址集合，用于避免扫描或 Ping 到自身。
 *
 * @param extra 额外需要纳入的本机地址
 * @return 去重后的 IPv4 地址集合
 */
fun getLocalIpv4Set(extra: List<String> = emptyList()): Set<String> {
    // 汇总本地 IPv4，避免扫描或 Ping 到自身。
    val interfaceIps = getNetworkInterfacesInfo()
        .flatMap { item ->  item.addresses }
        .filter { item ->  !item.isIPv6 }
        .map { item ->  item.address }
    return (extra + getAllIPAddresses(SocketClientIPEnum.IPV4_UP) + interfaceIps + listOf("127.0.0.1", "0.0.0.0"))
        .map { item ->  item.trim() }
        .filter { item ->  item.isNotEmpty() }
        .toSet()
}

internal fun selectAdvertisedHttpHost(
    currentHost: String,
    targetHost: String? = null,
    candidateHosts: List<String> = emptyList(),
): String {
    val current = currentHost.trim()
    val target = targetHost?.trim().orEmpty()
    val reachableCandidates = candidateHosts
        .map { item -> item.trim() }
        .filter { item -> item.isNotEmpty() && !item.isLoopbackOrUnspecifiedHttpHost() }
        .distinct()

    if (target.isNotEmpty() && !target.isLoopbackOrUnspecifiedHttpHost()) {
        if (!current.isLoopbackOrUnspecifiedHttpHost() && current.isSame24Subnet(target)) {
            return current
        }
        reachableCandidates.firstOrNull { item -> item.isSame24Subnet(target) }?.let { item ->
            return item
        }
    }

    if (!current.isLoopbackOrUnspecifiedHttpHost()) {
        return current
    }
    return reachableCandidates.firstOrNull() ?: current
}

internal fun resolveDiscoveredHttpDeviceHost(
    advertisedHost: String,
    remoteHost: String?,
): String {
    val remote = remoteHost?.trim().orEmpty()
    if (!remote.isLoopbackOrUnspecifiedHttpHost()) return remote
    val advertised = advertisedHost.trim()
    return advertised.ifBlank { remote }
}

private fun String.isLoopbackOrUnspecifiedHttpHost(): Boolean {
    val normalized = trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
    return normalized.isBlank() ||
        normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized == "0.0.0.0" ||
        normalized == "::" ||
        normalized == "::1" ||
        normalized == "0:0:0:0:0:0:0:0" ||
        normalized == "0:0:0:0:0:0:0:1" ||
        normalized.startsWith("127.")
}
