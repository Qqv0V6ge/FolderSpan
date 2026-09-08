package com.folderspan.service.http.server.raw

import strings.AppStrings

import com.folderspan.routes.*
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_HEADER
import com.folderspan.service.http.crypto.HTTP_ENCRYPTED_PAYLOAD_VERSION
import com.folderspan.service.http.server.HttpPortSchemeSwitchingProxy
import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.restrictToModernTls
import com.folderspan.service.session.AndroidDeviceSessionByteChannel
import com.folderspan.service.session.DEVICE_SESSION_ALPN
import com.folderspan.service.session.DeviceLanBeaconAdvertiser
import com.folderspan.service.session.DeviceSessionServerApprovalContext
import com.folderspan.service.session.DeviceSessionServerRuntimeLauncher
import com.folderspan.service.session.advertiseDeviceSessionAlpn
import com.folderspan.service.session.hasDeviceSessionAlpn
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.utils.LogKit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class RawTlsHttpServer internal constructor(
    private val allocateInternalPort: (IntArray) -> Int,
) {
    constructor() : this(::allocateInternalRawHttpServerPort)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private val schemeSwitchingProxy = HttpPortSchemeSwitchingProxy()
    private var plainServerSocket: ServerSocket? = null
    private var plainAcceptJob: Job? = null
    private var tlsServerSocket: SSLServerSocket? = null
    private var tlsAcceptJob: Job? = null
    private val beaconAdvertiser = DeviceLanBeaconAdvertiser(scope)

    fun start(port: Int) {
        if (isRunning()) {
            LogKit.i(AppStrings.ui_raw_tls_file_sharing_service_already_running_skip_repeated)
            return
        }
        try {
            // 先绑定审批端口，避免内部临时监听器占用 Session 派生出的审批端口。
            shareApprovalServer.start(defaultDeviceShareApprovalPort(port), tls = true)
            val internalHttpPort = allocateInternalPort(intArrayOf(port))
            val internalTlsPort = allocateInternalPort(intArrayOf(port, internalHttpPort))
            schemeSwitchingProxy.start(
                publicHttpPort = port,
                internalHttpPort = internalHttpPort,
                internalTlsPort = internalTlsPort,
                bindLan = true,
                allowPlainHttpOnLan = false,
            )
            startPlainHttpServer(internalHttpPort)
            startTlsHttpServer(internalTlsPort)
            beaconAdvertiser.start(port)
            LogKit.i(AppStrings.ui_raw_tls_http_file_sharing_service_starts_listening_arg0.format(arg0 = (port).toString()))
        } catch (error: Throwable) {
            runBlocking { stop() }
            throw error
        }
    }

    private fun startPlainHttpServer(port: Int) {
        val socket = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
        plainServerSocket = socket
        plainAcceptJob = scope.launch {
            try {
                while (isActive) {
                    val client = withContext(Dispatchers.IO) { socket.accept() }
                    if (!clientSemaphore.tryAcquire()) {
                        LogKit.w(AppStrings.ui_number_raw_http_connections_has_reached_upper_limit_new)
                        runCatching { client.close() }
                        continue
                    }
                    launch {
                        try {
                            handleClient(client)
                        } finally {
                            clientSemaphore.release()
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException(AppStrings.ui_http_file_sharing_service_has_stopped)
            } catch (error: Throwable) {
                if (isActive) {
                    LogKit.e(AppStrings.ui_raw_http_file_sharing_service_accept_exception_arg0.format(arg0 = (error.message).toString()), error)
                }
            } finally {
                runCatching { socket.close() }
                if (plainServerSocket === socket) plainServerSocket = null
            }
        }
    }

    private fun startTlsHttpServer(port: Int) {
        val socket = DeviceTlsIdentity.createSessionServerSslContext()
            .serverSocketFactory
            .createServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        socket.restrictToModernTls()
        socket.advertiseDeviceSessionAlpn()
        tlsServerSocket = socket
        tlsAcceptJob = scope.launch {
            try {
                while (isActive) {
                    val client = withContext(Dispatchers.IO) { socket.accept() as SSLSocket }
                    if (!clientSemaphore.tryAcquire()) {
                        LogKit.w(AppStrings.ui_number_raw_tls_http_connections_has_reached_upper_limit)
                        runCatching { client.close() }
                        continue
                    }
                    launch {
                        try {
                            handleClient(client)
                        } finally {
                            clientSemaphore.release()
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException(AppStrings.ui_raw_tls_file_sharing_service_has_stopped)
            } catch (error: Throwable) {
                if (isActive) {
                    LogKit.e(AppStrings.ui_raw_tls_file_sharing_service_accept_exception_arg0.format(arg0 = (error.message).toString()), error)
                }
            } finally {
                runCatching { socket.close() }
                if (tlsServerSocket === socket) tlsServerSocket = null
            }
        }
    }

    suspend fun stop() {
        runCatching { schemeSwitchingProxy.stop() }
        runCatching { shareApprovalServer.stop() }
        beaconAdvertiser.stop()
        val plainSocket = plainServerSocket
        plainServerSocket = null
        runCatching { plainSocket?.close() }
        val tlsSocket = tlsServerSocket
        tlsServerSocket = null
        runCatching { tlsSocket?.close() }
        plainAcceptJob?.cancelAndJoin()
        plainAcceptJob = null
        tlsAcceptJob?.cancelAndJoin()
        tlsAcceptJob = null
    }

    fun isRunning(): Boolean =
        (plainServerSocket != null && plainAcceptJob?.isActive == true) ||
            (tlsServerSocket != null && tlsAcceptJob?.isActive == true)

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            var idleDataKeepAlivePermit: RawHttpCapacityPermit? = null
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                if (client is SSLSocket) {
                    client.advertiseDeviceSessionAlpn()
                    LogKit.d(AppStrings.ui_device_session_tls_handshake_remote_arg0.format(arg0 = (client.inetAddress?.hostAddress).toString()))
                    client.startHandshake()
                    if (!client.hasDeviceSessionAlpn()) {
                        LogKit.w(AppStrings.ui_device_session_rejected_connection_missing_alpn_arg0_remote_arg1.format(arg0 = (DEVICE_SESSION_ALPN).toString(), arg1 = (client.inetAddress?.hostAddress).toString()))
                        return
                    }
                    LogKit.i(AppStrings.ui_device_session_tls_complete_remote_arg0_alpn_arg1.format(arg0 = (client.inetAddress?.hostAddress).toString(), arg1 = (DEVICE_SESSION_ALPN).toString()))
                    DeviceSessionServerRuntimeLauncher.run(
                        dispatcher = dispatcher,
                        channel = AndroidDeviceSessionByteChannel(client),
                        remoteHost = client.inetAddress?.hostAddress,
                        scope = scope,
                        approvalContext = DeviceSessionServerApprovalContext.Standard,
                    )
                    return
                }
                val output = client.getOutputStream()
                while (currentCoroutineContext().isActive) {
                    val request = try {
                        parseRequest(client)
                    } catch (_: EOFException) {
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
                        writeResponse(output, response, keepAlive)
                        true
                    } ?: false
                    if (!admitted) {
                        keepAlive = false
                        writeResponse(output, rejectedCapacityResponse(routeClass), keepAlive = false)
                    }
                    if (!keepAlive) break
                }
            } catch (_: EOFException) {
            } catch (error: Throwable) {
                if (error.isExpectedClientDisconnect()) {
                    return
                }
                LogKit.e(AppStrings.ui_raw_tls_http_request_processing_exception_arg0.format(arg0 = (error.message).toString()), error)
                runCatching {
                    writeResponse(
                        client.getOutputStream(),
                        RawHttpResponse.bytes(500, body = byteArrayOf()),
                        keepAlive = false,
                    )
                }
            } finally {
                idleDataKeepAlivePermit?.release()
            }
        }
    }

    private fun parseRequest(socket: Socket): RawHttpRequest {
        val input = socket.getInputStream()
        val requestLine = input.readHttpLine().takeIf { item -> item.isNotBlank() }
            ?: throw EOFException(AppStrings.ui_empty_request_line)
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
            val line = input.readHttpLine()
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
        val maxRequestBodyBytes = if (shouldUseLargeRawHttpRequestBodyLimit(path)) {
            MAX_REQUEST_BODY_BYTES
        } else {
            bufferedRequestBodyLimitBytes()
        }
        if (contentLength !in 0L..maxRequestBodyBytes) {
            throw IllegalArgumentException(AppStrings.ui_the_request_body_is_too_large)
        }
        val isEncryptedPayload = headers[HTTP_ENCRYPTED_PAYLOAD_HEADER.lowercase()] == HTTP_ENCRYPTED_PAYLOAD_VERSION
        val bodyReader = if (shouldStreamRawHttpRequestBody(path, contentLength, isChunked = false)) {
            InputStreamRawHttpRequestBodyReader(input, contentLength)
        } else {
            null
        }
        val body = if (contentLength == 0L || bodyReader != null) {
            byteArrayOf()
        } else {
            input.readExact(contentLength.toInt())
        }
        return RawHttpRequest(
            method = method,
            path = path,
            protocolVersion = protocolVersion,
            queryParameters = queryParameters,
            headers = headers,
            body = body,
            bodyReader = bodyReader,
            remoteHost = socket.inetAddress?.hostAddress,
        )
    }

    private suspend fun writeResponse(output: OutputStream, response: RawHttpResponse, keepAlive: Boolean) {
        val headers = linkedMapOf<String, String>()
        headers.putAll(response.headers)
        headers.putIfAbsent("Connection", if (keepAlive) "keep-alive" else "close")
        when (val body = response.body) {
            is RawHttpBody.Bytes -> {
                headers["Content-Length"] = body.bytes.size.toString()
                output.writeStatusAndHeaders(response, headers)
                withContext(Dispatchers.IO) {
                    output.write(body.bytes)
                    output.flush()
                }
            }

            is RawHttpBody.Stream -> {
                val contentLength = body.contentLength
                val writer = if (contentLength == null) {
                    headers["Transfer-Encoding"] = "chunked"
                    output.writeStatusAndHeaders(response, headers)
                    ChunkedOutputWriter(output)
                } else {
                    headers["Content-Length"] = contentLength.toString()
                    output.writeStatusAndHeaders(response, headers)
                    FixedOutputWriter(output)
                }
                body.writer.invoke(writer)
                if (writer is ChunkedOutputWriter) writer.finish()
                withContext(Dispatchers.IO) {
                    output.flush()
                }
            }
        }
    }

    private fun OutputStream.writeStatusAndHeaders(response: RawHttpResponse, headers: Map<String, String>) {
        writeAscii("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
        headers.forEach { (name, value) -> writeAscii("$name: $value\r\n") }
        writeAscii("\r\n")
    }

    private class FixedOutputWriter(private val output: OutputStream) : RawHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            withContext(Dispatchers.IO) {
                output.write(bytes, offset, length)
            }
        }

        override suspend fun flush() {
            withContext(Dispatchers.IO) {
                output.flush()
            }
        }
    }

    private class InputStreamRawHttpRequestBodyReader(
        private val input: InputStream,
        override val contentLength: Long,
    ) : RawHttpRequestBodyReader {
        override var remainingBytes: Long = contentLength
            private set

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (remainingBytes <= 0L) return -1
            val readLength = minOf(length.toLong(), remainingBytes).toInt()
            val read = withContext(Dispatchers.IO) {
                input.read(buffer, offset, readLength)
            }
            if (read > 0) remainingBytes -= read.toLong()
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
                if (read < 0) throw EOFException(AppStrings.ui_the_request_body_is_incomplete)
            }
        }
    }

    private class ChunkedOutputWriter(private val output: OutputStream) : RawHttpResponseBodyWriter {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            withContext(Dispatchers.IO) {
                output.write(length.toString(16).encodeToByteArray())
                output.write("\r\n".encodeToByteArray())
                output.write(bytes, offset, length)
                output.write("\r\n".encodeToByteArray())
            }
        }

        override suspend fun flush() {
            withContext(Dispatchers.IO) {
                output.flush()
            }
        }

        fun finish() {
            output.write("0\r\n\r\n".encodeToByteArray())
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

    private fun InputStream.readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            if (read < 0) throw EOFException(AppStrings.ui_the_request_body_is_incomplete)
            offset += read
        }
        return result
    }

    private fun OutputStream.writeAscii(value: String) {
        write(value.encodeToByteArray())
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
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }

    private fun Throwable.isExpectedClientDisconnect(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is EOFException) return true
            if (current is SocketTimeoutException) return true
            if (current is SocketException || current is SSLException) {
                if (isExpectedRawTlsClientDisconnectMessage(current.message.orEmpty())) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val BACKLOG = 64
        private const val SOCKET_TIMEOUT_MS = 5 * 60 * 1000
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
    }
}

internal fun allocateInternalRawHttpServerPort(vararg reservedPorts: Int): Int {
    var candidate: Int
    do {
        candidate = ServerSocket(0).use { socket -> socket.localPort }
    } while (reservedPorts.contains(candidate))
    return candidate
}
