package com.folderspan.service.mcp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val MCP_JSON_RPC_VERSION: String = "2.0"
const val MCP_PROTOCOL_VERSION_2025_06_18: String = "2025-06-18"
const val MCP_PROTOCOL_VERSION_2025_11_25: String = "2025-11-25"
const val MCP_HEADER_PROTOCOL_VERSION: String = "MCP-Protocol-Version"
const val MCP_HEADER_SESSION_ID: String = "Mcp-Session-Id"

val McpSupportedProtocolVersions: List<String> = listOf(
    MCP_PROTOCOL_VERSION_2025_11_25,
    MCP_PROTOCOL_VERSION_2025_06_18,
)

/**
 * Shared strict-enough JSON configuration for the MCP boundary. Unknown fields remain accepted so
 * newer clients can add capabilities without breaking older FolderSpan builds.
 */
val McpJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = false
}

@Serializable
data class McpJsonRpcRequest(
    val jsonrpc: String = MCP_JSON_RPC_VERSION,
    val id: JsonElement,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpJsonRpcNotification(
    val jsonrpc: String = MCP_JSON_RPC_VERSION,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpJsonRpcResponse(
    val jsonrpc: String = MCP_JSON_RPC_VERSION,
    val id: JsonElement,
    val result: JsonElement,
)

@Serializable
data class McpJsonRpcErrorResponse(
    val jsonrpc: String = MCP_JSON_RPC_VERSION,
    val id: JsonElement,
    val error: McpJsonRpcError,
)

@Serializable
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

object McpJsonRpcErrorCodes {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603
}

@Serializable
data class McpImplementation(
    val name: String,
    val version: String,
    val title: String? = null,
)

@Serializable
data class McpInitializeParams(
    val protocolVersion: String,
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpImplementation,
)

@Serializable
data class McpClientCapabilities(
    val roots: JsonObject? = null,
    val sampling: JsonObject? = null,
    val elicitation: JsonObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpImplementation,
    val instructions: String? = null,
)

@Serializable
data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
    val logging: JsonObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
data class McpToolsCapability(
    val listChanged: Boolean = false,
)

@Serializable
data class McpListToolsParams(
    val cursor: String? = null,
)

@Serializable
data class McpListToolsResult(
    val tools: List<McpToolDefinition>,
    val nextCursor: String? = null,
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val annotations: McpToolAnnotations? = null,
)

@Serializable
data class McpToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
data class McpCallToolParams(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContent>,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
    @SerialName("_meta")
    val metadata: JsonObject? = null,
)
