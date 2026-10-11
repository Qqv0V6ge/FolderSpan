package com.folderspan.service.mcp.protocol

import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.tools.McpToolRegistry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class McpProtocolSession(
    val id: String,
    val tokenLookupId: String,
    var protocolVersion: String? = null,
    var initialized: Boolean = false,
    var lastAccessAt: Long,
)

data class McpTransportResult(
    val body: String?,
    val protocolVersion: String? = null,
)

class RawMcpStreamableHttpTransport(
    private val tools: McpToolRegistry,
    private val serverVersion: String,
) {
    suspend fun handle(
        body: String,
        scopes: Set<McpTokenScope>,
        session: McpProtocolSession? = null,
        stateless: Boolean = false,
    ): McpTransportResult {
        val root = try {
            McpJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return McpTransportResult(McpJson.encodeToString(error(JsonNull, McpJsonRpcErrorCodes.PARSE_ERROR, "Parse error")))
        } catch (_: IllegalArgumentException) {
            return McpTransportResult(McpJson.encodeToString(error(JsonNull, McpJsonRpcErrorCodes.PARSE_ERROR, "Parse error")))
        }

        val responses = when (root) {
            is JsonArray -> {
                if (root.isEmpty()) {
                    listOf(error(JsonNull, McpJsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request"))
                } else {
                    root.mapNotNull { item -> handleOne(item, scopes, session, stateless) }
                }
            }
            else -> listOfNotNull(handleOne(root, scopes, session, stateless))
        }
        val responseBody = when {
            responses.isEmpty() -> null
            root is JsonArray -> McpJson.encodeToString(JsonArray(responses))
            else -> McpJson.encodeToString(responses.first())
        }
        val negotiatedVersion = session?.protocolVersion ?: if (stateless) {
            requestedSupportedVersion(root)
        } else {
            null
        }
        return McpTransportResult(responseBody, negotiatedVersion)
    }

    private suspend fun handleOne(
        element: JsonElement,
        scopes: Set<McpTokenScope>,
        session: McpProtocolSession?,
        stateless: Boolean,
    ): JsonElement? {
        val request = element as? JsonObject
            ?: return error(JsonNull, McpJsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request")
        val id = request["id"]
        val isNotification = id == null
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        if (request["jsonrpc"]?.jsonPrimitive?.contentOrNull != MCP_JSON_RPC_VERSION || method.isNullOrBlank()) {
            return if (isNotification) null else error(id, McpJsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request")
        }
        val params = request["params"]
        if (params != null && params !is JsonObject) {
            return if (isNotification) null else error(id, McpJsonRpcErrorCodes.INVALID_PARAMS, "Invalid params")
        }

        if (isNotification) {
            if (method == "notifications/initialized" || method == "initialized") {
                session?.initialized = true
            }
            return null
        }

        val responseId = id
        return try {
            when (method) {
                "initialize" -> initialize(responseId, params, session)
                "ping" -> success(responseId, JsonObject(emptyMap()))
                "tools/list" -> {
                    requireReady(session, stateless)
                    val definitions = tools.list(scopes)
                    success(
                        responseId,
                        McpJson.encodeToJsonElement(
                            McpListToolsResult.serializer(),
                            McpListToolsResult(definitions),
                        ),
                    )
                }
                "tools/call" -> {
                    requireReady(session, stateless)
                    val call = decodeParams<McpCallToolParams>(params)
                    success(
                        responseId,
                        McpJson.encodeToJsonElement(
                            McpCallToolResult.serializer(),
                            tools.call(call.name, call.arguments, scopes),
                        ),
                    )
                }
                else -> error(responseId, McpJsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found")
            }
        } catch (failure: ProtocolFailure) {
            error(responseId, failure.code, failure.message ?: "Protocol error", failure.data)
        } catch (_: SerializationException) {
            error(responseId, McpJsonRpcErrorCodes.INVALID_PARAMS, "Invalid params")
        } catch (_: IllegalArgumentException) {
            error(responseId, McpJsonRpcErrorCodes.INVALID_PARAMS, "Invalid params")
        } catch (_: Throwable) {
            error(responseId, McpJsonRpcErrorCodes.INTERNAL_ERROR, "Internal error")
        }
    }

    private fun initialize(
        id: JsonElement,
        params: JsonObject?,
        session: McpProtocolSession?,
    ): JsonElement {
        val initialize = decodeParams<McpInitializeParams>(params)
        val requested = initialize.protocolVersion
        if (requested !in McpSupportedProtocolVersions) {
            throw ProtocolFailure(
                McpJsonRpcErrorCodes.INVALID_PARAMS,
                "Unsupported protocol version",
                buildJsonObject {
                    put("supported", JsonArray(McpSupportedProtocolVersions.map(::JsonPrimitive)))
                },
            )
        }
        session?.protocolVersion = requested
        session?.initialized = false
        return success(
            id,
            McpJson.encodeToJsonElement(
                McpInitializeResult.serializer(),
                McpInitializeResult(
                    protocolVersion = requested,
                    capabilities = McpServerCapabilities(tools = McpToolsCapability(listChanged = false)),
                    serverInfo = McpImplementation(
                        name = "folderspan",
                        title = "FolderSpan MCP",
                        version = serverVersion,
                    ),
                    instructions = "Use FolderSpan tools only within the scopes granted to this bearer token.",
                ),
            ),
        )
    }

    private fun requireReady(session: McpProtocolSession?, stateless: Boolean) {
        if (stateless) return
        if (session?.protocolVersion == null) {
            throw ProtocolFailure(McpJsonRpcErrorCodes.INVALID_REQUEST, "Session is not initialized")
        }
        if (!session.initialized) {
            throw ProtocolFailure(McpJsonRpcErrorCodes.INVALID_REQUEST, "Initialized notification is required")
        }
    }

    private inline fun <reified T> decodeParams(params: JsonObject?): T {
        if (params == null) throw ProtocolFailure(McpJsonRpcErrorCodes.INVALID_PARAMS, "Invalid params")
        return McpJson.decodeFromJsonElement(params)
    }

    private fun requestedSupportedVersion(root: JsonElement): String? {
        val requests = if (root is JsonArray) root else JsonArray(listOf(root))
        return requests.asSequence()
            .mapNotNull { item -> item as? JsonObject }
            .firstOrNull { item -> item["method"]?.jsonPrimitive?.contentOrNull == "initialize" }
            ?.get("params")
            ?.let { item -> item as? JsonObject }
            ?.get("protocolVersion")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { version -> version in McpSupportedProtocolVersions }
    }
}

private class ProtocolFailure(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
) : Exception(message)

private fun success(id: JsonElement, result: JsonElement): JsonElement = buildJsonObject {
    put("jsonrpc", MCP_JSON_RPC_VERSION)
    put("id", id)
    put("result", result)
}

private fun error(
    id: JsonElement,
    code: Int,
    message: String,
    data: JsonElement? = null,
): JsonElement = buildJsonObject {
    put("jsonrpc", MCP_JSON_RPC_VERSION)
    put("id", id)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
        data?.let { value -> put("data", value) }
    })
}
