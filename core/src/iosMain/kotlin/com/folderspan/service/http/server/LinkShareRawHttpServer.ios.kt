package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.service.http.server.linkshare.LinkShareHttpHeader
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequestBody
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBodyWriter
import com.folderspan.service.http.server.linkshare.LinkShareRequestBodyLimits
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.utils.LogKit
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.openssl.SSL
import platform.openssl.SSL_CTX
import platform.openssl.SSL_accept
import platform.openssl.SSL_free
import platform.openssl.SSL_new
import platform.openssl.SSL_read
import platform.openssl.SSL_set_fd
import platform.openssl.SSL_write
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.EOF
import platform.posix.IPPROTO_TCP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.MSG_PEEK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDBUF
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getnameinfo
import platform.posix.listen
import platform.posix.memset
import platform.posix.read
import platform.posix.recv
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socklen_t
import platform.posix.socklen_tVar
import platform.posix.socket
import platform.posix.strerror
import platform.posix.write
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalForeignApi::class)
internal class LinkShareRawHttpServer(
    private val scheme: String,
    private val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    private val handler: suspend (LinkShareHttpRequest) -> LinkShareHttpResponse,
    private val allowPlainHttpOnLan: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientSemaphore = Semaphore(MAX_CONCURRENT_CLIENTS)
    private var serverFd: Int = INVALID_FD
    private var acceptJob: kotlinx.coroutines.Job? = null
    private var serverContext: DeviceTlsIdentity.ServerContext? = null
    private val activeClientFds = mutableSetOf<Int>()
    private val activeClientFdsMutex = Mutex()

    fun start(port: Int, bindLoopback: Boolean = false) {
        if (acceptJob?.isActive == true || serverFd >= 0) {
            LogKit.i(AppStrings.ui_ios_linksharerawhttpserver_arg0_already_running_skip_repeated_startup.format(arg0 = scheme))
            return
        }
        val context = if (usesTlsContext()) {
            DeviceTlsIdentity.createServerContext()
        } else {
            null
        }
        val fd = try {
            createListeningSocket(port, bindLoopback)
        } catch (error: Throwable) {
            context?.close()
            throw error
        }
        serverContext = context
        serverFd = fd
        acceptJob = scope.launch {
            try {
                LogKit.i(AppStrings.ui_ios_linksharerawhttpserver_arg0_starts_listening_arg1.format(arg0 = scheme, arg1 = (port).toString()))
                while (isActive) {
                    val client = withContext(Dispatchers.Default) { acceptClient(fd) }
                    if (client.fd < 0) {
                        if (isActive) throw posixException(AppStrings.ui_accept_client_connection_failure)
                        break
                    }
                    if (!clientSemaphore.tryAcquire()) {
                        LogKit.w(AppStrings.ui_ios_linksharerawhttpserver_arg0_number_connections_has_reached_upper_limit.format(arg0 = scheme))
                        close(client.fd)
                        continue
                    }
                    trackClientFd(client.fd)
                    launch {
                        try {
                            handleClient(context?.pointer, client.fd, client.remoteHost)
                        } finally {
                            untrackClientFd(client.fd)
                            clientSemaphore.release()
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException(AppStrings.ui_ios_linksharerawhttpserver_arg0_has_stopped.format(arg0 = scheme))
            } catch (error: Throwable) {
                if (isActive) {
                    LogKit.e(AppStrings.ui_ios_linksharerawhttpserver_arg0_accept_exception_arg1.format(arg0 = scheme, arg1 = (error.message).toString()), error)
                }
            } finally {
                closeServerSocket(fd, context)
            }
        }
    }

    suspend fun stop() {
        val fd = serverFd
        serverFd = INVALID_FD
        if (fd >= 0) close(fd)
        closeActiveClientFds()
        acceptJob?.cancelAndJoin()
        acceptJob = null
        closeActiveClientFds()
        serverContext?.close()
        serverContext = null
    }

    fun isRunning(): Boolean = serverFd >= 0 && acceptJob?.isActive == true

    private suspend fun trackClientFd(fd: Int) {
        activeClientFdsMutex.withLock {
            activeClientFds += fd
        }
    }

    private suspend fun untrackClientFd(fd: Int) {
        activeClientFdsMutex.withLock {
            activeClientFds -= fd
        }
    }

    private suspend fun closeActiveClientFds() {
        val fds = activeClientFdsMutex.withLock {
            val snapshot = activeClientFds.toList()
            activeClientFds.clear()
            snapshot
        }
        fds.forEach { fd -> close(fd) }
    }

    private suspend fun handleClient(ctx: CPointer<SSL_CTX>?, clientFd: Int, remoteHost: String?) {
        val requestScheme = resolveConnectionScheme(clientFd)
        if (requestScheme == null) {
            close(clientFd)
            return
        }
        if (
            !allowPlainHttpOnLan &&
            requestScheme.equals("http", ignoreCase = true) &&
            !isLoopbackRemoteHost(remoteHost)
        ) {
            close(clientFd)
            return
        }
        val ssl = if (requestScheme.equals("https", ignoreCase = true)) ctx?.let { context -> SSL_new(context) } else null
        if (requestScheme.equals("https", ignoreCase = true) && ssl == null) {
            close(clientFd)
            return
        }
        var connection: LinkShareConnection? = null
        try {
            if (ssl != null && (SSL_set_fd(ssl, clientFd) != 1 || SSL_accept(ssl) != 1)) {
                return
            }
            val activeConnection = if (ssl != null) SslLinkShareConnection(ssl) else FdLinkShareConnection(clientFd)
            connection = activeConnection
            while (currentCoroutineContext().isActive) {
                val request = try {
                    parseRequest(activeConnection, remoteHost, requestScheme)
                } catch (_: LinkShareRawEofException) {
                    break
                }
                var keepAlive = request.shouldKeepAliveConnection()
                val response = handler(request)
                if (keepAlive) {
                    keepAlive = runCatching {
                        request.body.discard()
                        true
                    }.getOrDefault(false)
                }
                writeResponse(activeConnection, response, keepAlive)
                if (!keepAlive) break
            }
        } catch (error: Throwable) {
            if (error is LinkShareRequestBodyTooLargeException) {
                runCatching {
                    connection?.let { writeResponse(it, requestBodyTooLargeResponse(), keepAlive = false) }
                }
                return
            }
            if (!error.isExpectedClientDisconnect()) {
                LogKit.e(AppStrings.ui_ios_linksharerawhttpserver_arg0_arg1_request_processing_exception_arg2.format(arg0 = scheme, arg1 = requestScheme, arg2 = (error.message).toString()), error)
                runCatching { connection?.let { writeResponse(it, LinkShareHttpResponse.bytes(500), keepAlive = false) } }
            }
        } finally {
            if (ssl != null) SSL_free(ssl)
            close(clientFd)
        }
    }

    private fun parseRequest(
        connection: LinkShareConnection,
        remoteHost: String?,
        requestScheme: String,
    ): LinkShareHttpRequest {
        val requestLine = readHttpLine(connection).takeIf { item -> item.isNotBlank() }
            ?: throw LinkShareRawEofException(AppStrings.ui_empty_request_line)
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) throw IllegalArgumentException(AppStrings.ui_http_request_line_invalid)
        val method = requestParts[0].trim()
        val target = requestParts[1].trim().ifBlank { "/" }
        val headers = linkedMapOf<String, MutableList<String>>()
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
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            headers.getOrPut(name) { mutableListOf() } += value
        }
        val headerMap = headers.mapValues { item -> item.value.toList() }
        val contentLength = headerMap.firstHeader("Content-Length")?.toLongOrNull() ?: 0L
        val transferEncodings = headerMap.firstHeader("Transfer-Encoding")
            ?.split(",")
            ?.map { item -> item.trim().lowercase() }
            .orEmpty()
        val isUpload = LinkShareRequestBodyLimits.isStreamingUploadPath(target)
        val isChunked = "chunked" in transferEncodings
        val maxBodyBytes = LinkShareRequestBodyLimits.maxBodyBytes(target, maxRequestBodyBytes)
        if (contentLength !in 0L..maxBodyBytes) throw LinkShareRequestBodyTooLargeException()
        if (!isUpload && !LinkShareRequestBodyLimits.allowsNonEmptyBody(method) && (contentLength > 0L || isChunked)) {
            throw LinkShareRequestBodyTooLargeException()
        }
        val body = when {
            isChunked -> {
                if (isUpload) throw LinkShareRequestBodyTooLargeException()
                LinkShareHttpRequestBody.Bytes(readChunkedBody(connection, maxBodyBytes))
            }
            contentLength <= 0L -> LinkShareHttpRequestBody.Empty
            isUpload -> ConnectionLinkShareRequestBody(connection, contentLength)
            else -> LinkShareHttpRequestBody.Bytes(readExactCapped(connection, contentLength.toInt(), maxBodyBytes))
        }
        val defaultPort = if (requestScheme.equals("https", ignoreCase = true)) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        val endpoint = headerMap.hostAndPort(defaultPort)
        return LinkShareHttpRequest.from(
            method = method,
            rawUri = target,
            headers = headerMap,
            remoteHost = remoteHost,
            scheme = requestScheme,
            host = endpoint.first,
            port = endpoint.second,
            body = body,
        )
    }

    private fun usesTlsContext(): Boolean {
        return scheme.equals("https", ignoreCase = true) || scheme.equals(AUTO_SCHEME, ignoreCase = true)
    }

    private fun resolveConnectionScheme(clientFd: Int): String? {
        if (!scheme.equals(AUTO_SCHEME, ignoreCase = true)) return scheme
        val firstBytes = peekInitialBytes(clientFd)
        if (firstBytes.isEmpty()) return null
        return if (isHttpRequest(firstBytes)) "http" else "https"
    }

    private fun peekInitialBytes(fd: Int): ByteArray {
        val buffer = ByteArray(INITIAL_BYTES)
        buffer.usePinned { pinned ->
            val count = recv(fd, pinned.addressOf(0), buffer.size.convert(), MSG_PEEK)
            if (count <= 0) return byteArrayOf()
            return buffer.copyOf(count.toInt())
        }
    }

    private fun isHttpRequest(bytes: ByteArray): Boolean {
        val prefix = bytes.decodeToString().uppercase()
        return HTTP_METHOD_PREFIXES.any { method -> method.startsWith(prefix) || prefix.startsWith(method) }
    }

    private fun isLoopbackRemoteHost(remoteHost: String?): Boolean {
        val host = remoteHost?.trim()?.removeSurrounding("[", "]")?.substringBefore('%')?.lowercase()
            ?: return false
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val ipv4 = host.removePrefix("::ffff:")
        val octets = ipv4.split('.')
        if (octets.size != 4) return false
        val parsed = octets.map { part -> part.toIntOrNull() ?: return false }
        return parsed.all { octet -> octet in 0..255 } && parsed[0] == 127
    }

    private suspend fun writeResponse(connection: LinkShareConnection, response: LinkShareHttpResponse, keepAlive: Boolean) {
        val headers = response.headers.toMutableList()
        if (headers.none { item -> item.name.equals("Connection", ignoreCase = true) }) {
            headers += LinkShareHttpHeader("Connection", if (keepAlive) "keep-alive" else "close")
        }
        when (val body = response.body) {
            LinkShareHttpResponseBody.Empty -> {
                if (headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    headers += LinkShareHttpHeader("Content-Length", "0")
                }
                writeStatusAndHeaders(connection, response, headers)
            }
            is LinkShareHttpResponseBody.Bytes -> {
                if (headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    headers += LinkShareHttpHeader("Content-Length", body.bytes.size.toString())
                }
                writeStatusAndHeaders(connection, response, headers)
                connection.writeFully(body.bytes, 0, body.bytes.size)
            }
            is LinkShareHttpResponseBody.Stream -> {
                val writer = if (body.contentLength == null &&
                    headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }
                ) {
                    headers += LinkShareHttpHeader("Transfer-Encoding", "chunked")
                    writeStatusAndHeaders(connection, response, headers)
                    ChunkedConnectionOutputWriter(connection)
                } else {
                    writeStatusAndHeaders(connection, response, headers)
                    FixedConnectionOutputWriter(connection)
                }
                body.writer.invoke(writer)
                if (writer is ChunkedConnectionOutputWriter) writer.finish()
            }
        }
    }

    private fun writeStatusAndHeaders(
        connection: LinkShareConnection,
        response: LinkShareHttpResponse,
        headers: List<LinkShareHttpHeader>,
    ) {
        connection.writeAscii("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
        headers.forEach { header -> connection.writeAscii("${header.name}: ${header.value}\r\n") }
        connection.writeAscii("\r\n")
    }

    private class FixedConnectionOutputWriter(private val connection: LinkShareConnection) : LinkShareHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            connection.writeFully(bytes, offset, length)
        }

        override suspend fun flush() = Unit
    }

    private class ChunkedConnectionOutputWriter(private val connection: LinkShareConnection) : LinkShareHttpResponseBodyWriter {
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

    private class ConnectionLinkShareRequestBody(
        private val connection: LinkShareConnection,
        override val contentLength: Long,
    ) : LinkShareHttpRequestBody {
        override var remainingBytes: Long = contentLength
            private set

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (remainingBytes <= 0L) return EOF
            val readLength = minOf(length.toLong(), remainingBytes).toInt()
            val read = connection.read(buffer, offset, readLength)
            if (read > 0) remainingBytes -= read.toLong()
            return read
        }

        override suspend fun discard() {
            val buffer = ByteArray(minOf(REQUEST_BODY_DISCARD_BUFFER_BYTES.toLong(), remainingBytes).coerceAtLeast(1L).toInt())
            while (remainingBytes > 0L) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remainingBytes).toInt())
                if (read < 0) throw LinkShareRawEofException(AppStrings.ui_the_request_body_is_incomplete)
            }
        }
    }

    private fun createListeningSocket(port: Int, bindLoopback: Boolean): Int = memScoped {
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
            address.sin_addr.s_addr = if (bindLoopback) IPV4_LOOPBACK_NETWORK_ORDER else 0u
            if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                throw posixException(AppStrings.ui_binding_port_arg0_failed.format(arg0 = (port).toString()))
            }
            if (listen(fd, BACKLOG) != 0) throw posixException(AppStrings.ui_listening_port_arg0_failed.format(arg0 = (port).toString()))
            fd
        } catch (error: Throwable) {
            close(fd)
            throw error
        }
    }

    private fun acceptClient(serverFd: Int): AcceptedClient = memScoped {
        val address = alloc<sockaddr_in6>()
        val addressLength = alloc<socklen_tVar>()
        addressLength.value = sizeOf<sockaddr_in6>().convert()
        memset(address.ptr, 0, sizeOf<sockaddr_in6>().convert())
        val fd = accept(serverFd, address.ptr.reinterpret(), addressLength.ptr)
        if (fd < 0) return@memScoped AcceptedClient(fd, null)
        configureClientSocket(fd)
        AcceptedClient(fd, numericHost(address.ptr.reinterpret(), addressLength.value))
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

    private fun closeServerSocket(fd: Int, context: DeviceTlsIdentity.ServerContext?) {
        if (serverFd == fd) serverFd = INVALID_FD
        runCatching { close(fd) }
        if (context != null && serverContext === context) {
            serverContext = null
            context.close()
        }
    }

    private fun numericHost(address: CPointer<sockaddr>, addressLength: socklen_t): String? = memScoped {
        val family = address.pointed.sa_family.toInt()
        if (family != AF_INET && family != AF_INET6) return@memScoped null
        val host = allocArray<ByteVar>(NI_MAXHOST)
        val result = getnameinfo(address, addressLength, host, NI_MAXHOST.convert(), null, 0u, NI_NUMERICHOST)
        if (result == 0) host.toKString().substringBefore("%").takeIf { item -> item.isNotBlank() } else null
    }

    private fun readHttpLine(connection: LinkShareConnection): String {
        val bytes = mutableListOf<Byte>()
        val one = ByteArray(1)
        while (true) {
            val read = connection.read(one, 0, 1)
            if (read <= 0) {
                if (bytes.isEmpty()) throw LinkShareRawEofException(AppStrings.ui_connection_closed)
                break
            }
            val next = one[0].toInt() and 0xFF
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.add(one[0])
            if (bytes.size > MAX_HEADER_LINE_BYTES) throw IllegalArgumentException(AppStrings.ui_http_header_too_long)
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun readExactCapped(connection: LinkShareConnection, length: Int, maxBodyBytes: Long): ByteArray {
        if (length.toLong() !in 0L..maxBodyBytes) throw LinkShareRequestBodyTooLargeException()
        val chunks = mutableListOf<ByteArray>()
        var remaining = length
        while (remaining > 0) {
            val readLength = minOf(remaining, LinkShareRequestBodyLimits.READ_EXACT_CHUNK_BYTES)
            val chunk = ByteArray(readLength)
            var offset = 0
            while (offset < readLength) {
                val read = connection.read(chunk, offset, readLength - offset)
                if (read < 0) throw LinkShareRawEofException(AppStrings.ui_the_request_body_is_incomplete)
                offset += read
            }
            chunks.add(if (offset == chunk.size) chunk else chunk.copyOf(offset))
            remaining -= offset
        }
        if (chunks.size == 1) return chunks[0]
        val result = ByteArray(length)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }

    private fun readChunkedBody(connection: LinkShareConnection, maxBodyBytes: Long): ByteArray {
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
                throw LinkShareRequestBodyTooLargeException()
            }
            chunks.add(readExactCapped(connection, chunkSize.toInt(), maxBodyBytes))
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

    private fun Map<String, List<String>>.firstHeader(name: String): String? {
        return entries.firstOrNull { item -> item.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun Map<String, List<String>>.hostAndPort(defaultPort: Int): Pair<String, Int> {
        val hostHeader = firstHeader("Host").orEmpty()
        val separator = hostHeader.lastIndexOf(':')
        if (separator > 0 && hostHeader.indexOf(':') == separator) {
            return hostHeader.substring(0, separator) to
                (hostHeader.substring(separator + 1).toIntOrNull() ?: defaultPort)
        }
        return hostHeader to defaultPort
    }

    private fun LinkShareHttpRequest.shouldKeepAliveConnection(): Boolean {
        val connectionTokens = header("Connection")?.split(",")?.map { item -> item.trim().lowercase() }.orEmpty()
        return "close" !in connectionTokens
    }

    private fun Throwable.isExpectedClientDisconnect(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (
                current is LinkShareRawEofException ||
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed") ||
                message.contains("certificate_unknown") ||
                message.contains("unknown certificate") ||
                message.contains("bad_certificate") ||
                message.contains("certificate verify failed") ||
                message.contains(AppStrings.ui_connection_closed.lowercase()) ||
                message.contains(AppStrings.ui_connection_closed)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private class LinkShareRawEofException(message: String) : Exception(message)
    private data class AcceptedClient(val fd: Int, val remoteHost: String?)

    companion object {
        private const val INVALID_FD = -1
        private const val BACKLOG = 64
        private const val MAX_CONCURRENT_CLIENTS = 64
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_COUNT = 96
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val DEFAULT_MAX_REQUEST_BODY_BYTES = LinkShareRequestBodyLimits.MAX_BUFFERED_REQUEST_BODY_BYTES
        private const val REQUEST_BODY_DISCARD_BUFFER_BYTES = 64 * 1024
        private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443
        private const val AUTO_SCHEME = "auto"
        private const val INITIAL_BYTES = 8
        private val HTTP_METHOD_PREFIXES = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ")
    }
}

private class LinkShareRequestBodyTooLargeException : IllegalArgumentException(AppStrings.ui_the_request_body_is_too_large)

private fun requestBodyTooLargeResponse(): LinkShareHttpResponse = LinkShareHttpResponse.bytes(
    statusCode = 413,
    headers = listOf(LinkShareHttpHeader("Content-Type", "application/json; charset=utf-8")),
    bytes = "{\"error\":\"request body exceeds limit\"}".encodeToByteArray(),
)

private interface LinkShareConnection {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun writeFully(bytes: ByteArray, offset: Int, length: Int)

    fun writeAscii(value: String) {
        val bytes = value.encodeToByteArray()
        writeFully(bytes, 0, bytes.size)
    }
}

private class FdLinkShareConnection(private val fd: Int) : LinkShareConnection {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return fdRead(fd, buffer, offset, length)
    }

    override fun writeFully(bytes: ByteArray, offset: Int, length: Int) {
        fdWriteFully(fd, bytes, offset, length)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class SslLinkShareConnection(private val ssl: CPointer<SSL>) : LinkShareConnection {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return sslRead(ssl, buffer, offset, length)
    }

    override fun writeFully(bytes: ByteArray, offset: Int, length: Int) {
        sslWriteFully(ssl, bytes, offset, length)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fdRead(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return 0
    buffer.usePinned { pinned ->
        return read(fd, pinned.addressOf(offset), length.convert()).toInt()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fdWriteFully(fd: Int, bytes: ByteArray, offset: Int, length: Int) {
    if (length <= 0) return
    bytes.usePinned { pinned ->
        var writtenTotal = 0
        while (writtenTotal < length) {
            val written = write(fd, pinned.addressOf(offset + writtenTotal), (length - writtenTotal).convert()).toInt()
            if (written <= 0) throw Exception(AppStrings.ui_socket_write_failed)
            writtenTotal += written
        }
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

private const val IPV4_LOOPBACK_NETWORK_ORDER = 0x0100007Fu
