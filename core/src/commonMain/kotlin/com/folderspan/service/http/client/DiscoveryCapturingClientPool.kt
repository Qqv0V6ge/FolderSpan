package com.folderspan.service.http.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.channels.Channel

internal const val DISCOVERY_CAPTURING_CLIENT_POOL_SIZE = 64

/**
 * 限制发现 ping 的并发握手捕获。
 * 每次请求新建客户端，避免 keep-alive 跳过握手后指纹为空或串到上一台设备。
 */
internal class DiscoveryCapturingClientPool(
    size: Int = DISCOVERY_CAPTURING_CLIENT_POOL_SIZE,
    private val config: HttpClientConfig<*>.() -> Unit,
) {
    private val permits = Channel<Unit>(capacity = size)

    init {
        repeat(size) {
            check(permits.trySend(Unit).isSuccess)
        }
    }

    suspend fun <T> withClient(block: suspend (HttpClient, LeafCertificateCapture) -> T): T {
        permits.receive()
        try {
            val capture = LeafCertificateCapture()
            val client = createDiscoveryNoProxyHttpClient(capture, config)
            try {
                return block(client, capture)
            } finally {
                client.close()
            }
        } finally {
            permits.send(Unit)
        }
    }
}

internal class CapturedDiscoveryPing(
    val body: ByteArray,
    val handshakeFingerprint: String?,
    val headers: Map<String, String?>,
)
