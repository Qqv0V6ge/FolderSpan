package com.folderspan.service.http.server.linkshare

data class LinkShareHttpRequest(
    val method: String,
    val rawUri: String,
    val encodedPath: String,
    val path: String,
    val queryParameters: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val cookies: Map<String, String>,
    val remoteHost: String?,
    val scheme: String,
    val host: String,
    val port: Int,
    val body: LinkShareHttpRequestBody = LinkShareHttpRequestBody.Empty,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    fun query(name: String): String? = queryParameters[name]?.firstOrNull()

    fun queryValues(name: String): List<String> = queryParameters[name].orEmpty()

    fun isApiRequest(): Boolean = header("X-API-Request") != null

    companion object {
        fun from(
            method: String,
            rawUri: String,
            headers: Map<String, List<String>> = emptyMap(),
            remoteHost: String? = null,
            scheme: String = "http",
            host: String = "127.0.0.1",
            port: Int = if (scheme.equals("https", ignoreCase = true)) 443 else 80,
            body: LinkShareHttpRequestBody = LinkShareHttpRequestBody.Empty,
        ): LinkShareHttpRequest {
            val target = LinkShareHttpTarget.parse(rawUri)
            val normalizedHeaders = normalizeRequestHeaders(headers)
            return LinkShareHttpRequest(
                method = method.uppercase(),
                rawUri = rawUri,
                encodedPath = target.encodedPath,
                path = target.path,
                queryParameters = target.queryParameters,
                headers = normalizedHeaders,
                cookies = parseLinkShareCookieHeader(normalizedHeaders["cookie"]?.joinToString("; ")),
                remoteHost = remoteHost,
                scheme = scheme.lowercase(),
                host = host,
                port = port,
                body = body,
            )
        }
    }
}

interface LinkShareHttpRequestBody {
    val contentLength: Long?
    val remainingBytes: Long?
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun discard()

    object Empty : LinkShareHttpRequestBody {
        override val contentLength: Long = 0L
        override val remainingBytes: Long = 0L

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override suspend fun discard() = Unit
    }

    class Bytes(private val bytes: ByteArray) : LinkShareHttpRequestBody {
        private var offset: Int = 0
        override val contentLength: Long = bytes.size.toLong()
        override val remainingBytes: Long
            get() = (bytes.size - offset).coerceAtLeast(0).toLong()

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (this.offset >= bytes.size) return -1
            val read = minOf(length, bytes.size - this.offset)
            bytes.copyInto(buffer, destinationOffset = offset, startIndex = this.offset, endIndex = this.offset + read)
            this.offset += read
            return read
        }

        override suspend fun discard() {
            offset = bytes.size
        }

        fun copyBytes(): ByteArray = bytes.copyOf()
    }
}

data class LinkShareHttpHeader(
    val name: String,
    val value: String,
)

data class LinkShareHttpCookie(
    val name: String,
    val value: String,
    val path: String = "/",
    val maxAgeSeconds: Int? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = true,
    val sameSite: String? = "Lax",
) {
    fun toHeaderValue(): String = buildString {
        append(name)
        append('=')
        append(value)
        if (path.isNotBlank()) {
            append("; Path=")
            append(path)
        }
        maxAgeSeconds?.let { maxAge ->
            append("; Max-Age=")
            append(maxAge)
        }
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
        sameSite?.takeIf { item -> item.isNotBlank() }?.let { value ->
            append("; SameSite=")
            append(value)
        }
    }
}

sealed class LinkShareHttpResponseBody {
    data object Empty : LinkShareHttpResponseBody()

    data class Bytes(val bytes: ByteArray) : LinkShareHttpResponseBody() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Stream(
        val contentLength: Long?,
        val writer: suspend LinkShareHttpResponseBodyWriter.() -> Unit,
    ) : LinkShareHttpResponseBody()
}

interface LinkShareHttpResponseBodyWriter {
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    suspend fun flush()
}

