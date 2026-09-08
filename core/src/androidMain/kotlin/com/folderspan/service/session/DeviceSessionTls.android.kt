package com.folderspan.service.session

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.LogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import strings.AppStrings

internal class AndroidDeviceSessionByteChannel(
    private val socket: Socket,
) : DeviceSessionByteChannel {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        input.read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        output.write(buffer, offset, length)
    }

    override suspend fun flush() = withContext(Dispatchers.IO) {
        output.flush()
    }

    override suspend fun writeAndFlush(buffer: ByteArray, offset: Int, length: Int) =
        withContext(Dispatchers.IO) {
            output.write(buffer, offset, length)
            output.flush()
        }

    override fun close() {
        runCatching { socket.close() }
    }
}

internal fun createPinnedDeviceSessionSocket(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): SSLSocket {
    return createDeviceSessionSocket(
        host = host,
        port = port,
        trustManager = FingerprintTrustManager(expectedFingerprintSha256),
    )
}

internal fun createUnpinnedDeviceSessionSocket(host: String, port: Int): Pair<SSLSocket, String> {
    val trustManager = FingerprintTrustManager(expectedFingerprintSha256 = null)
    val socket = createDeviceSessionSocket(host = host, port = port, trustManager = trustManager)
    val fingerprint = trustManager.peerFingerprintSha256
    check(fingerprint.isNotBlank()) { AppStrings.ui_device_tls_certificate_fingerprint_is_empty }
    return socket to fingerprint
}

private fun createDeviceSessionSocket(
    host: String,
    port: Int,
    trustManager: FingerprintTrustManager,
): SSLSocket {
    val context = SSLContext.getInstance("TLS")
    context.init(
        null,
        arrayOf(trustManager),
        SecureRandom(),
    )
    LogKit.d(AppStrings.ui_device_session_client_connection_host_arg0_port_arg1.format(arg0 = (host).toString(), arg1 = (port).toString()))
    val socket = context.socketFactory.createSocket(host, port) as SSLSocket
    try {
        socket.enabledProtocols = socket.supportedProtocols
            .filter { protocol -> protocol == "TLSv1.2" || protocol == "TLSv1.3" }
            .toTypedArray()
        socket.offerDeviceSessionAlpn()
        LogKit.d(AppStrings.ui_tls_handshake_host_arg0.format(arg0 = (host).toString()))
        socket.startHandshake()
        if (!socket.hasDeviceSessionAlpn()) {
            LogKit.w(AppStrings.ui_device_session_client_missing_alpn_arg0_host_arg1.format(arg0 = (DEVICE_SESSION_ALPN).toString(), arg1 = (host).toString()))
            throw DeviceSessionIoException("missing ALPN $DEVICE_SESSION_ALPN")
        }
        LogKit.i(AppStrings.ui_device_session_client_tls_complete_host_arg0_alpn_arg1.format(arg0 = (host).toString(), arg1 = (DEVICE_SESSION_ALPN).toString()))
        return socket
    } catch (error: Throwable) {
        runCatching { socket.close() }
        throw error
    }
}

private class FingerprintTrustManager(
    expectedFingerprintSha256: String?,
) : X509TrustManager {
    private val expected = normalizeTlsFingerprintSha256(expectedFingerprintSha256.orEmpty())
    var peerFingerprintSha256: String = ""
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull()
            ?: throw java.security.cert.CertificateException(AppStrings.ui_server_certificate_is_empty)
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val actual = digest.joinToString("") { byte -> "%02X".format(byte) }
        peerFingerprintSha256 = actual
        if (expected.isNotBlank() && actual != expected) {
            throw java.security.cert.CertificateException(AppStrings.ui_device_tls_certificate_fingerprint_does_not_match)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
