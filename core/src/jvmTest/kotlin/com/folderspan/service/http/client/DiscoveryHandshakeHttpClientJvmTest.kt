package com.folderspan.service.http.client

import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryHandshakeHttpClientJvmTest {
    @Test
    fun discoveryClientRecordsLeafCertificateFromHandshake() = runBlocking {
        val port = ServerSocket(0).use { socket -> socket.localPort }
        val server = LinkShareRawHttpServer("https") { request ->
            if (request.path == "/health") {
                LinkShareHttpResponse.text(text = "ok")
            } else {
                LinkShareHttpResponse.bytes(statusCode = 404)
            }
        }
        val capture = LeafCertificateCapture()
        val client = createDiscoveryNoProxyHttpClient(capture) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
            }
        }
        try {
            server.start(port, host = "127.0.0.1", tls = true)
            val response = client.get("https://127.0.0.1:$port/health") {
                headers.append(HttpHeaders.Connection, "close")
            }
            assertEquals(HttpStatusCode.OK, response.status)

            val expected = normalizeTlsFingerprintSha256(
                DeviceTlsIdentity.loadOrCreate().fingerprintSha256
            )
            val handshake = capture.take()
            assertEquals(expected, handshake)
            assertNull(capture.take())
            assertEquals(expected, requireHandshakeTlsPin(handshake))
        } finally {
            client.close()
            server.stop()
        }
    }
}
