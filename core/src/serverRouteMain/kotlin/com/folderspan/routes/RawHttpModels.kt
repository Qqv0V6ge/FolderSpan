package com.folderspan.routes

import kotlinx.coroutines.sync.Semaphore

data class RawHttpRequest(
    val method: String,
    val path: String,
    val protocolVersion: String = "HTTP/1.1",
    val queryParameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
    val bodyReader: RawHttpRequestBodyReader? = null,
    val remoteHost: String? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    suspend fun discardUnreadBody(): Boolean {
        val reader = bodyReader ?: return true
        return runCatching {
            reader.discard()
            true
        }.getOrDefault(false)
    }
}

internal enum class RawHttpRouteClass {
    Control,
    Data,
    Pairing,
}

interface RawHttpRequestBodyReader {
    val contentLength: Long
    val remainingBytes: Long
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun discard()
}

internal fun RawHttpRequest.routeClass(): RawHttpRouteClass = classifyRawHttpRoute(path)

internal fun classifyRawHttpRoute(path: String): RawHttpRouteClass {
    return when {
        path in DATA_ROUTES -> RawHttpRouteClass.Data
        path in PAIRING_ROUTES -> RawHttpRouteClass.Pairing
        else -> RawHttpRouteClass.Control
    }
}

internal fun shouldUseLargeRawHttpRequestBodyLimit(path: String): Boolean {
    return path in STREAMING_REQUEST_BODY_ROUTES
}

internal fun shouldStreamRawHttpRequestBody(
    path: String,
    contentLength: Long,
    isChunked: Boolean,
): Boolean {
    return path in STREAMING_REQUEST_BODY_ROUTES &&
        contentLength > 0L &&
        !isChunked
}

internal fun RawHttpRequest.shouldKeepAliveConnection(
    dataKeepAliveEnabled: Boolean = true,
): Boolean {
    if (!dataKeepAliveEnabled && routeClass() == RawHttpRouteClass.Data) return false
    val connectionTokens = header("connection")
        ?.split(",")
        ?.map { item -> item.trim().lowercase() }
        .orEmpty()
    return "close" !in connectionTokens && (protocolVersion.equals("HTTP/1.1", ignoreCase = true) || "keep-alive" in connectionTokens)
}

internal class RawHttpRouteCapacity(
    maxControlRequests: Int,
    maxDataRequests: Int,
    maxIdleDataKeepAliveConnections: Int,
    maxPairingRequests: Int = DEFAULT_MAX_PAIRING_REQUESTS,
) {
    private val controlSemaphore = Semaphore(maxControlRequests.coerceAtLeast(1))
    private val dataSemaphore = Semaphore(maxDataRequests.coerceAtLeast(1))
    private val pairingSemaphore = Semaphore(maxPairingRequests.coerceAtLeast(1))
    private val maxIdleDataKeepAliveConnections = maxIdleDataKeepAliveConnections.coerceAtLeast(0)
    private val idleDataKeepAliveSemaphore = Semaphore(this.maxIdleDataKeepAliveConnections.coerceAtLeast(1))

    suspend fun <T> withAdmittedRequestOrNull(routeClass: RawHttpRouteClass, block: suspend () -> T): T? {
        val permit = tryAcquireRequestPermit(routeClass) ?: return null
        return try {
            block()
        } finally {
            permit.release()
        }
    }

    fun tryAcquireRequestPermit(routeClass: RawHttpRouteClass): RawHttpCapacityPermit? {
        val semaphore = when (routeClass) {
            RawHttpRouteClass.Control -> controlSemaphore
            RawHttpRouteClass.Data -> dataSemaphore
            RawHttpRouteClass.Pairing -> pairingSemaphore
        }
        return if (semaphore.tryAcquire()) RawHttpCapacityPermit(semaphore) else null
    }

    fun tryAcquireIdleDataKeepAlive(): RawHttpCapacityPermit? {
        if (maxIdleDataKeepAliveConnections <= 0) return null
        return if (idleDataKeepAliveSemaphore.tryAcquire()) {
            RawHttpCapacityPermit(idleDataKeepAliveSemaphore)
        } else {
            null
        }
    }

    internal fun canAcquireForTests(routeClass: RawHttpRouteClass): Boolean {
        val permit = tryAcquireForTests(routeClass)
        permit?.release()
        return permit != null
    }

    internal fun tryAcquireForTests(routeClass: RawHttpRouteClass): RawHttpCapacityPermit? {
        return tryAcquireRequestPermit(routeClass)
    }

    internal fun canAcquireIdleDataKeepAliveForTests(): Boolean {
        val permit = tryAcquireIdleDataKeepAliveForTests()
        permit?.release()
        return permit != null
    }

    internal fun tryAcquireIdleDataKeepAliveForTests(): RawHttpCapacityPermit? =
        tryAcquireIdleDataKeepAlive()
}

internal class RawHttpCapacityPermit(
    private val semaphore: Semaphore,
) {
    private var released = false

    fun release() {
        if (!released) {
            released = true
            semaphore.release()
        }
    }
}

internal const val DEFAULT_MAX_PAIRING_REQUESTS = 4

private val PAIRING_ROUTES = emptySet<String>()

private val DATA_ROUTES = setOf(
    "/api/files/read-bytes",
    "/api/files/stream-file",
    "/api/files/stream-upload",
)

private val STREAMING_REQUEST_BODY_ROUTES = setOf(
    "/api/files/write-bytes",
    "/api/files/stream-upload",
)

data class RawHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String> = emptyMap(),
    val body: RawHttpBody = RawHttpBody.Bytes(byteArrayOf()),
) {
    companion object {
        fun bytes(
            statusCode: Int,
            headers: Map<String, String> = emptyMap(),
            body: ByteArray = byteArrayOf(),
        ): RawHttpResponse = RawHttpResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase(statusCode),
            headers = headers,
            body = RawHttpBody.Bytes(body)
        )

        fun stream(
            statusCode: Int,
            headers: Map<String, String> = emptyMap(),
            contentLength: Long? = null,
            writer: suspend RawHttpResponseBodyWriter.() -> Unit,
        ): RawHttpResponse = RawHttpResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase(statusCode),
            headers = headers,
            body = RawHttpBody.Stream(contentLength, writer)
        )
    }
}

sealed class RawHttpBody {
    data class Bytes(val bytes: ByteArray) : RawHttpBody()
    data class Stream(
        val contentLength: Long?,
        val writer: suspend RawHttpResponseBodyWriter.() -> Unit,
    ) : RawHttpBody()
}

interface RawHttpResponseBodyWriter {
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    suspend fun flush()
}
