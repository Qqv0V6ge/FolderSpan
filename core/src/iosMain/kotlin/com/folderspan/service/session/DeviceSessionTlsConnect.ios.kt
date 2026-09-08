package com.folderspan.service.session

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.LogKit
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.darwin.inet_pton
import platform.openssl.SSL
import platform.openssl.SSL_CTX
import platform.openssl.SSL_CTX_free
import platform.openssl.SSL_connect
import platform.openssl.SSL_free
import platform.openssl.SSL_new
import platform.openssl.SSL_read
import platform.openssl.SSL_set_fd
import platform.openssl.SSL_write
import platform.openssl.fm_create_client_ctx
import platform.openssl.fm_ssl_alpn_is_folderspan
import platform.openssl.fm_ssl_peer_fingerprint_sha256
import platform.posix.AF_INET
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.socket
import strings.AppStrings

@OptIn(ExperimentalForeignApi::class)
internal actual fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): DeviceSessionByteChannel {
    return connectDeviceSessionChannel(host, port, expectedFingerprintSha256).channel
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection {
    return connectDeviceSessionChannel(host, port, expectedFingerprintSha256 = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun connectDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String?,
): DeviceSessionBootstrapConnection {
    LogKit.d(AppStrings.ui_device_session_client_connection_host_arg0_port_arg1.format(arg0 = (host).toString(), arg1 = (port).toString()))
    val ctx = fm_create_client_ctx() ?: throw DeviceSessionIoException("TLS client context failed")
    val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    if (fd < 0) {
        SSL_CTX_free(ctx)
        throw DeviceSessionIoException("socket create failed")
    }
    try {
        memScoped {
            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_family = AF_INET.convert()
            address.sin_port = port.toNetworkOrderPort()
            val parsed = inet_pton(AF_INET, host, address.sin_addr.ptr)
            if (parsed != 1) {
                throw DeviceSessionIoException("invalid host $host")
            }
            if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                throw DeviceSessionIoException("connect failed")
            }
        }
        val ssl = SSL_new(ctx) ?: throw DeviceSessionIoException("SSL_new failed")
        LogKit.d(AppStrings.ui_tls_handshake_host_arg0.format(arg0 = (host).toString()))
        if (SSL_set_fd(ssl, fd) != 1 || SSL_connect(ssl) != 1) {
            SSL_free(ssl)
            throw DeviceSessionIoException("TLS handshake failed")
        }
        if (fm_ssl_alpn_is_folderspan(ssl) != 1) {
            LogKit.w(AppStrings.ui_device_session_client_missing_alpn_arg0_host_arg1.format(arg0 = (DEVICE_SESSION_ALPN).toString(), arg1 = (host).toString()))
            SSL_free(ssl)
            throw DeviceSessionIoException("missing ALPN $DEVICE_SESSION_ALPN")
        }
        val actual = peerFingerprint(ssl)
        if (actual.isBlank()) {
            SSL_free(ssl)
            throw DeviceSessionIoException(AppStrings.ui_device_tls_certificate_fingerprint_is_empty)
        }
        val expected = expectedFingerprintSha256?.let(::normalizeTlsFingerprintSha256).orEmpty()
        if (expected.isNotBlank() && actual != expected) {
            LogKit.w(AppStrings.ui_device_session_client_certificate_fingerprint_mismatch_host_arg0.format(arg0 = (host).toString()))
            SSL_free(ssl)
            throw DeviceSessionIoException(AppStrings.ui_device_tls_certificate_fingerprint_does_not_match)
        }
        LogKit.i(AppStrings.ui_device_session_client_tls_complete_host_arg0_alpn_arg1.format(arg0 = (host).toString(), arg1 = (DEVICE_SESSION_ALPN).toString()))
        return DeviceSessionBootstrapConnection(
            channel = IosDeviceSessionByteChannel(ssl, ctx, fd),
            peerFingerprintSha256 = actual,
        )
    } catch (error: Throwable) {
        close(fd)
        SSL_CTX_free(ctx)
        throw error
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDeviceSessionByteChannel(
    private val ssl: CPointer<SSL>,
    private val ctx: CPointer<SSL_CTX>,
    private val fd: Int,
) : DeviceSessionByteChannel {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.Default) {
        if (length <= 0) return@withContext 0
        buffer.usePinned { pinned ->
            SSL_read(ssl, pinned.addressOf(offset), length)
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.Default) {
        if (length <= 0) return@withContext
        buffer.usePinned { pinned ->
            var writtenTotal = 0
            while (writtenTotal < length) {
                val written = SSL_write(ssl, pinned.addressOf(offset + writtenTotal), length - writtenTotal)
                if (written <= 0) throw DeviceSessionIoException("TLS write failed")
                writtenTotal += written
            }
        }
    }

    override suspend fun flush() = Unit

    override fun close() {
        SSL_free(ssl)
        SSL_CTX_free(ctx)
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun peerFingerprint(ssl: CPointer<SSL>): String = memScoped {
    val digest = allocArray<UByteVar>(32)
    val length = alloc<UIntVar>()
    length.value = 32u
    if (fm_ssl_peer_fingerprint_sha256(ssl, digest.reinterpret(), length.ptr) != 1) {
        return@memScoped ""
    }
    (0 until length.value.toInt()).joinToString("") { index ->
        val value = digest[index].toInt() and 0xFF
        value.toString(16).uppercase().padStart(2, '0')
    }
}

private fun Int.toNetworkOrderPort(): UShort {
    val value = this and 0xFFFF
    val networkOrder = ((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)
    return networkOrder.toUShort()
}
