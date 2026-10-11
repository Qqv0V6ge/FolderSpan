package com.folderspan.service.http.clipboard

import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.decodeURLPart
import io.ktor.http.takeFrom
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Clock

data class ClipboardUrlDownloadPolicy(
    val connectTimeoutMillis: Long = 15_000L,
    val firstByteTimeoutMillis: Long = 30_000L,
    val idleReadTimeoutMillis: Long = 30_000L,
    val totalTaskTimeoutMillis: Long = 30L * 60L * 1_000L,
    val chunkSize: Int = 64 * 1024,
    val maximumRedirects: Int = 5,
    val maximumRetryDelayMillis: Long = 30_000L,
)

data class ClipboardUrlRequestHeaders(
    val values: Map<String, String>,
    val sendsTaskCookie: Boolean,
)

fun buildClipboardUrlRequestHeaders(
    config: ClipboardUrlDownloadTaskConfig,
    requestUrl: String,
    initialUrl: String = config.url,
    range: LongRange? = null,
    ifRange: String? = null,
): ClipboardUrlRequestHeaders {
    val headers = linkedMapOf<String, String>()
    if (config.capabilities.canSetUserAgent && config.userAgent.isNotBlank()) {
        headers[HttpHeaders.UserAgent] = config.userAgent
    }
    if (config.automaticHeaders) {
        headers[HttpHeaders.Accept] = "application/octet-stream, application/*;q=0.9, */*;q=0.8"
        if (!config.capabilities.isBrowser) {
            headers[HttpHeaders.AcceptEncoding] = "identity"
        }
    }
    if (range != null) {
        headers[HttpHeaders.Range] = "bytes=${range.first}-${range.last}"
        if (!ifRange.isNullOrBlank()) headers[HttpHeaders.IfRange] = ifRange
    }
    val sendsCookie = config.capabilities.canSetCookie &&
        config.cookie.isNotBlank() &&
        sameClipboardUrlOrigin(initialUrl, requestUrl)
    if (sendsCookie) headers[HttpHeaders.Cookie] = config.cookie
    return ClipboardUrlRequestHeaders(headers, sendsCookie)
}

fun sameClipboardUrlOrigin(first: String, second: String): Boolean {
    val left = runCatching { Url(first) }.getOrNull() ?: return false
    val right = runCatching { Url(second) }.getOrNull() ?: return false
    return left.protocol.name.equals(right.protocol.name, ignoreCase = true) &&
        left.host.equals(right.host, ignoreCase = true) &&
        left.port == right.port
}

fun validateClipboardRedirect(currentUrl: String, location: String): String? {
    val resolved = runCatching {
        URLBuilder().takeFrom(currentUrl).takeFrom(location).build()
    }.getOrNull() ?: return null
    if (resolved.protocol.name !in setOf("http", "https")) return null
    if (resolved.user != null || resolved.password != null) return null
    return resolved.toString()
}

enum class ClipboardUrlResponseKind {
    Downloadable,
    Redirect,
    RetryableFailure,
    AuthenticationRequired,
    Html,
    HttpFailure,
    MissingFileIdentity,
}

data class ClipboardUrlResponseClassification(
    val kind: ClipboardUrlResponseKind,
    val status: Int,
    val contentLength: Long?,
    val contentType: String?,
)

fun classifyClipboardUrlResponse(
    status: Int,
    headers: Map<String, String>,
    finalUrl: String,
): ClipboardUrlResponseClassification {
    fun header(name: String): String? = headers.entries.firstOrNull { (key, _) -> key.equals(name, true) }?.value
    val contentType = header(HttpHeaders.ContentType)?.substringBefore(';')?.trim()?.lowercase()
    val contentLength = header(HttpHeaders.ContentLength)?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    val disposition = header(HttpHeaders.ContentDisposition).orEmpty()
    val isAttachment = disposition.substringBefore(';').trim().equals("attachment", ignoreCase = true)
    val kind = when {
        status in 300..399 -> ClipboardUrlResponseKind.Redirect
        status == 401 || status == 403 -> ClipboardUrlResponseKind.AuthenticationRequired
        status == 408 || status == 429 || status in 500..599 -> ClipboardUrlResponseKind.RetryableFailure
        status !in 200..299 -> ClipboardUrlResponseKind.HttpFailure
        isAttachment -> ClipboardUrlResponseKind.Downloadable
        contentType == "text/html" || contentType == "application/xhtml+xml" -> ClipboardUrlResponseKind.Html
        !contentType.isNullOrBlank() -> ClipboardUrlResponseKind.Downloadable
        urlFileNameCandidate(finalUrl) != null -> ClipboardUrlResponseKind.Downloadable
        else -> ClipboardUrlResponseKind.MissingFileIdentity
    }
    return ClipboardUrlResponseClassification(kind, status, contentLength, contentType)
}

fun resolveClipboardDownloadFileName(
    contentDisposition: String?,
    finalUrl: String,
    contentType: String?,
    epochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): String {
    val candidates = listOfNotNull(
        contentDispositionParameter(contentDisposition, "filename*")?.let(::decodeExtendedFileName),
        contentDispositionParameter(contentDisposition, "filename"),
        urlFileNameCandidate(finalUrl),
    )
    candidates.firstNotNullOfOrNull(::sanitizeClipboardDownloadFileName)?.let { return it }
    val extension = mimeFileExtension(contentType)
    return "download-${epochMillis.coerceAtLeast(0L)}${extension?.let { ".$it" }.orEmpty()}"
}

