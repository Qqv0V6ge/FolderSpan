package com.folderspan.routes

import strings.AppStrings

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.ParameterErrorException
import com.folderspan.service.data.DISCOVERY_PING_HEADER
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.WRITE_BYTES_STREAM_REQUEST_HEADER
import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_STREAM_RANGE_BYTES
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_CONCURRENT_FILE_CHUNKS
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_LENGTH
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_HEADER
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_VERSION
import com.folderspan.service.http.server.isAdvertisedOrLoopbackHost
import com.folderspan.service.mcp.http.normalizeHostHeader
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatusHeaders
import com.folderspan.service.operation.HttpTransferStatusProvider
import com.folderspan.service.webrtc.signaling.HTTP_WEBRTC_CLIENT_TOKEN_HEADER
import com.folderspan.service.webrtc.signaling.HTTP_WEBRTC_HOST_SECRET_HEADER
import com.folderspan.ui.state.device.DeviceTokenFingerprint
import com.folderspan.ui.state.file.ShareTokenFingerprint
import com.folderspan.utils.ProtoBufCodec


internal suspend fun RawHttpApiDispatcher.requireAuth(request: RawHttpRequest): RawHttpResponse? {
    return authorizeDeviceRequest(
        request = request,
        resolveDeviceId = deviceCertificateState::getDeviceIdByToken,
        validateToken = deviceCertificateState::isTokenValid,
        onAuthorized = deviceState::markRemoteDeviceConnected,
    )
}

internal suspend fun authorizeDeviceRequest(
    request: RawHttpRequest,
    resolveDeviceId: (String) -> String?,
    validateToken: (String, DeviceTokenFingerprint) -> Boolean,
    onAuthorized: suspend (String) -> Unit,
): RawHttpResponse? {
    val token = request.authToken()
    if (token.isNullOrEmpty()) {
        return protobufFailure(401, AuthorityException(AppStrings.ui_auth_token_missing))
    }
    val deviceId = resolveDeviceId(token)
        ?: return protobufFailure(401, AuthorityException(AppStrings.ui_auth_token_invalid))
    val fingerprint = request.buildDeviceFingerprint(deviceId)
    if (!validateToken(token, fingerprint)) {
        return protobufFailure(401, AuthorityException(AppStrings.ui_auth_token_invalid))
    }
    onAuthorized(deviceId)
    return null
}

internal fun requireProtobuf(
    request: RawHttpRequest,
    allowOctetStream: Boolean = false,
): RawHttpResponse? {
    val contentType = request.header("content-type").orEmpty().lowercase()
    val isProtobuf = PROTOBUF_CONTENT_TYPES.any { item -> contentType.contains(item) }
    val isOctet = allowOctetStream && contentType.contains(OCTET_STREAM_CONTENT_TYPE)
    return if (isProtobuf || isOctet) null
    else protobufFailure(415, ParameterErrorException("Content-Type must be application/protobuf"))
}

internal suspend fun withTransferStatus(block: suspend () -> RawHttpResponse): RawHttpResponse {
    return HttpTransferStatusProvider.trackRequest { block() }
}

internal suspend fun deviceTransferStatusHeaders(): Map<String, String> {
    return transferStatusHeaders(
        maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
        maxParallelRequests = DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
    )
}

internal suspend fun deviceStreamTransferStatusHeaders(): Map<String, String> {
    return transferStatusHeaders(
        maxChunkBytes = DEVICE_DIRECT_STREAM_RANGE_BYTES,
        maxParallelRequests = DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
    )
}

internal suspend fun transferStatusHeaders(
    maxChunkBytes: Int = MAX_LENGTH,
    maxParallelRequests: Int = MAX_CONCURRENT_FILE_CHUNKS,
): Map<String, String> {
    return HttpTransferStatusHeaders.encode(
        HttpTransferStatusProvider.snapshot(
            maxChunkBytes = maxChunkBytes,
            maxParallelRequests = maxParallelRequests,
        )
    )
}