data class LinkShareHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String = linkShareReasonPhrase(statusCode),
    val headers: List<LinkShareHttpHeader> = emptyList(),
    val body: LinkShareHttpResponseBody = LinkShareHttpResponseBody.Empty,
) {
    fun header(name: String): String? {
        return headers.firstOrNull { item -> item.name.equals(name, ignoreCase = true) }?.value
    }

    fun headerValues(name: String): List<String> {
        return headers
            .filter { item -> item.name.equals(name, ignoreCase = true) }
            .map { item -> item.value }
    }

    companion object {
        fun text(
            statusCode: Int = 200,
            text: String,
            contentType: String = "text/plain; charset=UTF-8",
            headers: List<LinkShareHttpHeader> = emptyList(),
        ): LinkShareHttpResponse {
            val bytes = text.encodeToByteArray()
            return LinkShareHttpResponse(
                statusCode = statusCode,
                headers = headers
                    .withSetHeader("Content-Type", contentType)
                    .withSetHeader("Content-Length", bytes.size.toString()),
                body = LinkShareHttpResponseBody.Bytes(bytes),
            )
        }

        fun bytes(
            statusCode: Int = 200,
            bytes: ByteArray = byteArrayOf(),
            contentType: String? = null,
            headers: List<LinkShareHttpHeader> = emptyList(),
        ): LinkShareHttpResponse {
            val resolvedHeaders = buildList {
                addAll(headers)
                if (contentType != null && none { item -> item.name.equals("Content-Type", ignoreCase = true) }) {
                    add(LinkShareHttpHeader("Content-Type", contentType))
                }
                if (none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    add(LinkShareHttpHeader("Content-Length", bytes.size.toString()))
                }
            }
            return LinkShareHttpResponse(
                statusCode = statusCode,
                headers = resolvedHeaders,
                body = LinkShareHttpResponseBody.Bytes(bytes),
            )
        }

        fun redirect(
            location: String,
            statusCode: Int = 302,
            headers: List<LinkShareHttpHeader> = emptyList(),
        ): LinkShareHttpResponse {
            return LinkShareHttpResponse(
                statusCode = statusCode,
                headers = headers
                    .withSetHeader("Location", location)
                    .withSetHeader("Content-Length", "0"),
            )
        }

        fun stream(
            statusCode: Int = 200,
            contentLength: Long? = null,
            contentType: String? = null,
            headers: List<LinkShareHttpHeader> = emptyList(),
            writer: suspend LinkShareHttpResponseBodyWriter.() -> Unit,
        ): LinkShareHttpResponse {
            val resolvedHeaders = buildList {
                addAll(headers)
                if (contentType != null && none { item -> item.name.equals("Content-Type", ignoreCase = true) }) {
                    add(LinkShareHttpHeader("Content-Type", contentType))
                }
                if (contentLength != null && none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    add(LinkShareHttpHeader("Content-Length", contentLength.toString()))
                }
            }
            return LinkShareHttpResponse(
                statusCode = statusCode,
                headers = resolvedHeaders,
                body = LinkShareHttpResponseBody.Stream(contentLength, writer),
            )
        }
    }
}

class LinkShareHttpResponseBuilder {
    var statusCode: Int = 200
    private val headers = mutableListOf<LinkShareHttpHeader>()
    private var body: LinkShareHttpResponseBody = LinkShareHttpResponseBody.Empty

    fun header(name: String, value: String) {
        headers += LinkShareHttpHeader(name, value)
    }

    fun setHeader(name: String, value: String) {
        headers.removeAll { item -> item.name.equals(name, ignoreCase = true) }
        header(name, value)
    }

    fun cookie(cookie: LinkShareHttpCookie) {
        header("Set-Cookie", cookie.toHeaderValue())
    }

    fun text(value: String, contentType: String = "text/plain; charset=UTF-8") {
        val bytes = value.encodeToByteArray()
        body = LinkShareHttpResponseBody.Bytes(bytes)
        setHeader("Content-Type", contentType)
        setHeader("Content-Length", bytes.size.toString())
    }

    fun bytes(value: ByteArray, contentType: String? = null) {
        body = LinkShareHttpResponseBody.Bytes(value)
        if (contentType != null) setHeader("Content-Type", contentType)
        setHeader("Content-Length", value.size.toString())
    }

    fun stream(
        contentLength: Long? = null,
        contentType: String? = null,
        writer: suspend LinkShareHttpResponseBodyWriter.() -> Unit,
    ) {
        body = LinkShareHttpResponseBody.Stream(contentLength, writer)
        if (contentType != null) setHeader("Content-Type", contentType)
        if (contentLength != null) setHeader("Content-Length", contentLength.toString())
    }