fun sanitizeClipboardDownloadFileName(raw: String): String? {
    val leafName = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
        leafName.trim().forEach { char ->
            when {
                char.code < 0x20 || char.code == 0x7f -> Unit
                char == '/' || char == '\\' || char == ':' -> append('_')
                else -> append(char)
            }
        }
    }.trim().trimStart('.', ' ').trimEnd('.', ' ').take(180)
    if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
    return cleaned
}

private fun contentDispositionParameter(value: String?, target: String): String? {
    if (value.isNullOrBlank()) return null
    return value.split(';').drop(1).firstNotNullOfOrNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0 || !part.substring(0, separator).trim().equals(target, true)) return@firstNotNullOfOrNull null
        part.substring(separator + 1).trim().removeSurrounding("\"").takeIf(String::isNotBlank)
    }
}

private fun decodeExtendedFileName(value: String): String? {
    val encoded = value.substringAfter("''", value)
    return runCatching { encoded.decodeURLPart() }.getOrNull()
}

private fun urlFileNameCandidate(value: String): String? {
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    val encodedName = url.encodedPath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
    return runCatching { encodedName.decodeURLPart() }.getOrNull()
}

private fun mimeFileExtension(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    "application/json" -> "json"
    "application/octet-stream" -> "bin"
    "text/plain" -> "txt"
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "audio/mpeg" -> "mp3"
    else -> null
}

enum class ClipboardUrlFailureType {
    TransportInterrupted,
    ReadTimeout,
    Tls,
    Cancelled,
    ResponseRejected,
    HttpStatus,
}

fun isClipboardDownloadRetryable(failure: ClipboardUrlFailureType, status: Int? = null): Boolean = when (failure) {
    ClipboardUrlFailureType.TransportInterrupted,
    ClipboardUrlFailureType.ReadTimeout -> true

    ClipboardUrlFailureType.HttpStatus -> status == 408 || status == 429 || status in 500..599
    ClipboardUrlFailureType.Tls,
    ClipboardUrlFailureType.Cancelled,
    ClipboardUrlFailureType.ResponseRejected -> false
}

fun clipboardDownloadRetryDelayMillis(
    retryIndex: Int,
    retryAfter: String? = null,
    jitterUnit: Double = 0.5,
    maximumDelayMillis: Long = 30_000L,
): Long {
    val retryAfterMillis = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0L }?.times(1_000L)
    if (retryAfterMillis != null) return min(retryAfterMillis, maximumDelayMillis)
    val exponent = retryIndex.coerceIn(0, 10)
    val base = min((500.0 * 2.0.pow(exponent)).toLong(), maximumDelayMillis)
    val boundedJitter = jitterUnit.coerceIn(0.0, 1.0)
    return min((base * (0.75 + boundedJitter * 0.5)).toLong(), maximumDelayMillis)
}

data class ClipboardContentRange(
    val first: Long,
    val last: Long,
    val total: Long,
)

fun parseClipboardContentRange(value: String?): ClipboardContentRange? {
    val match = CONTENT_RANGE_PATTERN.matchEntire(value?.trim().orEmpty()) ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (first < 0L || last < first || total <= last) return null
    return ClipboardContentRange(first, last, total)
}

data class ClipboardRangeValidator(
    val headerValue: String,
    val etag: String? = null,
    val lastModified: String? = null,
)

fun clipboardRangeValidator(etag: String?, lastModified: String?): ClipboardRangeValidator? {
    val strongEtag = etag?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return ClipboardRangeValidator(strongEtag, etag = strongEtag)
    val modified = lastModified?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ClipboardRangeValidator(modified, lastModified = modified)
}

data class ClipboardByteRange(val index: Int, val first: Long, val last: Long) {
    val length: Long
        get() = last - first + 1L

    fun asLongRange(): LongRange = first..last
}

fun planClipboardByteRanges(totalBytes: Long, requestedThreads: Int): List<ClipboardByteRange> {
    require(totalBytes > 0L)
    val segmentCount = requestedThreads.coerceIn(1, CLIPBOARD_DOWNLOAD_MAX_THREADS).coerceAtMost(totalBytes.toIntSafe())
    val baseSize = totalBytes / segmentCount
    val remainder = totalBytes % segmentCount
    var offset = 0L
    return List(segmentCount) { index ->
        val size = baseSize + if (index < remainder) 1L else 0L
        ClipboardByteRange(index, offset, offset + size - 1L).also { offset += size }
    }
}

fun validateClipboardRangeResponse(
    status: Int,
    contentRange: String?,
    expected: ClipboardByteRange,
    expectedTotal: Long,
    expectedValidator: ClipboardRangeValidator,
    etag: String?,
    lastModified: String?,
): Boolean {
    if (status != 206) return false
    val actualRange = parseClipboardContentRange(contentRange) ?: return false
    return !(actualRange.first != expected.first || actualRange.last != expected.last || actualRange.total != expectedTotal) && when {
        expectedValidator.etag != null -> etag?.trim() == expectedValidator.etag
        expectedValidator.lastModified != null -> lastModified?.trim() == expectedValidator.lastModified
        else -> false
    }
}

private fun Long.toIntSafe(): Int = if (this > Int.MAX_VALUE) Int.MAX_VALUE else toInt()

private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
