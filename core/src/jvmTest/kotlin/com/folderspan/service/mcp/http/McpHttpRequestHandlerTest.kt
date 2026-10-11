package com.folderspan.service.mcp.http

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.Device
import com.folderspan.db.DeviceConnect
import com.folderspan.db.DeviceReceiveShare
import com.folderspan.db.FileBookmark
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileFilter
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequestBody
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import com.folderspan.service.mcp.auth.McpTokenRepository
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.protocol.MCP_HEADER_PROTOCOL_VERSION
import com.folderspan.service.mcp.protocol.MCP_HEADER_SESSION_ID
import com.folderspan.service.mcp.protocol.MCP_PROTOCOL_VERSION_2025_11_25
import com.folderspan.service.mcp.protocol.McpToolDefinition
import com.folderspan.service.mcp.protocol.RawMcpStreamableHttpTransport
import com.folderspan.service.mcp.tools.McpRegisteredTool
import com.folderspan.service.mcp.tools.McpToolRegistry
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpHttpRequestHandlerTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val repository = McpTokenRepository(createMcpHttpTestDatabase(driver))
    private val registry = McpToolRegistry(
        tools = listOf(
            McpRegisteredTool(
                definition = McpToolDefinition(
                    name = "folderspan_test",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                    },
                ),
                scope = McpTokenScope.FilesRead,
                handler = { buildJsonObject { put("ok", true) } },
            ),
            McpRegisteredTool(
                definition = McpToolDefinition(
                    name = "folderspan_secret_failure",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                    },
                ),
                scope = McpTokenScope.FilesRead,
                handler = { error("password=must-not-leak") },
            ),
        )
    )
    private val handler = McpHttpRequestHandler(
        tokenRepository = repository,
        transport = RawMcpStreamableHttpTransport(registry, "test"),
            securityPolicy = McpHttpSecurityPolicy(
                allowedHostProvider = McpAllowedHostProvider { setOf("192.168.1.20") },
            ),
    )

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun authenticationHostOriginMethodsAndRequestLimitAreEnforced() = runTest {
        val token = repository.create("test", setOf(McpTokenScope.FilesRead)).token

        val missing = handler.handle(request("POST", "/mcp/stateless", null, "{}"))
        assertEquals(401, missing.statusCode)
        assertTrue(missing.header("WWW-Authenticate")?.startsWith("Bearer") == true)
        assertEquals(
            "Bearer token is required",
            Json.parseToJsonElement(missing.bodyText()).jsonObject["error"]?.jsonPrimitive?.content,
        )
        assertEquals(401, handler.handle(request("POST", "/mcp/stateless", "fmcp_invalid_token", "{}")).statusCode)

        val wrongHost = handler.handle(request("POST", "/mcp/stateless", token, "{}", host = "attacker.example"))
        assertEquals(403, wrongHost.statusCode)
        val wrongOrigin = handler.handle(
            request("POST", "/mcp/stateless", token, "{}", origin = "https://attacker.example")
        )
        assertEquals(403, wrongOrigin.statusCode)

        val method = handler.handle(request("GET", "/mcp/stateless", token, null))
        assertEquals(405, method.statusCode)
        assertEquals("POST", method.header("Allow"))

        val oversized = handler.handle(
            request(
                method = "POST",
                path = "/mcp/stateless",
                token = token,
                body = "x".repeat(McpHttpRequestHandler.MAX_REQUEST_BODY_BYTES + 1),
            )
        )
        assertEquals(413, oversized.statusCode)

        repository.setEnabled(repository.list().single().lookupId, false)
        assertEquals(401, handler.handle(request("POST", "/mcp/stateless", token, "{}")).statusCode)
    }

    @Test
    fun scopesFilterToolDiscoveryAndToolFailuresDoNotLeakSecrets() = runTest {
        val restricted = repository.create("restricted", setOf(McpTokenScope.BookmarksRead)).token
        val restrictedList = handler.handle(request("POST", "/mcp/stateless", restricted, toolsListBody()))
        assertEquals(200, restrictedList.statusCode)
        assertFalse(restrictedList.bodyText().contains("folderspan_test"))

        val denied = handler.handle(
            request("POST", "/mcp/stateless", restricted, toolCallBody("folderspan_test")),
        )
        assertTrue(denied.bodyText().contains("permission_denied"))

        val allowed = repository.create("allowed", setOf(McpTokenScope.FilesRead)).token
        val failed = handler.handle(
            request("POST", "/mcp/stateless", allowed, toolCallBody("folderspan_secret_failure")),
        )
        assertTrue(failed.bodyText().contains("internal_error"))
        assertFalse(failed.bodyText().contains("must-not-leak"))
    }

    @Test
    fun statelessAndStatefulLifecycleReturnProtocolHeadersAndDeleteSessions() = runTest {
        val token = repository.create("test", setOf(McpTokenScope.FilesRead)).token
        val initialize = initializeBody()

        val stateless = handler.handle(request("POST", "/mcp/stateless", token, initialize))
        assertEquals(200, stateless.statusCode)
        assertEquals("application/json; charset=utf-8", stateless.header("Content-Type"))
        assertEquals(MCP_PROTOCOL_VERSION_2025_11_25, stateless.header(MCP_HEADER_PROTOCOL_VERSION))
        assertNull(stateless.header(MCP_HEADER_SESSION_ID))

        val initialized = handler.handle(request("POST", "/mcp", token, initialize))
        assertEquals(200, initialized.statusCode)
        val sessionId = assertNotNull(initialized.header(MCP_HEADER_SESSION_ID))
        assertEquals(MCP_PROTOCOL_VERSION_2025_11_25, initialized.header(MCP_HEADER_PROTOCOL_VERSION))

        val beforeNotification = handler.handle(
            request("POST", "/mcp", token, toolsListBody(), sessionId = sessionId)
        )
        assertEquals(200, beforeNotification.statusCode)
        assertTrue(beforeNotification.bodyText().contains("-32600"))

        val notification = handler.handle(
            request(
                "POST",
                "/mcp",
                token,
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                sessionId,
            )
        )
        assertEquals(202, notification.statusCode)

        val list = handler.handle(request("POST", "/mcp", token, toolsListBody(), sessionId = sessionId))
        assertEquals(200, list.statusCode)
        assertTrue(list.bodyText().contains("folderspan_test"))

        val sse = handler.handle(request("GET", "/mcp", token, null, sessionId = sessionId))
        assertEquals(200, sse.statusCode)
        assertEquals("text/event-stream", sse.header("Content-Type"))
        assertTrue(sse.body is LinkShareHttpResponseBody.Stream)

        val deleted = handler.handle(request("DELETE", "/mcp", token, null, sessionId = sessionId))
        assertEquals(200, deleted.statusCode)
        assertEquals(404, handler.handle(request("GET", "/mcp", token, null, sessionId = sessionId)).statusCode)
    }

    @Test
    fun malformedStatefulRequestDoesNotAllocateReusableSession() = runTest {
        val token = repository.create("test", setOf(McpTokenScope.FilesRead)).token
        val malformed = handler.handle(request("POST", "/mcp", token, "{"))

        assertEquals(200, malformed.statusCode)
        assertNull(malformed.header(MCP_HEADER_SESSION_ID))
        assertTrue(malformed.bodyText().contains("-32700"))
    }

    private fun request(
        method: String,
        path: String,
        token: String?,
        body: String?,
        sessionId: String? = null,
        host: String = "192.168.1.20:52137",
        origin: String? = null,
    ): LinkShareHttpRequest {
        val headers = buildMap<String, List<String>> {
            put("Host", listOf(host))
            token?.let { put("Authorization", listOf("Bearer $it")) }
            body?.let { put("Content-Type", listOf("application/json")) }
            sessionId?.let { put(MCP_HEADER_SESSION_ID, listOf(it)) }
            origin?.let { put("Origin", listOf(it)) }
        }
        return LinkShareHttpRequest.from(
            method = method,
            rawUri = path,
            headers = headers,
            body = body?.encodeToByteArray()?.let { bytes -> LinkShareHttpRequestBody.Bytes(bytes) }
                ?: LinkShareHttpRequestBody.Empty,
        )
    }

    private fun initializeBody(): String =
        """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"initialize",
          "params":{
            "protocolVersion":"$MCP_PROTOCOL_VERSION_2025_11_25",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1"}
          }
        }
        """.trimIndent()

    private fun toolsListBody(): String =
        """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""

    private fun toolCallBody(name: String): String =
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$name","arguments":{}}}"""
}

private fun LinkShareHttpResponse.bodyText(): String =
    ((body as? LinkShareHttpResponseBody.Bytes)?.bytes ?: byteArrayOf()).decodeToString()

internal fun createMcpHttpTestDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase {
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    val fileProtocolAdapter = enumAdapter<FileProtocol>()
    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(typeAdapter = enumAdapter<DeviceType>()),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = enumAdapter<DeviceConnectType>(),
            categoryAdapter = enumAdapter<DeviceCategory>(),
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = enumAdapter<DrawerBookmarkType>(),
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
        FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = enumAdapter<FileFilterType>(),
            extensionsAdapter = stringListAdapter,
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = enumAdapter<DeviceConnectType>(),
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = enumAdapter<FileFilterSort>(),
            ignoreFilesAdapter = stringListAdapter,
        ),
    )
}

private inline fun <reified T : Enum<T>> enumAdapter() = object : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
    override fun encode(value: T): String = value.name
}

private val stringListAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.takeIf(String::isNotEmpty)?.split(',') ?: emptyList()

    override fun encode(value: List<String>): String = value.joinToString(",")
}
