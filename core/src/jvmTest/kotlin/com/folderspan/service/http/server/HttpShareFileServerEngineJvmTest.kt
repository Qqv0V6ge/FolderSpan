package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.ui.state.file.FileShareState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.BindException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HttpShareFileServerEngineJvmTest {
    @Test
    fun linkShareServerAcceptsLanTcpNotOnlyLoopback() = runBlocking {
        val lanHost = getAllIPAddresses(SocketClientIPEnum.ALL)
            .firstOrNull { address ->
                ':' !in address && address != "0.0.0.0" && !address.startsWith("127.")
            }
            ?: return@runBlocking
        val port = allocateLocalPort()
        val server = HttpShareFileServer.getInstance(FileShareState())

        try {
            server.start(port)
            assertTrue(server.isRunning())
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                assertTrue(socket.isConnected)
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(lanHost, port), 2_000)
                assertTrue(socket.isConnected, "link share must listen on LAN, host=$lanHost port=$port")
            }
        } catch (error: ConnectException) {
            throw AssertionError("link share LAN bind failed: $lanHost:$port", error)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rawServerStartsWithHttpAndHttpsListeners() {
        val httpPort = allocateLocalPort()
        val httpsPort = allocateLocalPort(httpPort)
        val http = healthServer("http")
        val https = healthServer("https")

        try {
            http.start(httpPort)
            https.start(httpsPort, tls = true)

            assertEquals("ok:http", URI("http://127.0.0.1:$httpPort/health").toURL().readText())
            val connection = URI("https://127.0.0.1:$httpsPort/health")
                .toURL()
                .openConnection() as HttpsURLConnection
            connection.sslSocketFactory = trustAllSslContext.socketFactory
            connection.hostnameVerifier = { _, _ -> true }

            assertEquals("ok:https", connection.inputStream.bufferedReader().use { reader -> reader.readText() })
        } finally {
            runBlocking {
                http.stop()
                https.stop()
            }
        }
    }

    @Test
    fun rawServerStopClosesIdleClientWithoutWaitingForSocketTimeout() = runBlocking {
        val server = healthServer("http")
        val client = Socket()

        try {
            val port = allocateLocalPort()
            server.start(port)
            client.connect(java.net.InetSocketAddress("127.0.0.1", port))
            delay(100.milliseconds)

            withTimeout(1_000.milliseconds) {
                server.stop()
            }
        } finally {
            runCatching { client.close() }
            server.stop()
        }
    }

    @Test
    fun rawServerTreatsSocketReadTimeoutAsExpectedIdleDisconnect() {
        assertTrue(SocketTimeoutException("Read timed out").isExpectedLinkShareClientDisconnect())
    }

    @Test
    fun rawServerTreatsCertificateUnknownAsExpectedIdleDisconnect() {
        assertTrue(
            SSLHandshakeException("(certificate_unknown) Received fatal alert: certificate_unknown")
                .isExpectedLinkShareClientDisconnect()
        )
    }

    @Test
    fun httpPortSchemeSwitchingProxyStopClosesIdleClient() = runBlocking {
        val publicHttpPort = allocateLocalPort()
        val internalHttpPort = allocateLocalPort(publicHttpPort)
        val internalHttpsPort = allocateLocalPort(publicHttpPort, internalHttpPort)
        val proxy = HttpPortSchemeSwitchingProxy()
        val client = Socket()

        try {
            proxy.start(
                publicHttpPort = publicHttpPort,
                internalHttpPort = internalHttpPort,
                internalTlsPort = internalHttpsPort
            )
            client.connect(java.net.InetSocketAddress("127.0.0.1", publicHttpPort))
            delay(100.milliseconds)

            withTimeout(1_000.milliseconds) {
                proxy.stop()
            }
        } finally {
            runCatching { client.close() }
            proxy.stop()
        }
    }

    @Test
    fun httpPortSchemeSwitchingProxyForwardsPlainHttpToInternalHttpPort() {
        val publicHttpPort = allocateLocalPort()
        val internalHttpPort = allocateLocalPort(publicHttpPort)
        val internalHttpsPort = allocateLocalPort(publicHttpPort, internalHttpPort)
        val proxy = HttpPortSchemeSwitchingProxy()
        val internal = healthServer("http")

        try {
            internal.start(internalHttpPort, host = "127.0.0.1")
            proxy.start(
                publicHttpPort = publicHttpPort,
                internalHttpPort = internalHttpPort,
                internalTlsPort = internalHttpsPort
            )

            assertEquals("ok:http", URI("http://127.0.0.1:$publicHttpPort/health").toURL().readText())
        } finally {
            runBlocking {
                proxy.stop()
                internal.stop()
            }
        }
    }

    @Test
    fun httpPortSchemeSwitchingProxyKeepsRunningWhenInternalHttpPortIsUnavailable() = runBlocking {
        val publicHttpPort = allocateLocalPort()
        val internalHttpPort = allocateLocalPort(publicHttpPort)
        val internalHttpsPort = allocateLocalPort(publicHttpPort, internalHttpPort)
        val proxy = HttpPortSchemeSwitchingProxy()
        val internal = healthServer("http")

        try {
            proxy.start(
                publicHttpPort = publicHttpPort,
                internalHttpPort = internalHttpPort,
                internalTlsPort = internalHttpsPort
            )

            Socket("127.0.0.1", publicHttpPort).use { client ->
                client.soTimeout = 1_000
                client.getOutputStream().write(
                    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
                )
                client.getOutputStream().flush()
                runCatching { client.getInputStream().read() }
            }
            delay(100.milliseconds)

            internal.start(internalHttpPort, host = "127.0.0.1")

            val connection = URI("http://127.0.0.1:$publicHttpPort/health").toURL().openConnection()
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000

            assertEquals("ok:http", connection.getInputStream().bufferedReader().use { reader -> reader.readText() })
        } finally {
            proxy.stop()
            internal.stop()
        }
    }

    @Test
    fun httpPortSchemeSwitchingProxyForwardsTlsToInternalHttpsPortOnSamePublicPort() {
        val publicHttpPort = allocateLocalPort()
        val internalHttpPort = allocateLocalPort(publicHttpPort)
        val internalHttpsPort = allocateLocalPort(publicHttpPort, internalHttpPort)
        val proxy = HttpPortSchemeSwitchingProxy()
        val internal = LinkShareRawHttpServer("https") { request ->
            if (request.path == "/health") {
                LinkShareHttpResponse.text(text = "ok:${request.scheme}:${request.port}")
            } else {
                LinkShareHttpResponse.bytes(statusCode = 404)
            }
        }

        try {
            internal.start(internalHttpsPort, host = "127.0.0.1", tls = true)
            proxy.start(
                publicHttpPort = publicHttpPort,
                internalHttpPort = internalHttpPort,
                internalTlsPort = internalHttpsPort
            )
            val connection = URI("https://127.0.0.1:$publicHttpPort/health")
                .toURL()
                .openConnection() as HttpsURLConnection
            connection.sslSocketFactory = trustAllSslContext.socketFactory
            connection.hostnameVerifier = { _, _ -> true }
            connection.setRequestProperty("Connection", "close")

            assertEquals(
                "ok:https:$publicHttpPort",
                connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            )
        } finally {
            runBlocking {
                proxy.stop()
                internal.stop()
            }
        }
    }

    @Test
    fun parseRequestRejectsContentLengthAboveBufferedLimitOnNonUploadPath() {
        val port = allocateLocalPort()
        val server = healthServer("http")
        try {
            server.start(port, host = "127.0.0.1")
            val overLimit = (8L * 1024L * 1024L) + 1L
            val status = sendRawHttp(
                port,
                "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: $overLimit\r\nConnection: close\r\n\r\n",
            )
            assertEquals(413, status)
        } finally {
            runBlocking { server.stop() }
        }
    }

    @Test
    fun getWithContentLengthIsRejectedWithoutBuffering() {
        val port = allocateLocalPort()
        val server = healthServer("http")
        try {
            server.start(port, host = "127.0.0.1")
            val status = sendRawHttp(
                port,
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n",
            )
            assertEquals(413, status)
        } finally {
            runBlocking { server.stop() }
        }
    }

    private fun sendRawHttp(port: Int, request: String): Int {
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 2_000
            client.getOutputStream().write(request.encodeToByteArray())
            client.getOutputStream().flush()
            val statusLine = client.getInputStream().bufferedReader().readLine().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            checkNotNull(code) {
                AppStrings.ui_test_http_share_file_server_engine_jvm_invalid_http_status_line_arg0.format(arg0 = statusLine)
            }
            return code
        }
    }

    private fun healthServer(scheme: String): LinkShareRawHttpServer {
        return LinkShareRawHttpServer(scheme) { request ->
            if (request.path == "/health") {
                LinkShareHttpResponse.text(text = "ok:$scheme")
            } else {
                LinkShareHttpResponse.bytes(statusCode = 404)
            }
        }
    }

    private fun allocateLocalPort(vararg except: Int): Int {
        // 避开临时出站端口，防止并行网络请求在探测后、监听前占用测试端口。
        for (port in 18_240..18_367) {
            if (port in except) continue
            try {
                ServerSocket(port).use { return port }
            } catch (_: BindException) {
                // 已占用的候选不用于本次测试；实际 server.start 不重试。
            }
        }
        error("Unable to allocate HTTP server test port")
    }

    private val trustAllSslContext: SSLContext by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }
}