    fun redirect(location: String, statusCode: Int = 302) {
        this.statusCode = statusCode
        setHeader("Location", location)
        setHeader("Content-Length", "0")
        body = LinkShareHttpResponseBody.Empty
    }

    fun build(): LinkShareHttpResponse {
        return LinkShareHttpResponse(
            statusCode = statusCode,
            headers = headers.toList(),
            body = body,
        )
    }
}

data class LinkShareHttpTarget(
    val encodedPath: String,
    val path: String,
    val queryParameters: Map<String, List<String>>,
) {
    companion object {
        fun parse(rawUri: String): LinkShareHttpTarget {
            val withoutFragment = rawUri.substringBefore("#")
            val encodedPath = withoutFragment.substringBefore("?").ifBlank { "/" }
            val query = withoutFragment.substringAfter("?", "")
            return LinkShareHttpTarget(
                encodedPath = if (encodedPath.startsWith("/")) encodedPath else "/$encodedPath",
                path = decodeLinkShareUrlComponent(
                    if (encodedPath.startsWith("/")) encodedPath else "/$encodedPath",
                    plusAsSpace = false,
                ),
                queryParameters = parseLinkShareQueryParameters(query),
            )
        }
    }
}

fun linkShareHeadersOf(vararg headers: Pair<String, String>): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableList<String>>()
    headers.forEach { (name, value) ->
        result.getOrPut(name) { mutableListOf() } += value
    }
    return result
}

fun parseLinkShareQueryParameters(query: String): Map<String, List<String>> {
    if (query.isBlank()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    query.split('&').forEach { part ->
        if (part.isBlank()) return@forEach
        val name = decodeLinkShareUrlComponent(part.substringBefore('='), plusAsSpace = true)
        val value = if ('=' in part) {
            decodeLinkShareUrlComponent(part.substringAfter('='), plusAsSpace = true)
        } else {
            ""
        }
        result.getOrPut(name) { mutableListOf() } += value
    }
    return result
}

fun parseLinkShareCookieHeader(cookieHeader: String?): Map<String, String> {
    if (cookieHeader.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    cookieHeader.split(';').forEach { part ->
        val trimmed = part.trim()
        if (trimmed.isBlank()) return@forEach
        val separator = trimmed.indexOf('=')
        if (separator <= 0) return@forEach
        val name = decodeLinkShareUrlComponent(trimmed.substring(0, separator).trim(), plusAsSpace = false)
        val value = decodeLinkShareUrlComponent(trimmed.substring(separator + 1).trim(), plusAsSpace = false)
        if (name.isNotBlank()) {
            result[name] = value
        }
    }
    return result
}

fun decodeLinkShareUrlComponent(value: String, plusAsSpace: Boolean): String {
    if ('%' !in value && (!plusAsSpace || '+' !in value)) return value
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        when (val char = value[index]) {
            '%' if index + 2 < value.length -> {
                val decoded = hexToInt(value[index + 1], value[index + 2])
                if (decoded != null) {
                    bytes += decoded.toByte()
                    index += 3
                } else {
                    bytes += char.toString().encodeToByteArray().toList()
                    index++
                }
            }
            '+' if plusAsSpace -> {
                bytes += ' '.code.toByte()
                index++
            }
            else -> {
                bytes += char.toString().encodeToByteArray().toList()
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

fun normalizeRequestHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableList<String>>()
    headers.forEach { (name, values) ->
        val normalizedName = name.trim().lowercase()
        if (normalizedName.isBlank()) return@forEach
        result.getOrPut(normalizedName) { mutableListOf() } += values
            .map { value -> value.trim() }
            .filter { value -> value.isNotEmpty() }
    }
    return result
}

fun List<LinkShareHttpHeader>.withSetHeader(name: String, value: String): List<LinkShareHttpHeader> {
    return filterNot { item -> item.name.equals(name, ignoreCase = true) } + LinkShareHttpHeader(name, value)
}

fun linkShareReasonPhrase(statusCode: Int): String {
    return when (statusCode) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Status $statusCode"
    }
}

private fun hexToInt(high: Char, low: Char): Int? {
    val highValue = high.digitToIntOrNull(16) ?: return null
    val lowValue = low.digitToIntOrNull(16) ?: return null
    return highValue * 16 + lowValue
}
