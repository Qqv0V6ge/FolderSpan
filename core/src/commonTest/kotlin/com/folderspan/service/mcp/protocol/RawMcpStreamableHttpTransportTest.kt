package com.folderspan.service.mcp.protocol

import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.tools.McpRegisteredTool
import com.folderspan.service.mcp.tools.McpToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawMcpStreamableHttpTransportTest {
    private val tool = McpRegisteredTool(
        definition = McpToolDefinition(
            name = "folderspan_test_echo",
            description = "Echo test input.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
            },
        ),
        scope = McpTokenScope.FilesRead,
        handler = { arguments -> arguments },
    )
    private val transport = RawMcpStreamableHttpTransport(
        tools = McpToolRegistry(tools = listOf(tool)),
        serverVersion = "test",
    )

    @Test
    fun statefulInitializeSupportsBothVersionsAndRequiresInitializedNotification() = runTest {
        McpSupportedProtocolVersions.forEach { version ->
            val session = McpProtocolSession("session", "token", lastAccessAt = 1L)
            val initialized = transport.handle(initializeRequest(version), setOf(McpTokenScope.FilesRead), session)

            assertEquals(version, initialized.protocolVersion)
            assertEquals(version, session.protocolVersion)
            assertFalse(session.initialized)
            assertEquals(version, result(initialized.body)["protocolVersion"]?.jsonPrimitive?.content)

            val beforeNotification = transport.handle(pingOrTools("tools/list"), setOf(McpTokenScope.FilesRead), session)
            assertEquals(McpJsonRpcErrorCodes.INVALID_REQUEST, errorCode(beforeNotification.body))

            val notification = transport.handle(
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                setOf(McpTokenScope.FilesRead),
                session,
            )
            assertNull(notification.body)
            assertTrue(session.initialized)

            val tools = result(transport.handle(pingOrTools("tools/list"), setOf(McpTokenScope.FilesRead), session).body)
            assertEquals("folderspan_test_echo", tools["tools"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun statelessEndpointFiltersScopesAndReturnsBatchShape() = runTest {
        val hidden = transport.handle(pingOrTools("tools/list"), emptySet(), stateless = true)
        assertTrue(result(hidden.body)["tools"]?.jsonArray?.isEmpty() == true)

        val batch = transport.handle(
            """
            [
              {"jsonrpc":"2.0","method":"notifications/initialized"},
              {"jsonrpc":"2.0","id":2,"method":"ping"}
            ]
            """.trimIndent(),
            setOf(McpTokenScope.FilesRead),
            stateless = true,
        )
        val root = McpJson.parseToJsonElement(assertNotNull(batch.body))
        assertTrue(root is JsonArray)
        assertEquals(1, root.jsonArray.size)
    }

    @Test
    fun invalidJsonUnknownMethodsAndInvalidToolArgumentsUseStableErrors() = runTest {
        assertEquals(
            McpJsonRpcErrorCodes.PARSE_ERROR,
            errorCode(transport.handle("{", emptySet(), stateless = true).body),
        )
        assertEquals(
            McpJsonRpcErrorCodes.METHOD_NOT_FOUND,
            errorCode(transport.handle(pingOrTools("missing"), emptySet(), stateless = true).body),
        )

        val denied = transport.handle(
            """
            {
              "jsonrpc":"2.0",
              "id":3,
              "method":"tools/call",
              "params":{"name":"folderspan_test_echo","arguments":{}}
            }
            """.trimIndent(),
            emptySet(),
            stateless = true,
        )
        val callResult = result(denied.body)
        assertTrue(callResult["isError"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals(
            "permission_denied",
            callResult["structuredContent"]?.jsonObject?.get("code")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun statelessInitializeReturnsNegotiatedProtocolVersion() = runTest {
        val version = MCP_PROTOCOL_VERSION_2025_11_25
        val response = transport.handle(initializeRequest(version), emptySet(), stateless = true)

        assertEquals(version, response.protocolVersion)
        assertEquals(version, result(response.body)["protocolVersion"]?.jsonPrimitive?.content)
    }

    private fun initializeRequest(version: String): String =
        """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"initialize",
          "params":{
            "protocolVersion":"$version",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1"}
          }
        }
        """.trimIndent()

    private fun pingOrTools(method: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"$method","params":{}}"""

    private fun result(body: String?): JsonObject =
        McpJson.parseToJsonElement(assertNotNull(body)).jsonObject["result"]!!.jsonObject

    private fun errorCode(body: String?): Int =
        McpJson.parseToJsonElement(assertNotNull(body))
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content.toInt()
}
