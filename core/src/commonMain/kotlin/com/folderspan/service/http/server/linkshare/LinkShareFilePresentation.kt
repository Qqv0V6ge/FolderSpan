package com.folderspan.service.http.server.linkshare

internal const val LINK_SHARE_PREVIEW_QUERY = "preview"
internal const val LINK_SHARE_DEFAULT_CONTENT_TYPE = "application/octet-stream"
internal const val LINK_SHARE_ACTIVE_CONTENT_CSP =
    "sandbox; default-src 'none'; base-uri 'none'; form-action 'none'"

enum class LinkShareFileDelivery(
    val disposition: String,
) {
    Preview("inline"),
    Attachment("attachment"),
}

internal data class LinkShareFilePresentation(
    val delivery: LinkShareFileDelivery,
    val contentType: String,
    val activeContent: Boolean,
    val headers: List<LinkShareHttpHeader>,
)

internal fun LinkShareHttpRequest.resolveFileDelivery(
    browserDefault: LinkShareFileDelivery = LinkShareFileDelivery.Attachment,
): LinkShareFileDelivery {
    if (isApiRequest()) return LinkShareFileDelivery.Attachment
    return if (query(LINK_SHARE_PREVIEW_QUERY) == "1") {
        LinkShareFileDelivery.Preview
    } else {
        browserDefault
    }
}

internal fun resolveLinkShareContentType(
    fileName: String,
    fileMimeTypeHint: String? = null,
    platformMimeTypeHint: String? = null,
): String {
    normalizedMimeType(platformMimeTypeHint)?.takeUnless(::isGenericMimeType)?.let { return it }
    normalizedMimeType(fileMimeTypeHint)?.takeUnless(::isGenericMimeType)?.let { return it }

    val hintedExtension = normalizedExtension(fileMimeTypeHint)
    val fileExtension = extensionOf(fileName)
    return LINK_SHARE_EXTENSION_CONTENT_TYPES[hintedExtension]
        ?: LINK_SHARE_EXTENSION_CONTENT_TYPES[fileExtension]
        ?: LINK_SHARE_DEFAULT_CONTENT_TYPE
}

internal fun isLinkShareBrowserPreviewEligible(
    fileName: String,
    fileMimeTypeHint: String? = null,
    platformMimeTypeHint: String? = null,
): Boolean = resolveLinkShareBrowserPreviewContentType(
    fileName = fileName,
    fileMimeTypeHint = fileMimeTypeHint,
    platformMimeTypeHint = platformMimeTypeHint,
) != null

internal fun resolveLinkShareBrowserPreviewContentType(
    fileName: String,
    fileMimeTypeHint: String? = null,
    platformMimeTypeHint: String? = null,
): String? {
    val contentType = resolveLinkShareContentType(
        fileName = fileName,
        fileMimeTypeHint = fileMimeTypeHint,
        platformMimeTypeHint = platformMimeTypeHint,
    )
    return resolveLinkShareBrowserPreviewContentTypeFromResolved(
        fileName = fileName,
        fileMimeTypeHint = fileMimeTypeHint,
        contentType = contentType,
    )
}

