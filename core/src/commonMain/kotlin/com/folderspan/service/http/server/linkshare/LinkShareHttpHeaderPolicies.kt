package com.folderspan.service.http.server.linkshare

import com.folderspan.service.http.server.isAdvertisedOrLoopbackHost
import com.folderspan.service.mcp.http.normalizeHostHeader

object LinkShareHttpHeaderPolicies {
    private const val DEFAULT_CORS_ALLOW_HEADERS =
        "Authorization, Content-Type, Content-Range, Range, X-API-Request, X-Requested-With"
    private const val DEFAULT_CORS_ALLOW_METHODS = "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD"
    private const val DEFAULT_CORS_EXPOSE_HEADERS =
        "Content-Range, Content-Length, Content-Disposition, ETag, Accept-Ranges"

    fun corsPreflightResponse(
        request: LinkShareHttpRequest,
        advertisedHosts: Set<String>,
    ): LinkShareHttpResponse? {
        if (!request.method.equals("OPTIONS", ignoreCase = true)) return null
        val origin = request.header("Origin") ?: return null
        if (!isSameOrigin(origin, request.host, request.port, request.scheme, advertisedHosts)) {
            return LinkShareHttpResponse.bytes(statusCode = 403)
        }
        return LinkShareHttpResponse.bytes(
            statusCode = 200,
            headers = corsHeaders(origin),
        )
    }

    fun corsHeaders(
        request: LinkShareHttpRequest,
        advertisedHosts: Set<String>,
    ): List<LinkShareHttpHeader> {
        val origin = request.header("Origin") ?: return emptyList()
        if (!isSameOrigin(origin, request.host, request.port, request.scheme, advertisedHosts)) {
            return emptyList()
        }
        return corsHeaders(origin)
    }

    fun securityHeaders(path: String): List<LinkShareHttpHeader> {
        val isStreamSaverMitm = path.endsWith("/static/streamsaver/mitm.html")
        return listOf(
            LinkShareHttpHeader(
                "Content-Security-Policy",
                buildCsp(
                    allowInlineScript = isStreamSaverMitm,
                    frameAncestors = if (isStreamSaverMitm) "'self'" else "'none'",
                ),
            ),
            LinkShareHttpHeader("X-Content-Type-Options", "nosniff"),
            LinkShareHttpHeader("X-Frame-Options", if (isStreamSaverMitm) "SAMEORIGIN" else "DENY"),
            LinkShareHttpHeader("Referrer-Policy", "same-origin"),
            LinkShareHttpHeader(
                "Permissions-Policy",
                "geolocation=(), microphone=(), camera=(), payment=(), usb=(), interest-cohort=()",
            ),
        )
    }

    fun staticContentMetadata(staticPath: String): LinkShareStaticContentMetadata {
        return when (staticPath) {
            "streamsaver/sw.js" -> LinkShareStaticContentMetadata(
                contentType = contentTypeForPath(staticPath),
                cacheControl = "no-store",
                extraHeaders = listOf(LinkShareHttpHeader("Service-Worker-Allowed", "/static/streamsaver/")),
            )

            "streamsaver/mitm.html" -> LinkShareStaticContentMetadata(
                contentType = "text/html",
                cacheControl = "no-store",
            )

            "theme-config.css" -> LinkShareStaticContentMetadata(
                contentType = "text/css",
                cacheControl = "no-store, no-cache, must-revalidate",
            )

            else -> LinkShareStaticContentMetadata(
                contentType = contentTypeForPath(staticPath),
                cacheControl = "public, max-age=86400, immutable",
            )
        }
    }

    fun staticResponseHeaders(
        staticPath: String,
        etag: String? = null,
        contentLength: Long? = null,
    ): List<LinkShareHttpHeader> {
        val metadata = staticContentMetadata(staticPath)
        return buildList {
            add(LinkShareHttpHeader("Content-Type", metadata.contentType))
            add(LinkShareHttpHeader("Cache-Control", metadata.cacheControl))
            etag?.let { value -> add(LinkShareHttpHeader("ETag", value)) }
            contentLength?.let { value -> add(LinkShareHttpHeader("Content-Length", value.toString())) }
            addAll(metadata.extraHeaders)
        }
    }

