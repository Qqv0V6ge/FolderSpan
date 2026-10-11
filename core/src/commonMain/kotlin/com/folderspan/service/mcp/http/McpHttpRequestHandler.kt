package com.folderspan.service.mcp.http

import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.mcp.auth.McpTokenPrincipal
import com.folderspan.service.mcp.auth.McpTokenRepository
import com.folderspan.service.mcp.protocol.MCP_HEADER_PROTOCOL_VERSION
import com.folderspan.service.mcp.protocol.MCP_HEADER_SESSION_ID
import com.folderspan.service.mcp.protocol.RawMcpStreamableHttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

class McpHttpRequestHandler(
    private val tokenRepository: McpTokenRepository,
    private val transport: RawMcpStreamableHttpTransport,
    private val securityPolicy: McpHttpSecurityPolicy,
    private val sessions: McpHttpSessionStore = McpHttpSessionStore(),
) {
    private val toolCapacity = Semaphore(MAX_CONCURRENT_TOOL_REQUESTS)
    private val sseCapacity = Semaphore(MAX_CONCURRENT_SSE_STREAMS)

    suspend fun handle(request: LinkShareHttpRequest): LinkShareHttpResponse {
        if (request.path != MCP_STATEFUL_PATH && request.path != MCP_STATELESS_PATH) {
            return response(404)
        }
        if (!securityPolicy.allows(request.header("Host"), request.header("Origin"))) {
            return response(403, "request host or origin is not allowed")
        }
        val principal = authenticate(request) ?: return response(
            status = 401,
            message = "Bearer token is required",
            extraHeaders = listOf(LinkShareHttpHeader("WWW-Authenticate", "Bearer realm=\"FolderSpan MCP\"")),
        )
        return if (request.path == MCP_STATELESS_PATH) {
            handleStateless(request, principal)
        } else {
            handleStateful(request, principal)
        }
    }

    suspend fun clearSessions() = sessions.clear()

    private suspend fun handleStateless(
        request: LinkShareHttpRequest,
        principal: McpTokenPrincipal,
    ): LinkShareHttpResponse {
        if (!request.method.equals("POST", ignoreCase = true)) {
            return methodNotAllowed("POST")
        }
        return handlePost(request, principal, session = null, stateless = true)
    }

    private suspend fun handleStateful(
        request: LinkShareHttpRequest,
        principal: McpTokenPrincipal,
    ): LinkShareHttpResponse = when (request.method.uppercase()) {
        "POST" -> {
            val requestedId = request.header(MCP_HEADER_SESSION_ID)
            val existing = requestedId?.let { id -> sessions.get(id, principal.lookupId) }
            if (requestedId != null && existing == null) return response(404, "MCP session was not found")
            val session = existing ?: try {
                sessions.create(principal.lookupId)
            } catch (_: McpHttpCapacityException) {
                return response(429, "MCP session capacity reached")
            }
            val result = handlePost(request, principal, session, stateless = false)
            if (existing == null && session.protocolVersion == null) {
                sessions.remove(session.id, principal.lookupId)
            }
            result
        }
        "GET" -> handleSse(request, principal)
        "DELETE" -> {
            val id = request.header(MCP_HEADER_SESSION_ID) ?: return response(400, "MCP session ID is required")
            if (!sessions.remove(id, principal.lookupId)) response(404, "MCP session was not found")
            else response(200, extraHeaders = listOf(LinkShareHttpHeader(MCP_HEADER_SESSION_ID, id)))
        }
        else -> methodNotAllowed("POST, GET, DELETE")
    }

    private suspend fun handlePost(
        request: LinkShareHttpRequest,
        principal: McpTokenPrincipal,
        session: com.folderspan.service.mcp.protocol.McpProtocolSession?,
        stateless: Boolean,
    ): LinkShareHttpResponse {
        val contentType = request.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/json") return response(415, "Content-Type must be application/json")
        val body = readBody(request) ?: return response(413, "request body exceeds 1 MiB")
        if (!toolCapacity.tryAcquire()) return response(429, "too many concurrent MCP requests")
        return try {
            val result = transport.handle(body.decodeToString(), principal.scopes, session, stateless)
            val headers = buildList {
                session?.takeIf { item -> item.protocolVersion != null }?.let { item ->
                    add(LinkShareHttpHeader(MCP_HEADER_SESSION_ID, item.id))
                }
                (result.protocolVersion ?: session?.protocolVersion)?.let { version ->
                    add(LinkShareHttpHeader(MCP_HEADER_PROTOCOL_VERSION, version))
                }
            }
            if (result.body == null) response(202, extraHeaders = headers)
            else response(200, result.body, headers, rawJson = true)
        } finally {
            toolCapacity.release()
        }
    }

    private suspend fun handleSse(
        request: LinkShareHttpRequest,
        principal: McpTokenPrincipal,
    ): LinkShareHttpResponse {
        val id = request.header(MCP_HEADER_SESSION_ID) ?: return response(400, "MCP session ID is required")
        val session = sessions.get(id, principal.lookupId) ?: return response(404, "MCP session was not found")
        if (!sseCapacity.tryAcquire()) return response(429, "too many SSE streams")
        return LinkShareHttpResponse.stream(
            statusCode = 200,
            headers = listOf(
                LinkShareHttpHeader("Content-Type", "text/event-stream"),
                LinkShareHttpHeader("Cache-Control", "no-cache, no-transform"),
                LinkShareHttpHeader("Connection", "keep-alive"),
                LinkShareHttpHeader(MCP_HEADER_SESSION_ID, id),
                LinkShareHttpHeader(MCP_HEADER_PROTOCOL_VERSION, session.protocolVersion.orEmpty()),
            ),
        ) {
            try {
                write(": connected\n\n".encodeToByteArray())
                flush()
                while (sessions.contains(id, principal.lookupId)) {
                    delay(SSE_KEEPALIVE_SECONDS.seconds)
                    if (!sessions.contains(id, principal.lookupId)) break
                    write(": keepalive\n\n".encodeToByteArray())
                    flush()
                }
            } finally {
                sseCapacity.release()
            }
        }
    }

    private suspend fun authenticate(request: LinkShareHttpRequest): McpTokenPrincipal? {
        val authorization = request.header("Authorization") ?: return null
        val parts = authorization.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return tokenRepository.authenticate(parts[1])
    }

    private suspend fun readBody(request: LinkShareHttpRequest): ByteArray? {
        val declared = request.body.contentLength
        if (declared != null && declared > MAX_REQUEST_BODY_BYTES) return null
        val output = ByteArray(MAX_REQUEST_BODY_BYTES)
        var size = 0
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = request.body.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            if (size + count > MAX_REQUEST_BODY_BYTES) return null
            buffer.copyInto(output, destinationOffset = size, endIndex = count)
            size += count
        }
        return output.copyOf(size)
    }

    private fun methodNotAllowed(allow: String): LinkShareHttpResponse = response(
        status = 405,
        message = "method not allowed",
        extraHeaders = listOf(LinkShareHttpHeader("Allow", allow)),
    )

    private fun response(
        status: Int,
        message: String = "",
        extraHeaders: List<LinkShareHttpHeader> = emptyList(),
        rawJson: Boolean = false,
    ): LinkShareHttpResponse = LinkShareHttpResponse.bytes(
        statusCode = status,
        headers = listOf(
            LinkShareHttpHeader("Content-Type", "application/json; charset=utf-8"),
            LinkShareHttpHeader("Cache-Control", "no-store"),
            LinkShareHttpHeader("X-Content-Type-Options", "nosniff"),
        ) + extraHeaders,
        bytes = when {
            message.isEmpty() -> byteArrayOf()
            rawJson -> message.encodeToByteArray()
            else -> buildJsonObject { put("error", message) }.toString().encodeToByteArray()
        },
    )

    companion object {
        const val MCP_STATEFUL_PATH = "/mcp"
        const val MCP_STATELESS_PATH = "/mcp/stateless"
        const val MAX_REQUEST_BODY_BYTES = 1024 * 1024
        const val MAX_CONCURRENT_TOOL_REQUESTS = 16
        const val MAX_CONCURRENT_SSE_STREAMS = 16
        const val SSE_KEEPALIVE_SECONDS = 15
    }
}