internal fun resolveLinkShareFilePresentation(
    fileName: String,
    requestedDelivery: LinkShareFileDelivery,
    fileMimeTypeHint: String? = null,
    platformMimeTypeHint: String? = null,
    sandboxSupported: Boolean = true,
): LinkShareFilePresentation {
    val contentType = resolveLinkShareContentType(
        fileName = fileName,
        fileMimeTypeHint = fileMimeTypeHint,
        platformMimeTypeHint = platformMimeTypeHint,
    )
    val activeContent = isActiveLinkShareContent(
        fileName = fileName,
        contentType = contentType,
        fileMimeTypeHint = fileMimeTypeHint,
        platformMimeTypeHint = platformMimeTypeHint,
    )
    val previewContentType = resolveLinkShareBrowserPreviewContentTypeFromResolved(
        fileName = fileName,
        fileMimeTypeHint = fileMimeTypeHint,
        contentType = contentType,
    )
    val delivery = when {
        requestedDelivery != LinkShareFileDelivery.Preview -> requestedDelivery
        previewContentType == null -> LinkShareFileDelivery.Attachment
        activeContent && !sandboxSupported -> LinkShareFileDelivery.Attachment
        else -> LinkShareFileDelivery.Preview
    }
    val deliveredContentType = if (delivery == LinkShareFileDelivery.Preview) {
        checkNotNull(previewContentType)
    } else {
        contentType
    }

    val headers = buildList {
        add(LinkShareHttpHeader("Content-Disposition", contentDisposition(delivery, fileName)))
        add(LinkShareHttpHeader("Cache-Control", "no-cache, no-store, must-revalidate"))
        add(LinkShareHttpHeader("Accept-Ranges", "bytes"))
        if (delivery == LinkShareFileDelivery.Preview && activeContent) {
            add(LinkShareHttpHeader("Content-Security-Policy", LINK_SHARE_ACTIVE_CONTENT_CSP))
        }
    }
    return LinkShareFilePresentation(
        delivery = delivery,
        contentType = responseContentType(deliveredContentType, delivery),
        activeContent = activeContent,
        headers = headers,
    )
}

private fun responseContentType(
    contentType: String,
    delivery: LinkShareFileDelivery,
): String {
    return if (delivery == LinkShareFileDelivery.Preview && contentType.startsWith("text/")) {
        "$contentType; charset=UTF-8"
    } else {
        contentType
    }
}

internal fun contentDisposition(
    delivery: LinkShareFileDelivery,
    fileName: String,
): String {
    val sanitized = fileName.replace('\r', '_').replace('\n', '_')
    val fallback = buildString(sanitized.length) {
        sanitized.forEach { char ->
            append(
                when {
                    char.code !in 0x20..0x7E -> '_'
                    char == '\\' || char == '"' -> '\\'
                    else -> char
                }
            )
            if (char == '\\' || char == '"') append(char)
        }
    }
    return "${delivery.disposition}; filename=\"$fallback\"; filename*=UTF-8''${encodeRfc5987(sanitized)}"
}

private fun normalizedMimeType(value: String?): String? {
    val candidate = value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (candidate.isEmpty()) return null
    val slashIndex = candidate.indexOf('/')
    if (slashIndex <= 0 || slashIndex == candidate.lastIndex) return null
    if (!candidate.all { char -> char.isMimeTokenCharacter() || char == '/' }) return null
    if (candidate.indexOf('/', slashIndex + 1) >= 0) return null
    return candidate
}

private fun Char.isMimeTokenCharacter(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "!#$&^_.+-"
}

private fun isGenericMimeType(value: String): Boolean {
    return value == LINK_SHARE_DEFAULT_CONTENT_TYPE || value == "*/*"
}

private fun resolveLinkShareBrowserPreviewContentTypeFromResolved(
    fileName: String,
    fileMimeTypeHint: String?,
    contentType: String,
): String? {
    val extensions = listOfNotNull(
        extensionOf(fileName),
        normalizedExtension(fileMimeTypeHint),
    )
    if (extensions.any { extension -> extension !in LINK_SHARE_EXTENSION_CONTENT_TYPES }) {
        return null
    }

    if (isLinkShareBrowserPreviewContentType(contentType)) return contentType
    return extensions.asSequence()
        .mapNotNull(LINK_SHARE_EXTENSION_CONTENT_TYPES::get)
        .firstOrNull(::isLinkShareBrowserPreviewContentType)
}

private fun isLinkShareBrowserPreviewContentType(contentType: String): Boolean {
    val topLevelType = contentType.substringBefore('/', missingDelimiterValue = "")
    return topLevelType in LINK_SHARE_BROWSER_PREVIEW_TOP_LEVEL_TYPES ||
        contentType in LINK_SHARE_BROWSER_PREVIEW_APPLICATION_TYPES
}

