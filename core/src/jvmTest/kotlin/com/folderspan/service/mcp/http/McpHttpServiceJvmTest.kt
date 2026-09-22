package com.folderspan.service.mcp.http

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.mcp.McpServerSettings
import com.folderspan.service.mcp.auth.McpTokenRepository
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.protocol.McpToolDefinition
import com.folderspan.service.mcp.protocol.RawMcpStreamableHttpTransport
import com.folderspan.service.mcp.tools.McpRegisteredTool
import com.folderspan.service.mcp.tools.McpToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHttpServiceJvmTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val repository = McpTokenRepository(createMcpHttpTestDatabase(driver))
    private var service: McpHttpService? = null

    @AfterTest
    fun cleanup() {
        service?.let { current ->
            kotlinx.coroutines.runBlocking { current.stop() }
        }
        driver.close()
    }

    @Test
    fun defaultBindIsLoopbackOnly() = runTest {
        val lanHost = getAllIPAddresses(SocketClientIPEnum.IPV4_UP).firstOrNull()
            ?: return@runTest
        val token = repository.create("loopback test", setOf(McpTokenScope.FilesRead)).token
        val current = McpHttpService(createHandler(setOf(lanHost, "localhost", "127.0.0.1"))).also { service = it }
        val port = freePort()

        val status = current.start(McpServerSettings(enabled = true, port = port))
        assertTrue(status.running, status.errorMessage)
        assertTrue(status.endpoints.any { endpoint -> endpoint.host == "127.0.0.1" })
        assertFalse(status.endpoints.any { endpoint -> endpoint.host == lanHost })

        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertTrue(postJson("http://127.0.0.1:$port/mcp/stateless", token, body).contains("\"result\""))
        val lanError = runCatching { postJson("http://$lanHost:$port/mcp/stateless", token, body) }.exceptionOrNull()
        assertTrue(
            lanError is ConnectException || lanError is NoRouteToHostException || lanError is SocketTimeoutException,
            lanError?.toString(),
        )
    }

    @Test
    fun httpAndHttpsAreReachableThroughNonLoopbackLanAddressWhenEnabled() = runTest {
        val lanHost = firstSelfReachableLanHost() ?: return@runTest
        val token = repository.create("LAN test", setOf(McpTokenScope.FilesRead)).token
        val current = McpHttpService(createHandler(setOf(lanHost, "localhost", "127.0.0.1"))).also { service = it }
        val port = freePort()

        val status = current.start(McpServerSettings(enabled = true, port = port, lanAccess = true))
        assertTrue(status.running, status.errorMessage)
        assertTrue(status.endpoints.any { endpoint -> endpoint.host == lanHost && endpoint.scheme == "https" })
        assertTrue(status.endpoints.none { endpoint -> endpoint.host == lanHost && endpoint.scheme == "http" })

        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val lanHttps = runCatching { postJson("https://$lanHost:$port/mcp/stateless", token, body) }
        assertTrue(lanHttps.isSuccess, "$lanHost: ${lanHttps.exceptionOrNull()}")
        assertTrue(lanHttps.getOrThrow().contains("\"result\""))
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(lanHost, port), 5_000)
                socket.soTimeout = 5_000
                // Verify transport rejection directly, without HttpURLConnection retries.
                val response = runCatching {
                    val request = "POST /mcp/stateless HTTP/1.1\r\n" +
                        "Host: $lanHost:$port\r\nAuthorization: Bearer $token\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${body.encodeToByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    socket.getOutputStream().write(request.encodeToByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().read()
                }
                assertTrue(
                    response.getOrNull() == -1 || response.exceptionOrNull() is SocketException,
                    "Plain HTTP must be closed without a response: $response",
                )
            }
        }

        val stopped = current.stop()
        assertFalse(stopped.running)
    }

    @Test
    fun occupiedPortReturnsRecoverableStatusAndRestartIsIdempotent() = runTest {
        val current = McpHttpService(createHandler(setOf("localhost", "127.0.0.1"))).also { service = it }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val failed = current.start(McpServerSettings(enabled = true, port = occupied.localPort))
            assertFalse(failed.running)
            assertEquals("port_in_use", failed.errorCode)
        }

        val settings = McpServerSettings(enabled = true, port = freePort())
        assertTrue(current.start(settings).running)
        assertTrue(current.start(settings).running)
        assertTrue(current.restart(settings).running)

        val oversizedResponse = rawHeaderRequest(
            host = "127.0.0.1",
            port = settings.port,
            contentLength = McpHttpRequestHandler.MAX_REQUEST_BODY_BYTES + 1,
        )
        assertTrue(oversizedResponse.startsWith("HTTP/1.1 413"), oversizedResponse)
        assertTrue(oversizedResponse.contains("application/json; charset=utf-8"))
    }

    private fun createHandler(hosts: Set<String>): McpHttpRequestHandler {
        val registry = McpToolRegistry(
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
                )
            )
        )
        return McpHttpRequestHandler(
            tokenRepository = repository,
            transport = RawMcpStreamableHttpTransport(registry, "test"),
            securityPolicy = McpHttpSecurityPolicy(
                allowedHostProvider = McpAllowedHostProvider { hosts },
            ),
        )
    }

    private fun firstSelfReachableLanHost(): String? =
        getAllIPAddresses(SocketClientIPEnum.IPV4_UP).firstOrNull { address ->
            runCatching {
                ServerSocket(0, 1, InetAddress.getByName("0.0.0.0")).use { listener ->
                    val acceptor = thread(isDaemon = true, name = "mcp-lan-self-probe") {
                        runCatching {
                            listener.accept().use { accepted ->
                                accepted.getOutputStream().write(1)
                            }
                        }
                    }
                    Socket().use { client ->
                        client.soTimeout = 250
                        client.connect(InetSocketAddress(address, listener.localPort), 250)
                        check(client.getInputStream().read() == 1)
                    }
                    acceptor.join(250)
                }
            }.isSuccess
        }
}

private suspend fun rawHeaderRequest(host: String, port: Int, contentLength: Int): String = withContext(Dispatchers.IO) {
    Socket(host, port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "POST /mcp/stateless HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n"
                .encodeToByteArray(),
        )
        socket.getInputStream().bufferedReader().use { input -> input.readText() }
    }
}

private fun freePort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("0.0.0.0")).use { socket -> socket.localPort }

private suspend fun postJson(url: String, token: String, body: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    if (connection is HttpsURLConnection) {
        connection.sslSocketFactory = trustAllSslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
    connection.requestMethod = "POST"
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { output -> output.write(body.encodeToByteArray()) }
    assertEquals(200, connection.responseCode)
    connection.inputStream.bufferedReader().use { input -> input.readText() }
}

private val trustAllSslContext: SSLContext by lazy {
    val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }
    SSLContext.getInstance("TLS").also { context ->
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}
