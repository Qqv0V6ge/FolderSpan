package com.folderspan.service.http.server

import com.folderspan.service.mcp.http.isLoopbackHost
import com.folderspan.service.mcp.http.normalizeHostHeader

fun interface AdvertisedHostProvider {
    fun get(): Set<String>
}

object DefaultAdvertisedHostProvider : AdvertisedHostProvider {
    override fun get(): Set<String> = advertisedLanAndLoopbackHosts()
}

fun advertisedLanAndLoopbackHosts(
    lanAddresses: Iterable<String> = getAllIPAddresses(SocketClientIPEnum.ALL),
): Set<String> {
    return (lanAddresses + listOf("127.0.0.1", "localhost", "::1"))
        .map { item -> item.trim().removePrefix("[").removeSuffix("]") }
        .mapNotNull(::normalizeHostHeader)
        .filter { item -> item.isNotEmpty() }
        .toSet()
}

internal fun isAdvertisedOrLoopbackHost(host: String, advertisedHosts: Set<String>): Boolean {
    val normalized = normalizeHostHeader(host) ?: return false
    if (isLoopbackHost(normalized)) return true
    val allowed = advertisedHosts.mapNotNull(::normalizeHostHeader).toSet()
    return normalized in allowed
}

internal fun resolvePublicHttpHost(
    requestHost: String,
    advertisedHosts: Set<String>,
): String {
    val normalized = normalizeHostHeader(requestHost)
    if (normalized != null && isAdvertisedOrLoopbackHost(normalized, advertisedHosts)) {
        return normalized
    }
    val allowed = advertisedHosts.mapNotNull(::normalizeHostHeader)
    return allowed.firstOrNull { item -> !isLoopbackHost(item) }
        ?: allowed.firstOrNull()
        ?: "127.0.0.1"
}