private fun isActiveLinkShareContent(
    fileName: String,
    contentType: String,
    fileMimeTypeHint: String?,
    platformMimeTypeHint: String?,
): Boolean {
    val mimeTypes = listOfNotNull(
        contentType,
        normalizedMimeType(fileMimeTypeHint),
        normalizedMimeType(platformMimeTypeHint),
    )
    if (mimeTypes.any { item -> item in LINK_SHARE_ACTIVE_CONTENT_TYPES }) return true

    val extensions = listOfNotNull(
        extensionOf(fileName),
        normalizedExtension(fileMimeTypeHint),
    )
    return extensions.any { item -> item in LINK_SHARE_ACTIVE_CONTENT_EXTENSIONS }
}

private fun normalizedExtension(value: String?): String? {
    val candidate = value?.trim()?.lowercase().orEmpty()
    if (!candidate.startsWith('.') || candidate.length <= 1) return null
    if (!candidate.drop(1).all { char -> char.isLetterOrDigit() }) return null
    return candidate
}

private fun extensionOf(fileName: String): String? {
    val cleanName = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = cleanName.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex == cleanName.lastIndex) return null
    return normalizedExtension(cleanName.substring(dotIndex))
}

private fun encodeRfc5987(value: String): String {
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char.isRfc5987AttributeCharacter()) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }
}

private fun Char.isRfc5987AttributeCharacter(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "!#$&+-.^_`|~"
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private val LINK_SHARE_ACTIVE_CONTENT_TYPES = setOf(
    "application/xhtml+xml",
    "application/xml",
    "image/svg+xml",
    "text/html",
    "text/xml",
)

private val LINK_SHARE_ACTIVE_CONTENT_EXTENSIONS = setOf(
    ".htm",
    ".html",
    ".svg",
    ".xhtml",
    ".xml",
    ".xsl",
    ".xslt",
)

private val LINK_SHARE_BROWSER_PREVIEW_TOP_LEVEL_TYPES = setOf(
    "audio",
    "image",
    "text",
    "video",
)

private val LINK_SHARE_BROWSER_PREVIEW_APPLICATION_TYPES = setOf(
    "application/javascript",
    "application/json",
    "application/pdf",
    "application/xhtml+xml",
    "application/xml",
)

private val LINK_SHARE_EXTENSION_CONTENT_TYPES = mapOf(
    ".aac" to "audio/aac",
    ".avif" to "image/avif",
    ".avi" to "video/x-msvideo",
    ".bmp" to "image/bmp",
    ".css" to "text/css",
    ".csv" to "text/csv",
    ".flac" to "audio/flac",
    ".gif" to "image/gif",
    ".htm" to "text/html",
    ".html" to "text/html",
    ".ico" to "image/x-icon",
    ".jpeg" to "image/jpeg",
    ".jpg" to "image/jpeg",
    ".js" to "text/javascript",
    ".json" to "application/json",
    ".log" to "text/plain",
    ".m4a" to "audio/mp4",
    ".m4v" to "video/mp4",
    ".md" to "text/markdown",
    ".mjs" to "text/javascript",
    ".mov" to "video/quicktime",
    ".mp3" to "audio/mpeg",
    ".mp4" to "video/mp4",
    ".oga" to "audio/ogg",
    ".ogg" to "audio/ogg",
    ".ogv" to "video/ogg",
    ".opus" to "audio/ogg",
    ".pdf" to "application/pdf",
    ".png" to "image/png",
    ".svg" to "image/svg+xml",
    ".text" to "text/plain",
    ".txt" to "text/plain",
    ".wasm" to "application/wasm",
    ".wav" to "audio/wav",
    ".webm" to "video/webm",
    ".webp" to "image/webp",
    ".xhtml" to "application/xhtml+xml",
    ".xml" to "application/xml",
)
