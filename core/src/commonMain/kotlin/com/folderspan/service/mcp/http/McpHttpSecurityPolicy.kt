package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.isValidMcpPort

fun interface McpAllowedHostProvider {
    fun get(): Set<String>
}

class McpHttpSecurityPolicy(
    private val allowedHostProvider: McpAllowedHostProvider,
) {
    fun allows(hostHeader: String?, originHeader: String?): Boolean {
        val host = normalizeHostHeader(hostHeader) ?: return false
        val allowedHosts = allowedHostProvider.get().mapNotNull(::normalizeHostHeader).toSet()
        if (!isLoopbackHost(host) && host !in allowedHosts) return false
        val origin = originHeader?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: return true
        if (!isValidMcpOrigin(origin)) return false
        val originHost = originHost(origin) ?: return false
        return isLoopbackHost(originHost)
    }
}

internal fun normalizeHostHeader(value: String?): String? {
    var host = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    if (host.startsWith('[')) {
        val end = host.indexOf(']')
        if (end <= 1) return null
        return host.substring(1, end).substringBefore('%')
    }
    if (host.count { character -> character == ':' } == 1) host = host.substringBefore(':')
    return host.trimEnd('.').substringBefore('%').takeIf(String::isNotEmpty)
}

internal fun isLoopbackHost(host: String): Boolean {
    val normalized = normalizeHostHeader(host) ?: return false
    if (normalized == "localhost" || normalized == "::1") return true
    val ipv4 = normalized.removePrefix("::ffff:")
    val octets = ipv4.split('.')
    if (octets.size != 4) return false
    val parsed = octets.map { part -> part.toIntOrNull() ?: return false }
    return parsed.all { octet -> octet in 0..255 } && parsed[0] == 127
}

internal fun isValidMcpOrigin(value: String): Boolean {
    val origin = value.trim().trimEnd('/')
    val schemeEnd = origin.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = origin.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = origin.substring(schemeEnd + 3)
    if (authority.isBlank() || authority.any(Char::isWhitespace)) return false
    if (authority.any { item -> item == '/' || item == '?' || item == '#' || item == '@' }) return false
    val hostText: String
    val portText = when {
        authority.startsWith('[') -> {
            val bracket = authority.indexOf(']')
            if (bracket <= 1) return false
            hostText = authority.substring(1, bracket)
            val suffix = authority.substring(bracket + 1)
            when {
                suffix.isEmpty() -> null
                suffix.startsWith(':') -> suffix.drop(1)
                else -> return false
            }
        }
        authority.count { item -> item == ':' } > 1 -> return false
        ':' in authority -> {
            hostText = authority.substringBeforeLast(':')
            authority.substringAfterLast(':')
        }
        else -> {
            hostText = authority
            null
        }
    }
    return hostText.isNotBlank() && (portText == null || portText.toIntOrNull()?.let(::isValidMcpPort) == true)
}

private fun originHost(origin: String): String? {
    val schemeEnd = origin.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = origin.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = origin.substring(schemeEnd + 3).substringBefore('/')
    if (authority.contains('@')) return null
    return normalizeHostHeader(authority)
}
