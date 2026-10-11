package com.folderspan.service.http.server

import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.*

internal class HttpPortSchemeSwitchingProxy {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverSockets = mutableListOf<ServerSocket>()
    private val acceptJobs = mutableListOf<Job>()
    private val activeSockets = mutableSetOf<Socket>()

    fun start(
        publicHttpPort: Int,
        internalHttpPort: Int,
        internalTlsPort: Int,
        bindLan: Boolean = false,
        allowPlainHttpOnLan: Boolean = true,
    ) {
        if (acceptJobs.any { job -> job.isActive } || serverSockets.isNotEmpty()) return
        val sockets = createBindSockets(publicHttpPort, bindLan)
        if (sockets.isEmpty()) {
            error("unable to bind MCP proxy")
        }
        serverSockets += sockets
        sockets.forEach { socket ->
            acceptJobs += scope.launch {
                try {
                    while (isActive) {
                        val client = withContext(Dispatchers.IO) { socket.accept() }
                        launch {
                            try {
                                handleClient(
                                    client,
                                    internalHttpPort,
                                    internalTlsPort,
                                    bindLan = bindLan,
                                    allowPlainHttpOnLan = allowPlainHttpOnLan,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                runCatching { client.close() }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    throw CancellationException("HTTP port scheme switching proxy stopped")
                } catch (_: Throwable) {
                    if (isActive) throw CancellationException("HTTP port scheme switching proxy failed")
                } finally {
                    runCatching { socket.close() }
                    synchronized(serverSockets) {
                        serverSockets.remove(socket)
                    }
                }
            }
        }
    }

    suspend fun stop() {
        val sockets = synchronized(serverSockets) {
            val copy = serverSockets.toList()
            serverSockets.clear()
            copy
        }
        sockets.forEach { socket -> runCatching { socket.close() } }
        closeActiveSockets()
        acceptJobs.toList().forEach { job -> job.cancelAndJoin() }
        acceptJobs.clear()
        closeActiveSockets()
    }

    private suspend fun handleClient(
        client: Socket,
        internalHttpPort: Int,
        internalTlsPort: Int,
        bindLan: Boolean,
        allowPlainHttpOnLan: Boolean,
    ) {
        trackSocket(client)
        try {
            withContext(Dispatchers.IO) {
                client.use { clientSocket ->
                    clientSocket.soTimeout = SOCKET_TIMEOUT_MS
                    val input = clientSocket.getInputStream()
                    val firstBytes = readInitialBytes(input)
                    if (firstBytes.isEmpty()) return@use
                    val combinedInput = SequenceInputStream(ByteArrayInputStream(firstBytes), input)
                    val httpRequest = isHttpRequest(firstBytes)
                    if (
                        bindLan &&
                        !allowPlainHttpOnLan &&
                        httpRequest &&
                        !clientSocket.inetAddress.isLoopbackAddress
                    ) {
                        return@use
                    }
                    val upstreamPort = if (httpRequest) internalHttpPort else internalTlsPort
                    proxyTcp(clientSocket, combinedInput, upstreamPort)
                }
            }
        } finally {
            untrackSocket(client)
        }
    }

    private fun readInitialBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(INITIAL_BYTES)
        var offset = 0
        while (offset < buffer.size) {
            val value = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                break
            } catch (error: SocketException) {
                if (error.message == "Socket closed") break else throw error
            }
            if (value < 0) break
            buffer[offset] = value.toByte()
            offset++
        }
        return buffer.copyOf(offset)
    }

    private fun isHttpRequest(bytes: ByteArray): Boolean {
        val prefix = bytes.decodeToString().uppercase(Locale.ROOT)
        return HTTP_METHOD_PREFIXES.any { method -> prefix.startsWith(method) }
    }

    private fun proxyTcp(client: Socket, clientInput: InputStream, upstreamPort: Int) {
        Socket("127.0.0.1", upstreamPort).use { upstream ->
            trackSocket(upstream)
            try {
                client.soTimeout = 0
                upstream.soTimeout = 0
                val closeBoth = {
                    runCatching { upstream.close() }
                    runCatching { client.close() }
                }
                val clientToUpstream = scope.async(Dispatchers.IO) {
                    try {
                        runCatching { clientInput.copyTo(upstream.getOutputStream()) }
                        runCatching { upstream.shutdownOutput() }
                    } finally {
                        closeBoth()
                    }
                }
                val upstreamToClient = scope.async(Dispatchers.IO) {
                    try {
                        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
                        runCatching { client.shutdownOutput() }
                    } finally {
                        closeBoth()
                    }
                }
                runBlocking {
                    awaitAll(clientToUpstream, upstreamToClient)
                }
            } finally {
                untrackSocket(upstream)
            }
        }
    }

    private fun trackSocket(socket: Socket) {
        synchronized(activeSockets) {
            activeSockets += socket
        }
    }

    private fun untrackSocket(socket: Socket) {
        synchronized(activeSockets) {
            activeSockets -= socket
        }
    }

    private fun closeActiveSockets() {
        val sockets = synchronized(activeSockets) { activeSockets.toList() }
        sockets.forEach { socket -> runCatching { socket.close() } }
    }

    private fun createBindSockets(port: Int, bindLan: Boolean): List<ServerSocket> {
        if (bindLan) return listOf(ServerSocket(port))
        val ipv4 = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
        val ipv6 = runCatching { ServerSocket(port, BACKLOG, InetAddress.getByName("::1")) }.getOrNull()
        return listOfNotNull(ipv4, ipv6)
    }

    companion object {
        private const val INITIAL_BYTES = 8
        private const val SOCKET_TIMEOUT_MS = 3000
        private const val BACKLOG = 50
        private val HTTP_METHOD_PREFIXES = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ")
    }
}
