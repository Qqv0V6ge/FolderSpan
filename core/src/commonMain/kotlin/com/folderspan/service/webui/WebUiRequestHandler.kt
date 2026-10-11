package com.folderspan.service.webui

import com.folderspan.exception.AuthorityException
import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.mcp.file.FileEndpointErrorCode
import com.folderspan.service.mcp.file.FileEndpointException
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WebUiRequestHandler(
    private val credentials: WebUiCredentialStore,
    private val sessions: WebUiSessionStore,
    private val securityPolicy: WebUiSecurityPolicy,
    private val documentRoot: WebUiDocumentRoot,
    private val platform: WebUiPlatform = createWebUiPlatform(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val resources = mutableMapOf<String, WebUiPlatformSession>()
    private var reaper: Job? = null

    fun start() {
        check(reaper == null)
        reaper = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(5_000)
                val active = sessions.snapshot().map { it.id }.toSet()
                val owned = mutex.withLock { resources.toMap() }
                for ((token, resource) in owned) {
                    if (token !in active || resource.expired()) release(token)
                }
            }
        }
    }

    suspend fun stop() {
        reaper?.cancelAndJoin()
        reaper = null
        sessions.clear()
        val owned = mutex.withLock { resources.values.toList().also { resources.clear() } }
        owned.forEach { it.close() }
    }

    internal suspend fun release(token: String) {
        sessions.remove(token)
        mutex.withLock { resources.remove(token) }?.close()
    }

    internal fun allowsSocket(request: LinkShareHttpRequest): Boolean =
        request.headers["host"]?.size == 1 && request.headers["origin"].orEmpty().size <= 1 &&
            securityPolicy.allows(request.header("Host"), request.header("Origin"), request.scheme)

    internal suspend fun attachSocket(token: String, version: Int): WebUiPlatformSession {
        require(version == WEB_UI_PROTOCOL_VERSION) { "incompatible management protocol" }
        val session = sessions.get(token, touch = false) ?: error("session expired")
        val owned = mutex.withLock { resources[session.id] } ?: error("session resources released")
        check(!owned.expired()) { "platform lease expired" }
        owned.attachSocket()
        return owned
    }

    internal suspend fun socketAlive(token: String): Boolean = sessions.get(token, touch = false) != null

    suspend fun handle(request: LinkShareHttpRequest): LinkShareHttpResponse = handleInternal(request, false)
    internal suspend fun handleCommand(request: LinkShareHttpRequest): LinkShareHttpResponse = handleInternal(request, true)

    private suspend fun handleInternal(request: LinkShareHttpRequest, viaSocket: Boolean): LinkShareHttpResponse {
        val management = request.path.startsWith("/api/web-ui/")
        if (request.headers["host"]?.size != 1 || request.headers["origin"].orEmpty().size > 1 ||
                !securityPolicy.allows(request.header("Host"), if (management) request.header("Origin") else null, request.scheme)) {
            return failure(403, "forbidden", "request host or origin is not allowed")
        }
        val response = try {
            when {
                request.method == "OPTIONS" && management -> preflight(request)
                request.method == "GET" && request.path == WEB_UI_BOOTSTRAP_PATH ->
                    LinkShareHttpResponse.text(text = """{"mode":"attached","protocolVersion":$WEB_UI_PROTOCOL_VERSION,"transport":"websocket"}""", contentType = "application/json")
                request.method == "POST" && request.path == WEB_UI_LOGIN_PATH -> login(request)
                request.path == WEB_UI_SESSION_PATH || request.path == WEB_UI_FILES_PATH ->
                    failure(426, "incompatible_protocol", "WebSocket platform protocol v$WEB_UI_PROTOCOL_VERSION is required; update Web and Runtime together")
                management -> authenticated(request, viaSocket)
                request.method == "GET" || request.method == "HEAD" -> staticAsset(request)
                else -> failure(404, "not_found", "not found")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: AuthorityException) {
            failure(403, "permission_denied", "path access denied")
        } catch (error: FileEndpointException) {
            val status = when (error.code) {
                FileEndpointErrorCode.InvalidArgument, FileEndpointErrorCode.Unsupported -> 400
                FileEndpointErrorCode.PermissionDenied -> 403
                FileEndpointErrorCode.NotFound -> 404
                FileEndpointErrorCode.NotConnected, FileEndpointErrorCode.Conflict -> 409
                else -> 500
            }
            val message = if (error.code == FileEndpointErrorCode.Conflict) {
                error.message ?: "file operation conflict"
            } else "platform operation failed"
            failure(status, error.code.wireValue, message)
        } catch (_: IllegalArgumentException) {
            failure(400, "invalid_argument", "invalid platform request")
        } catch (error: IllegalStateException) {
            failure(409, "resource_unavailable", error.message ?: "platform resource unavailable")
        } catch (error: Exception) {
            LogKit.e("Web platform request failed", error)
            failure(500, "platform_error", "platform operation failed")
        }
        val headers = securityHeaders() + if (management && request.header("Origin") != null) listOf(
            LinkShareHttpHeader("Access-Control-Allow-Origin", request.header("Origin")!!.trim()),
            LinkShareHttpHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length, X-FolderSpan-End, X-FolderSpan-Size"),
            LinkShareHttpHeader("Vary", "Origin"),
        ) else emptyList()
        return response.copy(headers = response.headers.filterNot { header ->
            (header.name.startsWith("Access-Control-", ignoreCase = true) &&
                !header.name.equals("Access-Control-Allow-Methods", true) && !header.name.equals("Access-Control-Allow-Headers", true)) ||
                headers.any { it.name.equals(header.name, true) }
        } + headers)
    }

    private suspend fun login(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val parsed = json.decodeFromString<WebUiLoginRequest>(readBytes(request, 16 * 1024).decodeToString())
        if (parsed.protocolVersion != WEB_UI_PROTOCOL_VERSION) return failure(426, "incompatible_protocol", "update Web and Runtime together")
        if (!credentials.matches(parsed.secret)) return failure(401, "unauthorized", "authentication failed")
        val session = try { sessions.create() } catch (_: WebUiSessionCapacityException) {
            return failure(429, "capacity", "session capacity reached")
        }
        val owned = WebUiPlatformSession(session.id)
        mutex.withLock { resources[session.id] = owned }
        return try {
            response(WebUiLoginResponse(sessionToken = session.id, host = platform.hostInfo(owned)))
        } catch (error: Throwable) {
            release(session.id)
            throw error
        }
    }

    private suspend fun authenticated(request: LinkShareHttpRequest, viaSocket: Boolean): LinkShareHttpResponse {
        if (request.headers[WEB_UI_SESSION_HEADER.lowercase()]?.size != 1) return failure(401, "unauthorized", "authentication failed")
        val token = request.header(WEB_UI_SESSION_HEADER)?.trim()
        val poll = request.path == WEB_UI_EVENTS_PATH && request.method == "GET"
        val session = sessions.get(token, touch = !poll) ?: return failure(401, "unauthorized", "session expired")
        if (request.header(WEB_UI_PROTOCOL_HEADER) != WEB_UI_PROTOCOL_VERSION.toString()) return failure(426, "incompatible_protocol", "update Web and Runtime together")
        val owned = mutex.withLock { resources[session.id] } ?: return failure(401, "unauthorized", "session resources released")
        if (owned.expired()) {
            release(session.id)
            return failure(401, "unauthorized", "platform lease expired")
        }
        return when {
            request.path == WEB_UI_LOGOUT_PATH && request.method == "POST" -> {
                release(session.id)
                response(true)
            }
            poll -> failure(426, "incompatible_protocol", "WebSocket management connection required")
            request.path == WEB_UI_EVENTS_PATH && request.method == "POST" && viaSocket -> {
                owned.reply(requireNotNull(request.query("id")?.toLongOrNull()), readBytes(request, WEB_UI_FILE_STREAM_CHUNK_BYTES), request.query("failed") == "true")
                response(true)
            }
            request.path.startsWith("$WEB_UI_PLATFORM_PATH/") -> {
                if (!viaSocket && !isWebUiBodyRoute(request.method, request.rawUri))
                    failure(426, "incompatible_protocol", "WebSocket platform command required")
                else owned.runOperation { platform.handle(request, owned) }
            }
            else -> failure(404, "not_found", "unknown platform operation")
        }
    }

    private fun preflight(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val methods = setOf("GET", "POST", "PUT", "DELETE")
        val allowed = setOf("content-type", WEB_UI_SESSION_HEADER.lowercase(), WEB_UI_PROTOCOL_HEADER.lowercase(), "range")
        val requested = request.header("Access-Control-Request-Headers").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (request.header("Origin") == null || request.headers["access-control-request-method"]?.size != 1 ||
            request.headers["access-control-request-headers"].orEmpty().size > 1 ||
            request.header("Access-Control-Request-Method") !in methods || requested.any { it !in allowed }) {
            return failure(403, "forbidden", "preflight method or headers are not allowed")
        }
        return LinkShareHttpResponse.bytes(statusCode = 204, headers = listOf(
            LinkShareHttpHeader("Access-Control-Allow-Methods", methods.joinToString(", ")),
            LinkShareHttpHeader("Access-Control-Allow-Headers", allowed.joinToString(", ")),
        ))
    }

    private fun staticAsset(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val file = documentRoot.get(request.path) ?: return LinkShareHttpResponse.bytes(statusCode = 404)
        return if (request.method == "HEAD") LinkShareHttpResponse(200, headers = listOf(
            LinkShareHttpHeader("Content-Type", file.contentType), LinkShareHttpHeader("Content-Length", file.bytes.size.toString()),
        )) else LinkShareHttpResponse.bytes(bytes = file.bytes, contentType = file.contentType)
    }

    private suspend fun readBytes(request: LinkShareHttpRequest, limit: Int): ByteArray {
        require(request.body.contentLength == null || request.body.contentLength!! in 0..limit.toLong())
        val bytes = ByteArray(limit + 1)
        var size = 0
        while (size <= limit) {
            val count = request.body.read(bytes, size, bytes.size - size)
            if (count < 0) return bytes.copyOf(size)
            size += count
        }
        throw IllegalArgumentException("request body too large")
    }

    private inline fun <reified T> response(value: T) = LinkShareHttpResponse.text(
        text = json.encodeToString(value), contentType = "application/json",
    )

    private fun failure(status: Int, code: String, message: String) = LinkShareHttpResponse.text(
        statusCode = status, text = json.encodeToString(WebUiJsonError(message, code)), contentType = "application/json",
        headers = securityHeaders(),
    )

    private fun securityHeaders() = listOf(
        LinkShareHttpHeader("Cache-Control", "no-store"),
        LinkShareHttpHeader("X-Content-Type-Options", "nosniff"),
    )
}
