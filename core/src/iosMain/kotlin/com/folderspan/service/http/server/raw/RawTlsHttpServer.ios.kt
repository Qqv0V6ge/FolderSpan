package com.folderspan.service.http.server.raw

import strings.AppStrings

import com.folderspan.routes.*
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_HEADER
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_VERSION
import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.session.DeviceLanBeaconAdvertiser
import com.folderspan.service.session.DeviceSessionByteChannel
import com.folderspan.service.session.DeviceSessionServerApprovalContext
import com.folderspan.service.session.DeviceSessionServerRuntimeLauncher
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.utils.LogKit
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import platform.openssl.SSL
import platform.openssl.SSL_CTX
import platform.openssl.SSL_accept
import platform.openssl.SSL_free
import platform.openssl.SSL_new
import platform.openssl.SSL_read
import platform.openssl.SSL_set_fd
import platform.openssl.SSL_write
import platform.openssl.fm_ssl_alpn_is_folderspan
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
class RawTlsHttpServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher = RawHttpApiDispatcher()
    private val shareApprovalServer = LinkShareRawHttpServer(
        scheme = "https",
        maxRequestBodyBytes = DEVICE_SHARE_APPROVAL_MAX_REQUEST_BODY_BYTES,
        handler = dispatcher::dispatchDeviceShareApproval,
    )
    private val clientSemaphore = Semaphore(MAX_CONCURRENT_CLIENTS)
    private val routeCapacity = RawHttpRouteCapacity(
        maxControlRequests = MAX_CONCURRENT_CONTROL_REQUESTS,
        maxDataRequests = MAX_CONCURRENT_DATA_REQUESTS,
        maxIdleDataKeepAliveConnections = MAX_IDLE_DATA_KEEP_ALIVE_CONNECTIONS,
        maxPairingRequests = MAX_CONCURRENT_PAIRING_REQUESTS,
    )
    private var serverFd: Int = INVALID_FD
    private var acceptJob: Job? = null
    private var serverContext: DeviceTlsIdentity.ServerContext? = null
    private val beaconAdvertiser = DeviceLanBeaconAdvertiser(scope)

    fun start(port: Int) {
        if (acceptJob?.isActive == true || serverFd >= 0) {
            LogKit.i(AppStrings.ui_ios_raw_tls_file_sharing_service_already_running_skip)
            return
        }
        val context = DeviceTlsIdentity.createSessionServerContext()
        val fd = try {
            createListeningSocket(port)
        } catch (error: Throwable) {
            context.close()
            throw error
        }
        serverContext = context
        serverFd = fd
        shareApprovalServer.start(defaultDeviceShareApprovalPort(port))
        beaconAdvertiser.start(port)
        acceptJob = scope.launch {
            try {
                LogKit.i(AppStrings.ui_ios_raw_tls_file_sharing_service_starts_listening_arg0.format(arg0 = (port).toString()))
                while (isActive) {
                    val client = withContext(Dispatchers.Default) { acceptClient(fd) }
                    if (client.fd < 0) {
                        if (isActive) throw posixException(AppStrings.ui_accept_client_connection_failure)
                        break
                    }
                    if (!clientSemaphore.tryAcquire()) {
                        LogKit.w(AppStrings.ui_number_ios_raw_tls_connections_has_reached_upper_limit)
                        close(client.fd)
                        continue
                    }
                    launch {
                        try {
                            handleClient(context.pointer, client.fd, client.remoteHost)
                        } finally {
                            clientSemaphore.release()
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException(AppStrings.ui_ios_raw_tls_file_sharing_service_stopped)
            } catch (error: Throwable) {
                if (isActive) {
                    LogKit.e(AppStrings.ui_ios_raw_tls_file_sharing_service_accept_exception_arg0.format(arg0 = (error.message).toString()), error)
                }
            } finally {
                closeServerSocket(fd, context)
            }
        }
    }

    suspend fun stop() {
        runCatching { shareApprovalServer.stop() }
        val fd = serverFd
        serverFd = INVALID_FD
        if (fd >= 0) close(fd)
        beaconAdvertiser.stop()
        acceptJob?.cancelAndJoin()
        acceptJob = null
        serverContext?.close()
        serverContext = null
    }

    fun isRunning(): Boolean = serverFd >= 0 && acceptJob?.isActive == true

    private suspend fun handleClient(ctx: CPointer<SSL_CTX>, clientFd: Int, remoteHost: String?) {
        val ssl = SSL_new(ctx)
        if (ssl == null) {
            close(clientFd)
            return
        }
        var connection: RawHttpConnection? = null
        var idleDataKeepAlivePermit: RawHttpCapacityPermit? = null
        try {
            LogKit.d(AppStrings.ui_device_session_tls_handshake_remote_arg0.format(arg0 = (remoteHost).toString()))
            if (SSL_set_fd(ssl, clientFd) != 1 || SSL_accept(ssl) != 1) {
                LogKit.w(AppStrings.ui_device_session_tls_handshake_failed_remote_arg0.format(arg0 = (remoteHost).toString()))
                return
            }
            if (fm_ssl_alpn_is_folderspan(ssl) != 1) {
                LogKit.w(AppStrings.ui_device_session_rejected_connection_missing_alpn_folderspan_1_remote_arg0.format(arg0 = (remoteHost).toString()))
                return
            }
            LogKit.i(AppStrings.ui_device_session_tls_complete_remote_arg0_alpn_folderspan_1.format(arg0 = (remoteHost).toString()))
            DeviceSessionServerRuntimeLauncher.run(
                dispatcher = dispatcher,
                channel = IosAcceptedSessionByteChannel(ssl),
                remoteHost = remoteHost,
                scope = scope,
                approvalContext = DeviceSessionServerApprovalContext.Standard,
            )
            return
            val activeConnection = SslRawHttpConnection(ssl)
            connection = activeConnection
            while (currentCoroutineContext().isActive) {
                val request = try {
                    parseRequest(activeConnection, remoteHost)
                } catch (_: RawTlsEofException) {
                    break
                }
                idleDataKeepAlivePermit?.release()
                idleDataKeepAlivePermit = null
                var keepAlive = request.shouldKeepAliveConnection()
                val routeClass = request.routeClass()
                val admitted = routeCapacity.withAdmittedRequestOrNull(routeClass) {
                    val response = dispatcher.dispatch(request)
                    if (keepAlive) {
                        keepAlive = request.discardUnreadBody()
                    }
                    if (keepAlive && routeClass == RawHttpRouteClass.Data) {
                        val permit = routeCapacity.tryAcquireIdleDataKeepAlive()
                        if (permit == null) {
                            keepAlive = false
                        } else {
                            idleDataKeepAlivePermit = permit
                        }
                    }
                    writeResponse(activeConnection, response, keepAlive)
                    true
                } ?: false
                if (!admitted) {
                    keepAlive = false
                    writeResponse(activeConnection, rejectedCapacityResponse(routeClass), keepAlive = false)
                }
                if (!keepAlive) break
            }
        } catch (error: Throwable) {
            if (!error.isExpectedClientDisconnect()) {
                LogKit.e(AppStrings.ui_ios_raw_http_tls_request_processing_exception_arg0.format(arg0 = (error.message).toString()), error)
                runCatching {
                    connection?.let { activeConnection ->
                        writeResponse(
                            activeConnection,
                            RawHttpResponse.bytes(500, body = byteArrayOf()),
                            keepAlive = false
                        )
                    }
                }
            }
        } finally {
            idleDataKeepAlivePermit?.release()
            SSL_free(ssl)
            close(clientFd)
        }
    }

    private fun parseRequest(connection: RawHttpConnection, remoteHost: String?): RawHttpRequest {
        val requestLine = readHttpLine(connection).takeIf { item -> item.isNotBlank() }
            ?: throw RawTlsEofException(AppStrings.ui_empty_request_line)
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) throw IllegalArgumentException(AppStrings.ui_http_request_line_invalid)
        val method = requestParts[0].trim()
        val target = requestParts[1].trim()
        val protocolVersion = requestParts.getOrNull(2)?.trim().orEmpty().ifBlank { "HTTP/1.0" }
        val path = target.substringBefore("?").ifBlank { "/" }
        val queryParameters = parseQuery(target.substringAfter("?", ""))
        val headers = linkedMapOf<String, String>()
        var headerCount = 0
        var headerBytes = 0
        while (true) {
            val line = readHttpLine(connection)
            if (line.isEmpty()) break
            headerCount++
            headerBytes += line.encodeToByteArray().size
            if (headerCount > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                throw IllegalArgumentException(AppStrings.ui_http_header_too_many)
            }
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val transferEncodings = headers["transfer-encoding"]
            ?.split(",")
            ?.map { item -> item.trim().lowercase() }
            .orEmpty()
        val isChunked = "chunked" in transferEncodings
        val maxRequestBodyBytes = if (shouldUseLargeRawHttpRequestBodyLimit(path)) {
            MAX_REQUEST_BODY_BYTES
        } else {
            bufferedRequestBodyLimitBytes()
        }
        if (contentLength !in 0L..maxRequestBodyBytes) {
            throw IllegalArgumentException(AppStrings.ui_the_request_body_is_too_large)
        }
        val isEncryptedPayload = headers[HTTP_ENCRYPTED_PAYLOAD_HEADER.lowercase()] == HTTP_ENCRYPTED_PAYLOAD_VERSION
        val bodyReader = if (shouldStreamRawHttpRequestBody(path, contentLength, isChunked)) {
            ConnectionRawHttpRequestBodyReader(connection, contentLength)
        } else {
            null
        }
        val body = when {
            isChunked -> readChunkedBody(connection, maxRequestBodyBytes)
            contentLength == 0L || bodyReader != null -> byteArrayOf()
            else -> readExact(connection, contentLength.toInt())
        }
        return RawHttpRequest(
            method = method,
            path = path,
            protocolVersion = protocolVersion,
            queryParameters = queryParameters,
            headers = headers,
            body = body,
            bodyReader = bodyReader,
            remoteHost = remoteHost,
        )
    }

    private fun acceptClient(serverFd: Int): AcceptedClient = memScoped {
        val address = alloc<sockaddr_in6>()
        val addressLength = alloc<socklen_tVar>()
        addressLength.value = sizeOf<sockaddr_in6>().convert()
        memset(address.ptr, 0, sizeOf<sockaddr_in6>().convert())
        val fd = accept(serverFd, address.ptr.reinterpret(), addressLength.ptr)
        if (fd < 0) {
            return@memScoped AcceptedClient(fd, null)
        }
        configureClientSocket(fd)
        AcceptedClient(
            fd = fd,
            remoteHost = numericHost(address.ptr.reinterpret(), addressLength.value)
        )
    }

    private fun configureClientSocket(fd: Int) = memScoped {
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())

        val bufferSize = alloc<IntVar>()
        bufferSize.value = SOCKET_BUFFER_BYTES
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, bufferSize.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, bufferSize.ptr, sizeOf<IntVar>().convert())
    }

    private fun numericHost(address: CPointer<sockaddr>, addressLength: socklen_t): String? = memScoped {
        val family = address.pointed.sa_family.toInt()
        if (family != AF_INET && family != AF_INET6) return@memScoped null
        val host = allocArray<ByteVar>(NI_MAXHOST)
        val result = getnameinfo(
            address,
            addressLength,
            host,
            NI_MAXHOST.convert(),
            null,
            0u,
            NI_NUMERICHOST
        )
        if (result == 0) {
            host.toKString().substringBefore("%").takeIf { item -> item.isNotBlank() }
        } else {
            null
        }
    }

    private suspend fun writeResponse(connection: RawHttpConnection, response: RawHttpResponse, keepAlive: Boolean) {
        val headers = linkedMapOf<String, String>()
        headers.putAll(response.headers)
        if (!headers.containsKey("Connection")) {
            headers["Connection"] = if (keepAlive) "keep-alive" else "close"
        }
        when (val body = response.body) {
            is RawHttpBody.Bytes -> {
                headers["Content-Length"] = body.bytes.size.toString()
                writeStatusAndHeaders(connection, response, headers)
                connection.writeFully(body.bytes, 0, body.bytes.size)
            }

            is RawHttpBody.Stream -> {
                val contentLength = body.contentLength
                val writer = if (contentLength == null) {
                    headers["Transfer-Encoding"] = "chunked"
                    writeStatusAndHeaders(connection, response, headers)
                    ChunkedConnectionOutputWriter(connection)
                } else {
                    headers["Content-Length"] = contentLength.toString()
                    writeStatusAndHeaders(connection, response, headers)
                    FixedConnectionOutputWriter(connection)
                }
                body.writer.invoke(writer)
                if (writer is ChunkedConnectionOutputWriter) writer.finish()
                writer.flush()
            }
        }
    }

    private fun writeStatusAndHeaders(
        connection: RawHttpConnection,
        response: RawHttpResponse,
        headers: Map<String, String>,
    ) {
        connection.writeAscii("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
        headers.forEach { (name, value) ->
            connection.writeAscii("$name: $value\r\n")
        }
        connection.writeAscii("\r\n")
    }

    private class FixedConnectionOutputWriter(private val connection: RawHttpConnection) : RawHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            connection.writeFully(bytes, offset, length)
        }

        override suspend fun flush() = Unit
    }

    private class ChunkedConnectionOutputWriter(private val connection: RawHttpConnection) : RawHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            connection.writeAscii(length.toString(16))
            connection.writeAscii("\r\n")
            connection.writeFully(bytes, offset, length)
            connection.writeAscii("\r\n")
        }

        override suspend fun flush() = Unit

        fun finish() {
            connection.writeAscii("0\r\n\r\n")
        }
    }

    private class ConnectionRawHttpRequestBodyReader(
        private val connection: RawHttpConnection,
        override val contentLength: Long,
    ) : RawHttpRequestBodyReader {
        override var remainingBytes: Long = contentLength
            private set

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (remainingBytes <= 0L) return EOF
            val readLength = minOf(length.toLong(), remainingBytes).toInt()
            val read = connection.read(buffer, offset, readLength)
            if (read <= 0) return EOF
            remainingBytes -= read.toLong()
            return read
        }

        override suspend fun discard() {
            if (remainingBytes <= 0L) return
            val buffer = ByteArray(
                minOf(REQUEST_BODY_DISCARD_BUFFER_BYTES.toLong(), remainingBytes)
                    .coerceAtLeast(1L)
                    .toInt()
            )
            while (remainingBytes > 0L) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remainingBytes).toInt())
                if (read <= 0) throw RawTlsEofException(AppStrings.ui_the_request_body_is_incomplete)
            }
        }
    }

    private fun createListeningSocket(port: Int): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (fd < 0) throw posixException(AppStrings.ui_create_a_listener_socket_failure)
        try {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())

            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_family = AF_INET.convert()
            address.sin_port = port.toNetworkOrderPort()
            address.sin_addr.s_addr = 0u
            if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                throw posixException(AppStrings.ui_binding_port_arg0_failed.format(arg0 = (port).toString()))
            }
            if (listen(fd, BACKLOG) != 0) {
                throw posixException(AppStrings.ui_listening_port_arg0_failed.format(arg0 = (port).toString()))
            }
            fd
        } catch (error: Throwable) {
            close(fd)
            throw error
        }
    }

    private fun closeServerSocket(fd: Int, context: DeviceTlsIdentity.ServerContext) {
        if (serverFd == fd) serverFd = INVALID_FD
        runCatching { close(fd) }
        if (serverContext === context) {
            serverContext = null
            context.close()
        }
    }

    private fun readHttpLine(connection: RawHttpConnection): String {
        val bytes = mutableListOf<Byte>()
        val one = ByteArray(1)
        while (true) {
            val read = connection.read(one, 0, 1)
            if (read <= 0) {
                if (bytes.isEmpty()) throw RawTlsEofException(AppStrings.ui_connection_closed)
                break
            }
            val next = one[0].toInt() and 0xFF
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.add(one[0])
            if (bytes.size > MAX_HEADER_LINE_BYTES) throw IllegalArgumentException(AppStrings.ui_http_header_too_long)
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun readExact(connection: RawHttpConnection, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = connection.read(result, offset, length - offset)
            if (read <= 0) throw RawTlsEofException(AppStrings.ui_the_request_body_is_incomplete)
            offset += read
        }
        return result
    }

    private fun readChunkedBody(connection: RawHttpConnection, maxBodyBytes: Long): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0L
        while (true) {
            val sizeLine = readHttpLine(connection)
            val chunkSize = sizeLine
                .substringBefore(";")
                .trim()
                .toLongOrNull(16)
                ?: throw IllegalArgumentException(AppStrings.ui_invalid_chunked_request_body)
            if (chunkSize < 0L) throw IllegalArgumentException(AppStrings.ui_invalid_chunked_request_body)
            if (chunkSize == 0L) {
                while (readHttpLine(connection).isNotEmpty()) {
                    // 丢弃 trailer headers。
                }
                break
            }
            totalBytes += chunkSize
            if (totalBytes > maxBodyBytes || chunkSize > Int.MAX_VALUE) {
                throw IllegalArgumentException(AppStrings.ui_the_request_body_is_too_large)
            }
            chunks.add(readExact(connection, chunkSize.toInt()))
            val terminator = readHttpLine(connection)
            if (terminator.isNotEmpty()) throw IllegalArgumentException(AppStrings.ui_invalid_chunked_request_body)
        }
        val result = ByteArray(totalBytes.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }

    private fun bufferedRequestBodyLimitBytes(): Long {
        return HttpTransferRuntimeTuning.plan(
            maxChunkBytes = MAX_BUFFERED_REQUEST_BODY_BYTES,
            maxParallelRequests = 1,
        ).recommendedChunkBytes.toLong()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val name = part.substringBefore("=")
                val value = part.substringAfter("=", "")
                decodeUrl(name) to decodeUrl(value)
            }
            .toMap()
    }

    private fun decodeUrl(value: String): String {
        if (value.isEmpty()) return value
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    bytes.add(byte.toByte())
                    index += 3
                    continue
                }
            }
            val text = if (char == '+') " " else char.toString()
            bytes.addAll(text.encodeToByteArray().asIterable())
            index += 1
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun Throwable.isExpectedClientDisconnect(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is RawTlsEofException || isExpectedRawTlsClientDisconnectMessage(current.message.orEmpty())) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private class RawTlsEofException(message: String) : Exception(message)

    private data class AcceptedClient(
        val fd: Int,
        val remoteHost: String?,
    )

    companion object {
        private const val INVALID_FD = -1
        private const val BACKLOG = 64
        private const val MAX_CONCURRENT_CLIENTS = 96
        private const val MAX_CONCURRENT_CONTROL_REQUESTS = 32
        private const val MAX_CONCURRENT_DATA_REQUESTS = 64
        private const val MAX_CONCURRENT_PAIRING_REQUESTS = 4
        private const val MAX_IDLE_DATA_KEEP_ALIVE_CONNECTIONS = 64
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_COUNT = 96
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_REQUEST_BODY_BYTES = 128L * 1024L * 1024L
        private const val MAX_BUFFERED_REQUEST_BODY_BYTES = 8 * 1024 * 1024
        private const val REQUEST_BODY_DISCARD_BUFFER_BYTES = 64 * 1024
        private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

private interface RawHttpConnection {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun writeFully(bytes: ByteArray, offset: Int, length: Int)

    fun writeAscii(value: String) {
        val bytes = value.encodeToByteArray()
        writeFully(bytes, 0, bytes.size)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosAcceptedSessionByteChannel(
    private val ssl: CPointer<SSL>,
) : DeviceSessionByteChannel {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return sslRead(ssl, buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        sslWriteFully(ssl, buffer, offset, length)
    }

    override suspend fun flush() = Unit

    override fun close() = Unit
}

@OptIn(ExperimentalForeignApi::class)
private class SslRawHttpConnection(
    private val ssl: CPointer<SSL>,
) : RawHttpConnection {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return sslRead(ssl, buffer, offset, length)
    }

    override fun writeFully(bytes: ByteArray, offset: Int, length: Int) {
        sslWriteFully(ssl, bytes, offset, length)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sslRead(ssl: CPointer<SSL>, buffer: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return 0
    buffer.usePinned { pinned ->
        return SSL_read(ssl, pinned.addressOf(offset), length)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sslWriteFully(ssl: CPointer<SSL>, bytes: ByteArray, offset: Int, length: Int) {
    if (length <= 0) return
    bytes.usePinned { pinned ->
        var writtenTotal = 0
        while (writtenTotal < length) {
            val written = SSL_write(ssl, pinned.addressOf(offset + writtenTotal), length - writtenTotal)
            if (written <= 0) throw Exception(AppStrings.ui_tls_write_error)
            writtenTotal += written
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun posixException(message: String): Exception {
    val detail = strerror(errno)?.toKString() ?: "unknown"
    return Exception("$message: $detail")
}

private fun Int.toNetworkOrderPort(): UShort {
    val value = this and 0xFFFF
    val networkOrder = ((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)
    return networkOrder.toUShort()
}