internal fun rawRequestBodyBufferBytes(expectedBytes: Long): Int {
    if (expectedBytes <= 0L) return 1
    val runtimePlan = HttpTransferRuntimeTuning.plan(
        maxChunkBytes = RAW_REQUEST_BODY_STREAM_BUFFER_BYTES,
        maxParallelRequests = 1,
    )
    return minOf(
        expectedBytes,
        RAW_REQUEST_BODY_STREAM_BUFFER_BYTES.toLong(),
        runtimePlan.recommendedChunkBytes.toLong(),
    ).coerceAtLeast(1L).toInt()
}

internal inline fun <reified T> RawHttpRequest.decodeProtobuf(): T = ProtoBufCodec.decode(body)

internal fun RawHttpRequest.authToken(): String? {
    val authorization = header("authorization")?.trim() ?: return null
    if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
    return authorization.substringAfter(' ').trim().takeIf { item -> item.isNotEmpty() }
}

internal fun RawHttpRequest.writeBytesMetadataHeader(): String? {
    return header(WRITE_BYTES_STREAM_REQUEST_HEADER)
}

internal fun RawHttpRequest.isEncryptedHttpPayload(): Boolean {
    return header(HTTP_ENCRYPTED_PAYLOAD_HEADER) == HTTP_ENCRYPTED_PAYLOAD_VERSION
}

internal fun RawHttpRequest.buildDeviceFingerprint(deviceId: String): DeviceTokenFingerprint {
    val clientIp = remoteHost
    val userAgent = header("user-agent")
    return DeviceTokenFingerprint(deviceId, clientIp, userAgent)
}

internal fun RawHttpRequest.buildShareTokenFingerprint(): ShareTokenFingerprint {
    val clientIp = remoteHost
    val userAgent = header("user-agent")
    return ShareTokenFingerprint(clientIp, userAgent)
}

internal inline fun <reified T> protobuf(
    value: T,
    statusCode: Int = 200,
    extraHeaders: Map<String, String> = emptyMap(),
): RawHttpResponse {
    return rawBytes(
        ProtoBufCodec.encode(value),
        contentType = PROTOBUF_CONTENT_TYPE,
        headers = commonHeaders() + extraHeaders
    ).copy(statusCode = statusCode, reasonPhrase = reasonPhrase(statusCode))
}

internal fun protobufFailure(
    statusCode: Int,
    error: Throwable,
    extraHeaders: Map<String, String> = emptyMap(),
): RawHttpResponse {
    return rawBytes(
        ProtoBufCodec.encode(SerializableResult.failure(error)),
        contentType = PROTOBUF_CONTENT_TYPE,
        headers = commonHeaders() + extraHeaders
    ).copy(statusCode = statusCode, reasonPhrase = reasonPhrase(statusCode))
}

internal fun rawBytes(
    bytes: ByteArray,
    contentType: String,
    headers: Map<String, String> = commonHeaders(),
): RawHttpResponse {
    return RawHttpResponse.bytes(
        200,
        headers = headers + ("Content-Type" to contentType),
        body = bytes
    )
}

internal fun commonHeaders(): Map<String, String> {
    return privateNetworkCorsHeaders(
        advertisedHosts = emptySet(),
        request = null,
        allowedHeaders = DEVICE_API_CORS_ALLOWED_HEADERS,
        exposedHeaders = DEVICE_API_CORS_EXPOSED_HEADERS,
        maxAgeSeconds = "86400",
    )
}

internal fun deviceApiCorsHeaders(
    dispatcher: RawHttpApiDispatcher,
    request: RawHttpRequest? = null,
): Map<String, String> {
    return privateNetworkCorsHeaders(
        advertisedHosts = dispatcher.advertisedHostProvider.get(),
        request = request,
        allowedHeaders = DEVICE_API_CORS_ALLOWED_HEADERS,
        exposedHeaders = DEVICE_API_CORS_EXPOSED_HEADERS,
        maxAgeSeconds = "86400",
    )
}

internal fun webRtcSignalingHeaders(
    dispatcher: RawHttpApiDispatcher,
    request: RawHttpRequest? = null,
): Map<String, String> {
    return privateNetworkCorsHeaders(
        advertisedHosts = dispatcher.advertisedHostProvider.get(),
        request = request,
        allowedHeaders = WEBRTC_SIGNALING_CORS_ALLOWED_HEADERS,
        maxAgeSeconds = "600",
    )
}

