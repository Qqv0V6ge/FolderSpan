package com.folderspan.utils

import io.ktor.http.*

data class WebDavBaseUrl(
    val normalizedBaseUrl: String,
    val displayAddress: String,
)

data class ShareNetworkLink(
    val baseUrl: String,
    val password: String?,
    val hostLabel: String,
)

data class NetworkAddress(
    val normalizedHost: String,
    val displayAddress: String,
    val hostLabel: String,
)

data class SmbNetworkAddress(
    val normalizedHost: String,
    val displayAddress: String,
    val hostLabel: String,
    val shareName: String,
)

fun parseShareNetworkLink(input: String, defaultPort: Int? = null): ShareNetworkLink? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }

    val parts = withScheme.split("?", limit = 2)
    val basePart = parts.firstOrNull()?.trim().orEmpty()
    val match = Regex("^(https?://[^/]+)").find(basePart) ?: return null
    var baseUrl = match.value.trimEnd('/')

    // 如果没有指定端口且提供了默认端口，则使用默认端口
    val hostPart = baseUrl.removePrefix("http://").removePrefix("https://")
    if (!hostPart.contains(":") && defaultPort != null) {
        baseUrl = "$baseUrl:$defaultPort"
    }

    val query = parts.getOrNull(1).orEmpty()
    val password = query
        .split("&").firstNotNullOfOrNull { item ->
            if (item.startsWith("pwd=")) item.substringAfter("pwd=") else null
        }
        ?.takeIf { it.isNotBlank() }
        ?.decodeURLQueryComponent()

    val hostLabel = baseUrl
        .removePrefix("http://")
        .removePrefix("https://")

    return ShareNetworkLink(
        baseUrl = baseUrl,
        password = password,
        hostLabel = hostLabel,
    )
}

fun parseNetworkAddress(input: String, defaultPort: Int? = null): NetworkAddress? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withoutFragment = trimmed.substringBefore("#")
    val withoutQuery = withoutFragment.substringBefore("?")

    val uncPrefix = "\\\\"
    if (withoutQuery.startsWith(uncPrefix)) {
        val hostPart = withoutQuery.removePrefix(uncPrefix)
            .substringBefore("\\")
            .substringBefore("/")
        val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
        return NetworkAddress(
            normalizedHost = normalized,
            displayAddress = normalized,
            hostLabel = normalized,
        )
    }

    val schemeIndex = withoutQuery.indexOf("://")
    if (schemeIndex > 0) {
        val scheme = withoutQuery.substring(0, schemeIndex)
        val remainder = withoutQuery.substring(schemeIndex + 3)
        val hostPart = remainder.substringBefore("/").substringBefore("\\")
        val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
        return NetworkAddress(
            normalizedHost = normalized,
            displayAddress = "$scheme://$normalized",
            hostLabel = normalized,
        )
    }

    val hostPart = withoutQuery.substringBefore("/").substringBefore("\\")
    val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
    return NetworkAddress(
        normalizedHost = normalized,
        displayAddress = normalized,
        hostLabel = normalized,
    )
}

fun parseSmbAddress(input: String, defaultPort: Int? = null): SmbNetworkAddress? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withoutFragment = trimmed.substringBefore("#")
    val withoutQuery = withoutFragment.substringBefore("?")

    val uncPrefix = "\\\\"
    if (withoutQuery.startsWith(uncPrefix)) {
        val remainder = withoutQuery.removePrefix(uncPrefix)
        val parts = remainder.split('\\', '/', limit = 3)
        val hostPart = parts.getOrNull(0).orEmpty()
        val shareName = parts.getOrNull(1).orEmpty().trim()
        val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
        return SmbNetworkAddress(
            normalizedHost = normalized,
            displayAddress = listOf(normalized, shareName).filter { item -> item.isNotBlank() }.joinToString("\\"),
            hostLabel = normalized,
            shareName = shareName,
        )
    }

    val schemeIndex = withoutQuery.indexOf("://")
    if (schemeIndex > 0) {
        val scheme = withoutQuery.substring(0, schemeIndex)
        val remainder = withoutQuery.substring(schemeIndex + 3)
        val hostPart = remainder.substringBefore("/").substringBefore("\\")
        val pathPart = remainder.substringAfter(hostPart, "")
        val shareName = pathPart.trim('/', '\\')
            .substringBefore("/")
            .substringBefore("\\")
            .trim()
        val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
        val shareSuffix = shareName.takeIf { item -> item.isNotBlank() }?.let { item -> "/$item" }.orEmpty()
        return SmbNetworkAddress(
            normalizedHost = normalized,
            displayAddress = "$scheme://$normalized$shareSuffix",
            hostLabel = normalized,
            shareName = shareName,
        )
    }

    val hostPart = withoutQuery.substringBefore("/").substringBefore("\\")
    val pathPart = withoutQuery.substringAfter(hostPart, "")
    val shareName = pathPart.trim('/', '\\')
        .substringBefore("/")
        .substringBefore("\\")
        .trim()
    val normalized = normalizeHostPort(hostPart, defaultPort) ?: return null
    val shareSuffix = shareName.takeIf { item -> item.isNotBlank() }?.let { item -> "/$item" }.orEmpty()
    return SmbNetworkAddress(
        normalizedHost = normalized,
        displayAddress = "$normalized$shareSuffix",
        hostLabel = normalized,
        shareName = shareName,
    )
}

fun parseWebDavBaseUrl(input: String): WebDavBaseUrl? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    val scheme = url.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val normalized = url.toString().trimEnd('/')
    return WebDavBaseUrl(
        normalizedBaseUrl = normalized,
        displayAddress = normalized,
    )
}

private fun normalizeHostPort(hostPart: String, defaultPort: Int? = null): String? {
    val trimmedHost = hostPart.trim()
    if (trimmedHost.isEmpty()) return null

    val invalidPort = hasInvalidPortSuffix(trimmedHost)
    if (invalidPort) return null

    val parsed = NetworkHostUtils.splitHostPort(trimmedHost)
    val resolvedPort = parsed.port ?: defaultPort
    return NetworkHostUtils.combineHostPort(parsed.host, resolvedPort)
}

private fun hasInvalidPortSuffix(value: String): Boolean {
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        if (end < 0) return false
        val portPart = if (end + 1 < value.length && value[end + 1] == ':') {
            value.substring(end + 2)
        } else {
            ""
        }
        return portPart.isNotEmpty() && portPart.toIntOrNull() == null
    }
    val lastColon = value.lastIndexOf(':')
    if (lastColon <= 0) return false
    val isIpv6 = value.count { it == ':' } > 1
    if (isIpv6) return false
    val portPart = value.substring(lastColon + 1)
    return portPart.isNotEmpty() && portPart.toIntOrNull() == null
}
