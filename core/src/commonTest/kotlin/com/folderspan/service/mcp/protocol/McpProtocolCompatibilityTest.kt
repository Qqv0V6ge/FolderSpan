package com.folderspan.service.mcp.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpProtocolCompatibilityTest {
    @Test
    fun initializeRequestMatchesProtocolVector() {
        val vector = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"initialize",
              "params":{
                "protocolVersion":"2025-11-25",
                "capabilities":{},
                "clientInfo":{"name":"contract-client","version":"1.0.0"}
              }
            }
        """.trimIndent()

        val request = McpJson.decodeFromString<McpJsonRpcRequest>(vector)
        val params = McpJson.decodeFromJsonElement(McpInitializeParams.serializer(), request.params!!)

        assertEquals(MCP_JSON_RPC_VERSION, request.jsonrpc)
        assertEquals(JsonPrimitive(1), request.id)
        assertEquals("initialize", request.method)
        assertEquals(MCP_PROTOCOL_VERSION_2025_11_25, params.protocolVersion)
        assertEquals("contract-client", params.clientInfo.name)
    }

    @Test
    fun initializeResultUsesExpectedCapabilityShape() {
        val response = McpJsonRpcResponse(
            id = JsonPrimitive("init-1"),
            result = McpJson.encodeToJsonElement(
                McpInitializeResult.serializer(),
                McpInitializeResult(
                    protocolVersion = MCP_PROTOCOL_VERSION_2025_06_18,
                    capabilities = McpServerCapabilities(tools = McpToolsCapability(listChanged = false)),
                    serverInfo = McpImplementation(name = "folderspan", version = "1.0.0"),
                ),
            ),
        )

        val encoded = McpJson.encodeToString(response)
        val result = McpJson.parseToJsonElement(encoded).jsonObject["result"]!!.jsonObject

        assertEquals(MCP_PROTOCOL_VERSION_2025_06_18, result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("folderspan", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
    }

    @Test
    fun notificationVectorDoesNotAcquireAnId() {
        val vector = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val notification = McpJson.decodeFromString<McpJsonRpcNotification>(vector)
        val encoded = McpJson.encodeToString(notification)

        assertEquals("notifications/initialized", notification.method)
        assertFalse("\"id\"" in encoded)
        assertNull(notification.params)
    }

    @Test
    fun toolVectorsPreserveSchemasArgumentsAndStructuredContent() {
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }
        val toolsResult = McpListToolsResult(
            tools = listOf(
                McpToolDefinition(
                    name = "folderspan_file_info",
                    description = "Return file metadata",
                    inputSchema = schema,
                    annotations = McpToolAnnotations(readOnlyHint = true),
                ),
            ),
        )
        val call = McpCallToolParams(
            name = "folderspan_file_info",
            arguments = buildJsonObject { put("path", "/Documents") },
        )
        val result = McpCallToolResult(
            content = listOf(McpContent(type = "text", text = "ok")),
            structuredContent = buildJsonObject { put("path", "/Documents") },
        )

        val decodedTools = McpJson.decodeFromString<McpListToolsResult>(McpJson.encodeToString(toolsResult))
        val decodedCall = McpJson.decodeFromString<McpCallToolParams>(McpJson.encodeToString(call))
        val decodedResult = McpJson.decodeFromString<McpCallToolResult>(McpJson.encodeToString(result))

        assertEquals("folderspan_file_info", decodedTools.tools.single().name)
        assertEquals("/Documents", decodedCall.arguments["path"]!!.jsonPrimitive.content)
        assertEquals("ok", decodedResult.content.single().text)
        assertEquals("/Documents", decodedResult.structuredContent!!["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun standardErrorVectorRoundTrips() {
        val response = McpJsonRpcErrorResponse(
            id = JsonPrimitive(7),
            error = McpJsonRpcError(
                code = McpJsonRpcErrorCodes.INVALID_PARAMS,
                message = "Invalid params",
                data = buildJsonObject { put("code", "invalid_arguments") },
            ),
        )

        val decoded = McpJson.decodeFromString<McpJsonRpcErrorResponse>(McpJson.encodeToString(response))

        assertEquals(-32602, decoded.error.code)
        assertEquals("invalid_arguments", decoded.error.data!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
