package com.folderspan.ui.state.main

import com.folderspan.getSocketDevice
import com.folderspan.service.data.DISCOVERY_PING_HEADER
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.client.*
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.server.getLocalIpv4Set
import com.folderspan.service.http.server.selectAdvertisedHttpHost
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
/**
 * 探测目标设备是否在线（轻量 Ping）。
 *
 * @return 返回 true 表示目标可达且响应可解析，否则返回 false
 */
internal suspend fun SocketDevice.probeAlive(client: HttpClient): Boolean {
    if (sessionClient != null) return true
    if (httpClient == null) {
        // 设备会话端口只接受 ALPN folderspan/1，HTTPS /ping 会被拒绝并误判离线。
        return false
    }
    val targetHost = host
    val encryptedTransport = usePlainHttpDeviceTransport()
    val requestProtocol = deviceApiProtocol(plainHttpTransport = encryptedTransport)
    val targetPort = if (requestProtocol == URLProtocol.HTTP) httpPortOrFallback() else httpsPortOrFallback()
    if (targetHost.isBlank()) return false

    val selfDevice = getSocketDevice()
    val localIpSet = getLocalIpv4Set(listOf(selfDevice.host))
    if (id == selfDevice.id || targetHost in localIpSet) {
        // 跳过本机/本地地址，避免自检导致自 Ping。
        return false
    }

    val requestDevice = selfDevice.withCopy(
        host = selectAdvertisedHttpHost(
            currentHost = selfDevice.host,
            targetHost = targetHost,
            candidateHosts = getAllIPAddresses(SocketClientIPEnum.IPV4_UP),
        )
    )

    val probeClient = normalizedTlsFingerprint()
        .takeIf { item -> item.isNotBlank() && requestProtocol == URLProtocol.HTTPS }
        ?.let { fingerprint ->
            createPinnedNoProxyHttpClient(fingerprint) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 1000
                    connectTimeoutMillis = 1000
                    socketTimeoutMillis = 1000
                }
            }
        }
        ?: client.takeIf { requestProtocol != URLProtocol.HTTPS }

    return probeClient != null && try {
        val response = probeClient.post {
            url {
                protocol = requestProtocol
                host = targetHost
                path("/ping")
                port = targetPort
            }
            contentType(ContentType.Application.ProtoBuf)
            header(DISCOVERY_PING_HEADER, "true")
            setFolderSpanRequestBody(
                ProtoBuf.encodeToByteArray(SocketDevice.serializer(), requestDevice),
                encryptedTransport
            )
        }

        if (!response.status.isSuccess()) return false
        val bytes = response.folderSpanBodyBytes()
        if (bytes.isEmpty()) return false
        runCatching { ProtoBuf.decodeFromByteArray(SocketDevice.serializer(), bytes) }.isSuccess
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    } finally {
        if (probeClient !== client) {
            runCatching { probeClient.close() }
        }
    }
}
