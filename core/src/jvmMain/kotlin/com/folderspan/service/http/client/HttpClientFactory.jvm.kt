package com.folderspan.service.http.client

import com.folderspan.service.http.tls.SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import strings.AppStrings

/**
 * JVM 平台实现：发现与文件 API 都走 CIO。
 * 桌面入口会清空 Java 代理参数，避免 CIO 读取全局 ProxySelector 时走代理。
 */
actual fun createNoProxyHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    createCioClient(tlsMode = ClientTlsMode.System, config = config)

actual fun createPinnedNoProxyHttpClient(
    expectedFingerprintSha256: String,
    config: HttpClientConfig<*>.() -> Unit
): HttpClient = createCioClient(
    tlsMode = ClientTlsMode.Pinned(expectedFingerprintSha256),
    config = config
)

internal actual fun createDiscoveryNoProxyHttpClient(
    leafCertificateCapture: LeafCertificateCapture,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = createCioClient(
    tlsMode = ClientTlsMode.Capture(leafCertificateCapture),
    config = config,
)

private fun createCioClient(
    tlsMode: ClientTlsMode,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(CIO) {
        engine {
            requestTimeout = CIO_REQUEST_TIMEOUT_MS
            maxConnectionsCount = if (tlsMode is ClientTlsMode.Capture) 1 else CIO_MAX_CONNECTIONS
            endpoint {
                connectTimeout = CIO_CONNECT_TIMEOUT_MS
                socketTimeout = CIO_SOCKET_TIMEOUT_MS
                connectAttempts = 1
                maxConnectionsPerRoute = if (tlsMode is ClientTlsMode.Capture) 1 else CIO_MAX_CONNECTIONS_PER_ROUTE
                keepAliveTime = if (tlsMode is ClientTlsMode.Capture) 1 else CIO_KEEP_ALIVE_TIME_MS
            }
            when (tlsMode) {
                ClientTlsMode.System -> Unit
                is ClientTlsMode.Capture -> https {
                    trustManager = FingerprintTrustManager(capture = tlsMode.capture)
                }
                is ClientTlsMode.Pinned -> https {
                    trustManager = FingerprintTrustManager(
                        expectedFingerprintSha256 = tlsMode.expectedFingerprintSha256,
                    )
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        this.config()
    }

private sealed interface ClientTlsMode {
    data object System : ClientTlsMode
    data class Capture(val capture: LeafCertificateCapture) : ClientTlsMode
    data class Pinned(val expectedFingerprintSha256: String) : ClientTlsMode
}

private const val CIO_MAX_CONNECTIONS = 64
private const val CIO_MAX_CONNECTIONS_PER_ROUTE = 64
private const val CIO_CONNECT_TIMEOUT_MS = 30_000L
private const val CIO_SOCKET_TIMEOUT_MS = 5 * 60 * 1000L
private const val CIO_REQUEST_TIMEOUT_MS = 5 * 60 * 1000L
private const val CIO_KEEP_ALIVE_TIME_MS = 5_000L

private class FingerprintTrustManager(
    expectedFingerprintSha256: String? = null,
    private val capture: LeafCertificateCapture? = null,
) : X509TrustManager {
    private val expected = normalizeTlsFingerprintSha256(expectedFingerprintSha256.orEmpty())

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull()
            ?: throw CertificateException(AppStrings.ui_server_certificate_is_empty)
        val actual = certificate.encoded.sha256Hex()
        capture?.accept(actual)
        if (capture != null) return
        if (expected.isBlank() || actual != expected) {
            throw CertificateException(SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { byte -> "%02X".format(byte) }
}
