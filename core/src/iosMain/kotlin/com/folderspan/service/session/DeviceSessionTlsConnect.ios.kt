package com.folderspan.service.session

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.LogKit
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import platform.posix.*
import strings.AppStrings
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
    connectTimeoutMillis: Int,
): DeviceSessionByteChannel {
    var opened: DeviceSessionByteChannel? = null
    return try {
        withContext(Dispatchers.Default) {
            connectDeviceSessionChannel(host, port, expectedFingerprintSha256, connectTimeoutMillis).channel.also { opened = it }
        }
    } catch (error: Throwable) {
        opened?.close()
        throw error
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun connectUnpinnedDeviceSessionChannel(
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
    connectTimeoutMillis: Int = 0,
): DeviceSessionBootstrapConnection {
    LogKit.d(AppStrings.ui_device_session_client_connection_host_arg0_port_arg1.format(arg0 = host, arg1 = (port).toString()))
    val ctx = fm_create_client_ctx() ?: throw DeviceSessionIoException("TLS client context failed")
    val fd = connectSessionSocket(host, port, connectTimeoutMillis)
    if (fd < 0) {
        SSL_CTX_free(ctx)
        throw DeviceSessionIoException("device TLS connection failed")
    }
    try {
        if (connectTimeoutMillis > 0) configureSessionSocketTimeout(fd, connectTimeoutMillis)
        val ssl = SSL_new(ctx) ?: throw DeviceSessionIoException("SSL_new failed")
        LogKit.d(AppStrings.ui_tls_handshake_host_arg0.format(arg0 = host))
        if (SSL_set_fd(ssl, fd) != 1 || SSL_connect(ssl) != 1) {
            SSL_free(ssl)
            throw DeviceSessionIoException("TLS handshake failed")
        }
        if (fm_ssl_alpn_is_folderspan(ssl) != 1) {
            LogKit.w(AppStrings.ui_device_session_client_missing_alpn_arg0_host_arg1.format(arg0 = DEVICE_SESSION_ALPN, arg1 = host))
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
            LogKit.w(AppStrings.ui_device_session_client_certificate_fingerprint_mismatch_host_arg0.format(arg0 = host))
            SSL_free(ssl)
            throw DeviceSessionIoException(AppStrings.ui_device_tls_certificate_fingerprint_does_not_match)
        }
        if (connectTimeoutMillis > 0) configureSessionSocketTimeout(fd, 0)
        LogKit.i(AppStrings.ui_device_session_client_tls_complete_host_arg0_alpn_arg1.format(arg0 = host, arg1 = DEVICE_SESSION_ALPN))
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
private fun connectSessionSocket(host: String, port: Int, timeout: Int): Int = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0) return@memScoped -1
    try {
        val start = TimeSource.Monotonic.markNow()
        var current = result.value
        while (current != null) {
            val remaining = timeout - start.elapsedNow().inWholeMilliseconds
            if (timeout > 0 && remaining <= 0) break
            val address = current.pointed
            val fd = socket(address.ai_family, address.ai_socktype, address.ai_protocol)
            if (fd >= 0) {
                val connected = if (timeout == 0) connect(fd, address.ai_addr, address.ai_addrlen) == 0
                else connectSessionSocketWithTimeout(fd, address, remaining.toInt())
                if (connected) return@memScoped fd
                close(fd)
            }
            current = address.ai_next
        }
        -1
    } finally {
        freeaddrinfo(result.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun connectSessionSocketWithTimeout(fd: Int, address: addrinfo, timeout: Int): Boolean = memScoped {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0 || fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return@memScoped false
    val ready = alloc<pollfd>()
    ready.fd = fd
    ready.events = POLLOUT.convert()
    ready.revents = 0
    val error = alloc<IntVar>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<IntVar>().convert()
    if (connect(fd, address.ai_addr, address.ai_addrlen) != 0 &&
        (errno != EINPROGRESS || poll(ready.ptr, 1.convert(), timeout) <= 0 ||
            getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr) != 0 || error.value != 0)
    ) return@memScoped false
    fcntl(fd, F_SETFL, flags) == 0
}

@OptIn(ExperimentalForeignApi::class)
private fun configureSessionSocketTimeout(fd: Int, millis: Int) = memScoped {
    val timeout = alloc<timeval>()
    timeout.tv_sec = (millis / 1_000).convert()
    timeout.tv_usec = (millis % 1_000 * 1_000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
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
