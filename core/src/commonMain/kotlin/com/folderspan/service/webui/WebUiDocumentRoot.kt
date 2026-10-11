package com.folderspan.service.webui

data class WebUiStaticFile(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebUiStaticFile) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * contentType.hashCode() + bytes.contentHashCode()
}

interface WebUiDocumentRoot {
    fun get(requestPath: String): WebUiStaticFile?
}

class MapWebUiDocumentRoot(
    private val files: Map<String, WebUiStaticFile>,
) : WebUiDocumentRoot {
    override fun get(requestPath: String): WebUiStaticFile? {
        val normalized = normalizeWebUiStaticPath(requestPath) ?: return null
        return files[normalized] ?: files["/$normalized"]
    }
}

class PlaceholderWebUiDocumentRoot : WebUiDocumentRoot {
    override fun get(requestPath: String): WebUiStaticFile? {
        val normalized = normalizeWebUiStaticPath(requestPath) ?: return null
        if (normalized == "index.html" || normalized == "") {
            return WebUiStaticFile(
                bytes = WEB_UI_PLACEHOLDER_INDEX_HTML.encodeToByteArray(),
                contentType = "text/html; charset=UTF-8",
            )
        }
        return null
    }
}

expect fun createWebUiDocumentRoot(): WebUiDocumentRoot

fun normalizeWebUiStaticPath(requestPath: String): String? {
    val trimmed = requestPath.substringBefore('?').substringBefore('#')
    if (trimmed.isBlank() || trimmed == "/") return "index.html"
    val relative = trimmed.trimStart('/')
    if (relative.isEmpty()) return "index.html"
    val parts = relative.split('/')
    if (parts.any { part -> part == ".." || part == "." || part.contains('\\') }) return null
    return relative
}

fun guessWebUiContentType(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=UTF-8"
        "js", "mjs" -> "text/javascript; charset=UTF-8"
        "css" -> "text/css; charset=UTF-8"
        "json" -> "application/json; charset=UTF-8"
        "wasm" -> "application/wasm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "map" -> "application/json"
        "txt" -> "text/plain; charset=UTF-8"
        else -> "application/octet-stream"
    }
}
