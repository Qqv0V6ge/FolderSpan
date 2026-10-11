package com.folderspan.service.webui

import com.folderspan.service.mcp.http.isLoopbackHost
import com.folderspan.service.mcp.http.normalizeHostHeader
import io.ktor.http.Url

class WebUiSecurityPolicy(
    trustedOrigins: Set<String> = emptySet(),
    private val advertisedHostProvider: () -> Set<String>,
) {
    private val trustedOrigins = trustedOrigins.map { origin ->
        requireNotNull(parseOrigin(origin)) { "Invalid trusted Web UI origin; use an HTTP(S) root and canonical dotted-decimal IPv4" }
    }.toSet()

    fun allows(hostHeader: String?, originHeader: String?, scheme: String): Boolean {
        val authority = hostHeader?.trim() ?: return false
        if (authority.any { it in "/\\@?#" } || scheme !in setOf("http", "https")) return false
        val target = parseOrigin("$scheme://$authority") ?: return false
        val host = normalizeHostHeader(authority) ?: return false
        val hostKey = originHostKey(host)
        val allowedHosts = advertisedHostProvider().mapNotNull(::normalizeHostHeader)
            .mapNotNull { runCatching { originHostKey(it) }.getOrNull() }.toSet()
        if (!isLoopbackHost(host) && hostKey != originHostKey("::1") && hostKey !in allowedHosts) return false
        if (originHeader == null) return true
        val origin = parseOrigin(originHeader) ?: return false
        return sameOrigin(origin, target) || trustedOrigins.any { sameOrigin(origin, it) }
    }
}

private fun parseOrigin(value: String): Url? {
    val origin = value.trim()
    if ("://" !in origin || '/' in origin.substringAfter("://")) return null
    val url = runCatching { Url(WebUiRuntimeAddress.parse(origin).origin) }.getOrNull() ?: return null
    val host = url.host.removeSurrounding("[", "]")
    val ipv4 = host.substringAfterLast(':')
    val last = ipv4.removeSuffix(".").substringAfterLast('.')
    val numericLast = last.all { it in '0'..'9' } ||
        last.startsWith("0x", ignoreCase = true) && last.drop(2).all { it.digitToIntOrNull(16) != null }
    // Browser IPv4 parsing accepts octal/hexadecimal forms. Reject those instead of changing their meaning.
    if (if (':' in host) '.' in ipv4 else numericLast) {
        val parts = ipv4.split('.')
        if (parts.size != 4 || parts.any { part ->
            part.isEmpty() || part.any { it !in '0'..'9' } || part.toIntOrNull() !in 0..255 ||
                part.length > 1 && part.startsWith('0')
        }) return null
    }
    return url
}

private fun sameOrigin(first: Url, second: Url): Boolean =
    first.protocol == second.protocol && originHostKey(first.host) == originHostKey(second.host) && first.port == second.port

private fun originHostKey(value: String): String {
    val host = value.removeSurrounding("[", "]").lowercase()
    if (':' !in host) return host
    fun words(part: String): List<Int> = part.split(':').filter(String::isNotEmpty).flatMap { word ->
        if ('.' in word) {
            val bytes = word.split('.').map(String::toInt)
            listOf((bytes[0] shl 8) or bytes[1], (bytes[2] shl 8) or bytes[3])
        } else listOf(word.toInt(16))
    }
    val halves = host.split("::", limit = 2)
    val left = words(halves[0])
    val right = if (halves.size == 2) words(halves[1]) else emptyList()
    require(if (halves.size == 2) left.size + right.size < 8 else left.size == 8)
    return (left + List(8 - left.size - right.size) { 0 } + right).joinToString(":") { it.toString(16) }
}

data class WebUiAdvertisedEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val url: String,
    val origin: String,
    val loopback: Boolean,
)

fun webUiAdvertisedHosts(lanAddresses: Iterable<String>): List<String> {
    val loopback = listOf("127.0.0.1", "localhost")
    return (loopback + lanAddresses.map { item -> item.removePrefix("[").removeSuffix("]") })
        .filter(String::isNotBlank)
        .distinct()
}

fun buildWebUiAdvertisedEndpoints(
    hosts: Iterable<String>,
    port: Int,
): List<WebUiAdvertisedEndpoint> {
    val normalized = hosts.map { item -> item.removePrefix("[").removeSuffix("]") }
        .filter(String::isNotBlank)
        .distinct()
    return listOf("https", "http").flatMap { scheme ->
        normalized.map { host ->
            val loopback = isLoopbackHost(host)
            val authorityHost = if (':' in host) "[$host]" else host
            val origin = "$scheme://$authorityHost:$port"
            WebUiAdvertisedEndpoint(
                scheme = scheme,
                host = host,
                port = port,
                url = origin,
                origin = origin,
                loopback = loopback,
            )
        }.sortedBy { endpoint -> if (endpoint.loopback) 0 else 1 }
    }
}
