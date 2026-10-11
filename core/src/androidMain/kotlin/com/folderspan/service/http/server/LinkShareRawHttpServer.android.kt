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
import com.folderspan.service.http.tls.restrictToModernTls
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal class LinkShareRawHttpServer(
    private val scheme: String,
    private val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    private val handler: suspend (LinkShareHttpRequest) -> LinkShareHttpResponse,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientSemaphore = Semaphore(MAX_CONCURRENT_CLIENTS)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: kotlinx.coroutines.Job? = null
    private val activeClients = mutableSetOf<Socket>()

    fun start(port: Int, host: String? = null, tls: Boolean = false) {
        if (acceptJob?.isActive == true || serverSocket != null) {
            LogKit.i(AppStrings.ui_linksharerawhttpserver_arg0_already_running_skip_repeated_startup.format(arg0 = scheme))
            return
        }
        val socket = createServerSocket(port, host, tls)
        serverSocket = socket
        acceptJob = scope.launch {
            try {
                LogKit.i(AppStrings.ui_linksharerawhttpserver_arg0_starts_listening_arg1_arg2.format(arg0 = scheme, arg1 = host ?: "0.0.0.0", arg2 = (port).toString()))
                while (isActive) {
                    val client = withContext(Dispatchers.IO) { socket.accept() }
                    if (!clientSemaphore.tryAcquire()) {
                        LogKit.w(AppStrings.ui_linksharerawhttpserver_arg0_number_connections_has_reached_upper_limit_new.format(arg0 = scheme))
                        runCatching { client.close() }
                        continue
                    }
                    trackClient(client)
                    launch {
                        try {
                            handleClient(client)
                        } finally {
                            untrackClient(client)
                            clientSemaphore.release()
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException(AppStrings.ui_linksharerawhttpserver_arg0_has_stopped.format(arg0 = (scheme).toString()))
            } catch (error: Throwable) {
                if (isActive) {
                    LogKit.e(AppStrings.ui_linksharerawhttpserver_arg0_accept_exception_arg1.format(arg0 = scheme, arg1 = (error.message).toString()), error)
                }
            } finally {
                runCatching { socket.close() }
                if (serverSocket === socket) serverSocket = null
            }
        }
    }

    suspend fun stop() {
        val job = withContext(NonCancellable + Dispatchers.IO) {
            val socket = serverSocket
            serverSocket = null
            runCatching { socket?.close() }
            closeActiveClients()
            acceptJob.also { activeJob ->
                acceptJob = null
                activeJob?.cancel()
            }
        }
        try {
            job?.join()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                closeActiveClients()
            }
        }
    }

    fun isRunning(): Boolean = serverSocket != null && acceptJob?.isActive == true

    private fun trackClient(socket: Socket) {
        synchronized(activeClients) {
            activeClients += socket
        }
    }

    private fun untrackClient(socket: Socket) {
        synchronized(activeClients) {
            activeClients -= socket
        }
    }

    private fun closeActiveClients() {
        val clients = synchronized(activeClients) { activeClients.toList() }
        clients.forEach { client -> runCatching { client.close() } }
    }

    private fun createServerSocket(port: Int, host: String?, tls: Boolean): ServerSocket {
        val bindAddress = host?.let { InetAddress.getByName(it) }
        return if (tls) {
            val socket = if (bindAddress == null) {
                DeviceTlsIdentity.createServerSslContext().serverSocketFactory.createServerSocket(port)
            } else {
                DeviceTlsIdentity.createServerSslContext().serverSocketFactory.createServerSocket(port, BACKLOG, bindAddress)
            } as SSLServerSocket
            socket.restrictToModernTls()
            socket
        } else {
            val factory = ServerSocketFactory.getDefault()
            if (bindAddress == null) {
                factory.createServerSocket(port)
            } else {
                factory.createServerSocket(port, BACKLOG, bindAddress)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            withContext(Dispatchers.IO) {
                try {
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    if (client is SSLSocket) client.startHandshake()
                    val output = client.getOutputStream()
                    while (currentCoroutineContext().isActive) {
                        val request = try {
                            parseRequest(client)
                        } catch (_: EOFException) {
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
                        writeResponse(output, response, keepAlive)
                        if (!keepAlive) break
                    }
                } catch (_: EOFException) {
                    // 客户端提前断开，忽略。
                } catch (_: LinkShareRequestBodyTooLargeException) {
                    runCatching {
                        writeResponse(client.getOutputStream(), requestBodyTooLargeResponse(), keepAlive = false)
                    }
                } catch (error: Throwable) {
                    if (error.isExpectedLinkShareClientDisconnect()) return@withContext
                    LogKit.e(AppStrings.ui_linksharerawhttpserver_arg0_request_processing_exception_arg1.format(arg0 = scheme, arg1 = (error.message).toString()), error)
                    runCatching {
                        writeResponse(
                            client.getOutputStream(),
                            LinkShareHttpResponse.bytes(500),
                            keepAlive = false
                        )
                    }
                }
            }
        }
    }

    private fun parseRequest(socket: Socket): LinkShareHttpRequest {
        val input = socket.getInputStream()
        val requestLine = input.readHttpLine().takeIf { item -> item.isNotBlank() }
            ?: throw EOFException(AppStrings.ui_empty_request_line)
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) throw IllegalArgumentException(AppStrings.ui_http_request_line_invalid)
        val method = requestParts[0].trim()
        val target = requestParts[1].trim().ifBlank { "/" }
        val headers = linkedMapOf<String, MutableList<String>>()
        var headerCount = 0
        var headerBytes = 0
        while (true) {
            val line = input.readHttpLine()
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
                LinkShareHttpRequestBody.Bytes(input.readChunkedBody(maxBodyBytes))
            }
            contentLength <= 0L -> LinkShareHttpRequestBody.Empty
            isUpload -> InputStreamLinkShareRequestBody(input, contentLength)
            else -> LinkShareHttpRequestBody.Bytes(input.readExactCapped(contentLength.toInt(), maxBodyBytes))
        }
        val endpoint = headerMap.hostAndPort(socket.localPort)
        return LinkShareHttpRequest.from(
            method = method,
            rawUri = target,
            headers = headerMap,
            remoteHost = socket.inetAddress?.hostAddress,
            scheme = scheme,
            host = endpoint.first,
            port = endpoint.second,
            body = body,
        )
    }

    private suspend fun writeResponse(
        output: OutputStream,
        response: LinkShareHttpResponse,
        keepAlive: Boolean,
    ) = withContext(Dispatchers.IO) {
        val headers = response.headers.toMutableList()
        if (headers.none { item -> item.name.equals("Connection", ignoreCase = true) }) {
            headers += LinkShareHttpHeader(
                "Connection",
                if (keepAlive) "keep-alive" else "close"
            )
        }
        when (val body = response.body) {
            LinkShareHttpResponseBody.Empty -> {
                if (headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    headers += LinkShareHttpHeader("Content-Length", "0")
                }
                output.writeStatusAndHeaders(response, headers)
                output.flush()
            }

            is LinkShareHttpResponseBody.Bytes -> {
                if (headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }) {
                    headers += LinkShareHttpHeader(
                        "Content-Length",
                        body.bytes.size.toString()
                    )
                }
                output.writeStatusAndHeaders(response, headers)
                output.write(body.bytes)
                output.flush()
            }

            is LinkShareHttpResponseBody.Stream -> {
                val writer = if (body.contentLength == null &&
                    headers.none { item -> item.name.equals("Content-Length", ignoreCase = true) }
                ) {
                    headers += LinkShareHttpHeader("Transfer-Encoding", "chunked")
                    output.writeStatusAndHeaders(response, headers)
                    ChunkedOutputWriter(output)
                } else {
                    output.writeStatusAndHeaders(response, headers)
                    FixedOutputWriter(output)
                }
                body.writer.invoke(writer)
                if (writer is ChunkedOutputWriter) writer.finish()
                output.flush()
            }
        }
    }

    private fun OutputStream.writeStatusAndHeaders(
        response: LinkShareHttpResponse,
        headers: List<LinkShareHttpHeader>,
    ) {
        writeAscii("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
        headers.forEach { header -> writeAscii("${header.name}: ${header.value}\r\n") }
        writeAscii("\r\n")
    }

    private class FixedOutputWriter(private val output: OutputStream) : LinkShareHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
            output.write(bytes, offset, length)
        }

        override suspend fun flush() = withContext(Dispatchers.IO) {
            output.flush()
        }
    }

    private class ChunkedOutputWriter(private val output: OutputStream) : LinkShareHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
            if (length <= 0) return@withContext
            output.write(length.toString(16).encodeToByteArray())
            output.write("\r\n".encodeToByteArray())
            output.write(bytes, offset, length)
            output.write("\r\n".encodeToByteArray())
        }

        override suspend fun flush() = withContext(Dispatchers.IO) {
            output.flush()
        }

        fun finish() {
            output.write("0\r\n\r\n".encodeToByteArray())
        }
    }

    private class InputStreamLinkShareRequestBody(
        private val input: InputStream,
        override val contentLength: Long,
    ) : LinkShareHttpRequestBody {
        override var remainingBytes: Long = contentLength
            private set

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
            if (length <= 0) return@withContext 0
            if (remainingBytes <= 0L) return@withContext -1
            val readLength = minOf(length.toLong(), remainingBytes).toInt()
            val read = input.read(buffer, offset, readLength)
            if (read > 0) remainingBytes -= read.toLong()
            read
        }

        override suspend fun discard() {
            val buffer = ByteArray(minOf(REQUEST_BODY_DISCARD_BUFFER_BYTES.toLong(), remainingBytes).coerceAtLeast(1L).toInt())
            while (remainingBytes > 0L) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remainingBytes).toInt())
                if (read < 0) throw EOFException(AppStrings.ui_the_request_body_is_incomplete)
            }
        }
    }

    private fun InputStream.readHttpLine(): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = read()
            if (next < 0) {
                if (bytes.isEmpty()) throw EOFException(AppStrings.ui_connection_closed)
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.add(next.toByte())
            if (bytes.size > MAX_HEADER_LINE_BYTES) throw IllegalArgumentException(AppStrings.ui_http_header_too_long)
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun InputStream.readExactCapped(length: Int, maxBodyBytes: Long): ByteArray {
        if (length.toLong() !in 0L..maxBodyBytes) throw LinkShareRequestBodyTooLargeException()
        val chunks = mutableListOf<ByteArray>()
        var remaining = length
        while (remaining > 0) {
            val readLength = minOf(remaining, LinkShareRequestBodyLimits.READ_EXACT_CHUNK_BYTES)
            val chunk = ByteArray(readLength)
            var offset = 0
            while (offset < readLength) {
                val read = read(chunk, offset, readLength - offset)
                if (read < 0) throw EOFException(AppStrings.ui_the_request_body_is_incomplete)
                offset += read
            }
            chunks += if (offset == chunk.size) chunk else chunk.copyOf(offset)
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

    private fun InputStream.readChunkedBody(maxBodyBytes: Long): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0L
        while (true) {
            val sizeLine = readHttpLine()
            val chunkSize = sizeLine.substringBefore(";").trim().toLongOrNull(16)
                ?: throw IllegalArgumentException(AppStrings.ui_invalid_chunked_request_body)
            if (chunkSize < 0L) throw IllegalArgumentException(AppStrings.ui_invalid_chunked_request_body)
            if (chunkSize == 0L) {
                while (true) {
                    if (readHttpLine().isEmpty()) break
                }
                break
            }
            totalBytes += chunkSize
            if (totalBytes > maxBodyBytes || chunkSize > Int.MAX_VALUE) throw LinkShareRequestBodyTooLargeException()
            chunks += readExactCapped(chunkSize.toInt(), maxBodyBytes)
            val terminator = readHttpLine()
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

    private fun Map<String, List<String>>.hostAndPort(localPort: Int): Pair<String, Int> {
        val hostHeader = firstHeader("Host").orEmpty()
        if (hostHeader.startsWith("[")) {
            val end = hostHeader.indexOf(']')
            if (end > 0) {
                val host = hostHeader.substring(1, end)
                val port = hostHeader.substring(end + 1).removePrefix(":").toIntOrNull() ?: localPort
                return host to port
            }
        }
        val separator = hostHeader.lastIndexOf(':')
        if (separator > 0 && hostHeader.indexOf(':') == separator) {
            val host = hostHeader.substring(0, separator)
            val port = hostHeader.substring(separator + 1).toIntOrNull() ?: localPort
            return host to port
        }
        return hostHeader to localPort
    }

    private fun LinkShareHttpRequest.shouldKeepAliveConnection(): Boolean {
        val connectionTokens = header("Connection")
            ?.split(",")
            ?.map { item -> item.trim().lowercase() }
            .orEmpty()
        return "close" !in connectionTokens
    }

    private fun OutputStream.writeAscii(value: String) {
        write(value.encodeToByteArray())
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5 * 60 * 1000
        private const val MAX_CONCURRENT_CLIENTS = 64
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_COUNT = 96
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val DEFAULT_MAX_REQUEST_BODY_BYTES = LinkShareRequestBodyLimits.MAX_BUFFERED_REQUEST_BODY_BYTES
        private const val REQUEST_BODY_DISCARD_BUFFER_BYTES = 64 * 1024
        private const val BACKLOG = 64
    }
}

private class LinkShareRequestBodyTooLargeException : IllegalArgumentException(AppStrings.ui_the_request_body_is_too_large)

private fun requestBodyTooLargeResponse(): LinkShareHttpResponse = LinkShareHttpResponse.bytes(
    statusCode = 413,
    headers = listOf(LinkShareHttpHeader("Content-Type", "application/json; charset=utf-8")),
    bytes = "{\"error\":\"request body exceeds limit\"}".encodeToByteArray(),
)

internal fun Throwable.isExpectedLinkShareClientDisconnect(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is EOFException || current is SocketTimeoutException) return true
        if (current is SocketException || current is SSLException) {
            val message = current.message.orEmpty().lowercase()
            if (
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed") ||
                message.contains("closed inbound") ||
                message.contains("close_notify") ||
                message.contains("certificate_unknown") ||
                message.contains("unknown certificate") ||
                message.contains("bad_certificate") ||
                message.contains("certificate verify failed")
            ) {
                return true
            }
        }
        current = current.cause
    }
    return false
}