internal fun RawHttpResponse.withDeviceApiCors(
    dispatcher: RawHttpApiDispatcher,
    request: RawHttpRequest,
): RawHttpResponse {
    val kept = headers.filterKeys { key ->
        !key.startsWith("Access-Control-", ignoreCase = true) &&
            !key.equals("Vary", ignoreCase = true)
    }
    return copy(headers = kept + deviceApiCorsHeaders(dispatcher, request))
}

internal fun RawHttpApiDispatcher.isRejectedPrivateWebRequest(request: RawHttpRequest): Boolean {
    return isRejectedPrivateWebHost(request) || isRejectedPrivateWebCorsOrigin(request)
}

internal fun RawHttpApiDispatcher.isRejectedPrivateWebHost(request: RawHttpRequest): Boolean {
    val hostHeader = request.header("host")?.trim()?.takeIf { item -> item.isNotEmpty() } ?: return false
    val host = normalizeHostHeader(hostHeader) ?: return true
    return !isAdvertisedOrLoopbackHost(host, advertisedHostProvider.get())
}

internal fun RawHttpApiDispatcher.isRejectedPrivateWebCorsOrigin(request: RawHttpRequest): Boolean {
    val origin = request.header("origin")?.trim()?.takeIf { item -> item.isNotEmpty() } ?: return false
    return !isAllowedPrivateWebCorsOrigin(
        origin = origin,
        requestHost = request.header("host"),
        advertisedHosts = advertisedHostProvider.get(),
    )
}

private fun privateNetworkCorsHeaders(
    advertisedHosts: Set<String>,
    request: RawHttpRequest?,
    allowedHeaders: List<String>,
    exposedHeaders: List<String> = emptyList(),
    maxAgeSeconds: String,
): Map<String, String> {
    val allowedOrigin = request
        ?.header("origin")
        ?.trim()
        ?.takeIf { origin ->
            isAllowedPrivateWebCorsOrigin(
                origin = origin,
                requestHost = request.header("host"),
                advertisedHosts = advertisedHosts,
            )
        }
    return buildMap {
        put("Server", "FolderSpan-RawTLS")
        put("Access-Control-Allow-Headers", allowedHeaders.joinToString(", "))
        put("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        put("Access-Control-Max-Age", maxAgeSeconds)
        if (exposedHeaders.isNotEmpty()) {
            put("Access-Control-Expose-Headers", exposedHeaders.joinToString(", "))
        }
        if (allowedOrigin != null) {
            put("Access-Control-Allow-Origin", allowedOrigin)
            put("Vary", "Origin")
        }
    }
}

private fun isAllowedPrivateWebCorsOrigin(
    origin: String,
    requestHost: String?,
    advertisedHosts: Set<String>,
): Boolean {
    val parsed = RawHttpCorsOrigin.parse(origin) ?: return false
    if (parsed.scheme !in setOf("http", "https")) return false
    val originHost = normalizeHostHeader(parsed.host) ?: return false
    if (!isAdvertisedOrLoopbackHost(originHost, advertisedHosts)) return false
    val requestAuthority = parseRequestHost(requestHost) ?: return false
    return originHost.equals(requestAuthority.host, ignoreCase = true) &&
        parsed.port == requestAuthority.port &&
        isAdvertisedOrLoopbackHost(requestAuthority.host, advertisedHosts)
}

private data class RawHttpCorsOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object {
        fun parse(origin: String): RawHttpCorsOrigin? {
            val trimmed = origin.trim()
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator <= 0) return null
            val scheme = trimmed.substring(0, schemeSeparator).lowercase()
            val authority = trimmed
                .substring(schemeSeparator + 3)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            val parsedAuthority = parseHostAuthority(authority) ?: return null
            val port = parsedAuthority.port ?: defaultOriginPort(scheme) ?: return null
            return RawHttpCorsOrigin(scheme = scheme, host = parsedAuthority.host, port = port)
        }
    }
}