    fun contentTypeForPath(path: String): String {
        return when {
            path.endsWith(".css", ignoreCase = true) -> "text/css"
            path.endsWith(".js", ignoreCase = true) -> "text/javascript"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".jpg", ignoreCase = true) ||
                path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            path.endsWith(".ico", ignoreCase = true) -> "image/x-icon"
            path.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
            path.endsWith(".woff", ignoreCase = true) -> "font/woff"
            path.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
            path.endsWith(".eot", ignoreCase = true) -> "application/vnd.ms-fontobject"
            path.endsWith(".html", ignoreCase = true) -> "text/html"
            else -> "application/octet-stream"
        }
    }

    fun isSameOrigin(
        origin: String,
        requestHost: String,
        requestPort: Int,
        requestScheme: String,
        advertisedHosts: Set<String>,
    ): Boolean {
        val parsed = ParsedOrigin.parse(origin) ?: return false
        if (!parsed.scheme.equals(requestScheme.trim(), ignoreCase = true)) return false
        val originHost = normalizeHostHeader(parsed.host) ?: return false
        val normalizedRequestHost = normalizeHostHeader(requestHost) ?: return false
        if (!isAdvertisedOrLoopbackHost(originHost, advertisedHosts)) return false
        if (!isAdvertisedOrLoopbackHost(normalizedRequestHost, advertisedHosts)) return false
        return originHost.equals(normalizedRequestHost, ignoreCase = true) && parsed.port == requestPort
    }

    private fun corsHeaders(origin: String): List<LinkShareHttpHeader> {
        return listOf(
            LinkShareHttpHeader("Access-Control-Allow-Origin", origin),
            LinkShareHttpHeader("Vary", "Origin"),
            LinkShareHttpHeader("Access-Control-Allow-Methods", DEFAULT_CORS_ALLOW_METHODS),
            LinkShareHttpHeader("Access-Control-Allow-Headers", DEFAULT_CORS_ALLOW_HEADERS),
            LinkShareHttpHeader("Access-Control-Expose-Headers", DEFAULT_CORS_EXPOSE_HEADERS),
            LinkShareHttpHeader("Access-Control-Allow-Credentials", "true"),
            LinkShareHttpHeader("Access-Control-Max-Age", "86400"),
        )
    }

    private fun buildCsp(allowInlineScript: Boolean, frameAncestors: String): String {
        val scriptSrc = buildString {
            append("script-src 'self'")
            if (allowInlineScript) {
                append(" 'unsafe-inline'")
            }
        }
        return listOf(
            "default-src 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-ancestors $frameAncestors",
            "form-action 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            scriptSrc,
            "connect-src 'self'",
            "worker-src 'self' blob:",
            "child-src 'self' blob:",
        ).joinToString("; ")
    }
}

data class LinkShareStaticContentMetadata(
    val contentType: String,
    val cacheControl: String,
    val extraHeaders: List<LinkShareHttpHeader> = emptyList(),
)

private data class ParsedOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object {
        fun parse(origin: String): ParsedOrigin? {
            val trimmed = origin.trim()
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator <= 0) return null
            val scheme = trimmed.substring(0, schemeSeparator).lowercase()
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return null
            }
            val authority = trimmed
                .substring(schemeSeparator + 3)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            if (authority.isBlank()) return null

            val host: String
            val port: Int
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close <= 1) return null
                host = authority.substring(1, close)
                port = authority
                    .substring(close + 1)
                    .removePrefix(":")
                    .takeIf { item -> item.isNotBlank() }
                    ?.toIntOrNull()
                    ?: defaultPort
            } else {
                val separator = authority.lastIndexOf(':')
                if (separator >= 0 && authority.indexOf(':') == separator) {
                    host = authority.substring(0, separator)
                    port = authority.substring(separator + 1).toIntOrNull() ?: return null
                } else {
                    host = authority
                    port = defaultPort
                }
            }
            if (host.isBlank() || port !in 1..65535) return null
            return ParsedOrigin(scheme, host, port)
        }
    }
}
