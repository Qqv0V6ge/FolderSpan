package com.folderspan.pro.core.network

import com.folderspan.AppBuildConfig

internal data class HttpProxyConfig(
    val url: String,
    val host: String,
    val port: Int,
)

internal fun runtimeHttpProxyConfig(): HttpProxyConfig? =
    AppBuildConfig.HTTP_PROXY_URL
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.let(::parseHttpProxyConfig)

private fun parseHttpProxyConfig(url: String): HttpProxyConfig? {
    val normalizedUrl = when {
        url.startsWith("http://", ignoreCase = true) -> url
        url.startsWith("https://", ignoreCase = true) -> url
        else -> "http://$url"
    }
    val authority = normalizedUrl
        .substringAfter("://", missingDelimiterValue = "")
        .substringBefore("/")
        .substringBefore("@")
        .takeIf { it.isNotEmpty() }
        ?: return null
    val host = authority.substringBefore(":").takeIf { it.isNotEmpty() } ?: return null
    val port = authority.substringAfter(":", missingDelimiterValue = "")
        .toIntOrNull()
        ?: return null
    if (port !in 1..65535) return null
    return HttpProxyConfig(
        url = normalizedUrl,
        host = host,
        port = port,
    )
}