private data class RawHttpHostAuthority(
    val host: String,
    val port: Int?,
)

private fun parseRequestHost(requestHost: String?): RawHttpHostAuthority? {
    val parsed = parseHostAuthority(requestHost?.trim().orEmpty()) ?: return null
    val port = parsed.port ?: return null
    return RawHttpHostAuthority(host = parsed.host, port = port)
}

private fun parseHostAuthority(authority: String): RawHttpHostAuthority? {
    if (authority.isBlank()) return null
    val host: String
    val port: Int?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close <= 1) return null
        host = authority.substring(1, close)
        val remainder = authority.substring(close + 1)
        port = if (remainder.isEmpty()) {
            null
        } else if (remainder.startsWith(":")) {
            remainder.substring(1).toIntOrNull() ?: return null
        } else {
            return null
        }
    } else {
        val separator = authority.lastIndexOf(':')
        if (separator >= 0 && authority.indexOf(':') == separator) {
            host = authority.substring(0, separator)
            port = authority.substring(separator + 1).toIntOrNull() ?: return null
        } else {
            host = authority
            port = null
        }
    }
    val normalizedHost = host.trim().removeSurrounding("[", "]").lowercase()
    if (normalizedHost.isBlank()) return null
    if (port != null && port !in 1..65535) return null
    return RawHttpHostAuthority(host = normalizedHost, port = port)
}

private fun defaultOriginPort(scheme: String): Int? = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}

private val DEVICE_API_CORS_ALLOWED_HEADERS = listOf(
    "Accept",
    "Authorization",
    "Content-Type",
    FILE_SHARE_ACCESS_KEY_HEADER,
    HTTP_WEBRTC_CLIENT_TOKEN_HEADER,
    HTTP_WEBRTC_HOST_SECRET_HEADER,
    DISCOVERY_PING_HEADER,
    WRITE_BYTES_STREAM_REQUEST_HEADER,
)

private val WEBRTC_SIGNALING_CORS_ALLOWED_HEADERS = listOf(
    "Accept",
    "Content-Type",
    FILE_SHARE_ACCESS_KEY_HEADER,
    HTTP_WEBRTC_CLIENT_TOKEN_HEADER,
    HTTP_WEBRTC_HOST_SECRET_HEADER,
)

private val DEVICE_API_CORS_EXPOSED_HEADERS = listOf(
    "Content-Length",
    HttpTransferStatusHeaders.VERSION,
    HttpTransferStatusHeaders.RECOMMENDED_CHUNK_BYTES,
    HttpTransferStatusHeaders.RECOMMENDED_PARALLEL_REQUESTS,
    HttpTransferStatusHeaders.MAX_CHUNK_BYTES,
    HttpTransferStatusHeaders.MAX_PARALLEL_REQUESTS,
    HttpTransferStatusHeaders.ACTIVE_REQUESTS,
    HttpTransferStatusHeaders.BUSY,
    HttpTransferStatusHeaders.RETRY_AFTER_MILLIS,
    HttpTransferStatusHeaders.SAMPLED_AT_MILLIS,
)

internal fun Int.toIntBytes(): ByteArray {
    return byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte()
    )
}

internal fun Throwable.asException(): Exception = this as? Exception ?: Exception(message, this)

internal const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
internal val PROTOBUF_CONTENT_TYPES = setOf(PROTOBUF_CONTENT_TYPE, "application/protobuf")
internal const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"
internal const val RAW_REQUEST_BODY_STREAM_BUFFER_BYTES = 64 * 1024

internal fun reasonPhrase(statusCode: Int): String = when (statusCode) {
    200 -> "OK"
    204 -> "No Content"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    415 -> "Unsupported Media Type"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    503 -> "Service Unavailable"
    else -> "Status"
}

internal fun rejectedCapacityResponse(routeClass: RawHttpRouteClass): RawHttpResponse {
    val statusCode = if (routeClass == RawHttpRouteClass.Pairing) 429 else 503
    return RawHttpResponse.bytes(statusCode, body = byteArrayOf())
}
