package com.folderspan.service.mcp.automation

import com.folderspan.service.http.client.DiscoveryCapturingClientPool
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.path

/**
 * MCP 设备扫描专用探测：只做 TLS 握手连通性，不发送 access key、不写设备库、不发起连接。
 */
object TlsHandshakeMcpDeviceProbe : McpDeviceProbe {
    private val pool = DiscoveryCapturingClientPool(
        config = {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 1_000
                connectTimeoutMillis = 1_000
                socketTimeoutMillis = 1_000
            }
        },
    )

    override suspend fun probe(address: String, port: Int): Boolean {
        return runCatching {
            pool.withClient { client, capture ->
                client.get {
                    url {
                        host = address
                        this.port = port
                        protocol = URLProtocol.HTTPS
                        path("/ping")
                    }
                    header(HttpHeaders.Connection, "close")
                }
                capture.take().isNullOrBlank().not()
            }
        }.getOrDefault(false)
    }
}
