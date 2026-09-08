package com.folderspan.service.mcp.http

import com.folderspan.service.http.server.HttpPortSchemeSwitchingProxy
import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.service.mcp.McpServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket

actual class McpHttpService actual constructor(
    private val handler: McpHttpRequestHandler,
) : McpHttpServiceInterface {
    private val proxy = HttpPortSchemeSwitchingProxy()
    private var httpServer: LinkShareRawHttpServer? = null
    private var httpsServer: LinkShareRawHttpServer? = null
    private var currentStatus = McpHttpServiceStatus(supported = true, running = false)
    private var startedLanAccess: Boolean? = null

    actual override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus = withContext(Dispatchers.IO) {
        if (!settings.enabled) return@withContext stop()
        if (currentStatus.running &&
            currentStatus.port == settings.port &&
            startedLanAccess == settings.lanAccess
        ) {
            return@withContext currentStatus
        }
        if (currentStatus.running) stop()
        try {
            val httpPort = allocateLoopbackPort(settings.port)
            val httpsPort = allocateLoopbackPort(settings.port, httpPort)
            httpServer = LinkShareRawHttpServer(
                "http",
                McpHttpRequestHandler.MAX_REQUEST_BODY_BYTES.toLong(),
                handler::handle,
            ).also { server ->
                server.start(httpPort, "127.0.0.1", tls = false)
            }
            httpsServer = LinkShareRawHttpServer(
                "https",
                McpHttpRequestHandler.MAX_REQUEST_BODY_BYTES.toLong(),
                handler::handle,
            ).also { server ->
                server.start(httpsPort, "127.0.0.1", tls = true)
            }
            proxy.start(
                settings.port,
                httpPort,
                httpsPort,
                bindLan = settings.lanAccess,
                allowPlainHttpOnLan = false,
            )
            startedLanAccess = settings.lanAccess
            currentStatus = runningStatus(settings)
        } catch (error: Throwable) {
            stopTransports()
            startedLanAccess = null
            currentStatus = McpHttpServiceStatus(
                supported = true,
                running = false,
                port = settings.port,
                errorCode = if (error.isAddressInUse()) "port_in_use" else "start_failed",
                errorMessage = if (error.isAddressInUse()) "MCP port is already in use" else "MCP service failed to start",
            )
        }
        currentStatus
    }

    actual override suspend fun stop(): McpHttpServiceStatus {
        stopTransports()
        handler.clearSessions()
        currentStatus = McpHttpServiceStatus(supported = true, running = false)
        return currentStatus
    }

    actual override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus {
        stop()
        return start(settings)
    }

    actual override fun status(): McpHttpServiceStatus = currentStatus

    private suspend fun stopTransports() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { proxy.stop() }
        runCatching { httpsServer?.stop() }
        runCatching { httpServer?.stop() }
        httpsServer = null
        httpServer = null
        startedLanAccess = null
    }

    private fun runningStatus(settings: McpServerSettings): McpHttpServiceStatus = McpHttpServiceStatus(
        supported = true,
        running = true,
        port = settings.port,
        endpoints = advertisedEndpoints(settings.port, settings.lanAccess),
        tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
    )
}

private fun allocateLoopbackPort(vararg excluded: Int): Int {
    repeat(16) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket -> socket.localPort }
        if (port !in excluded) return port
    }
    error("unable to allocate internal MCP port")
}

private fun advertisedEndpoints(port: Int, lanAccess: Boolean): List<McpAdvertisedEndpoint> =
    buildMcpAdvertisedEndpoints(
        mcpAdvertisedHosts(lanAccess, getAllIPAddresses(SocketClientIPEnum.ALL)),
        port,
    )

private fun Throwable.isAddressInUse(): Boolean {
    var error: Throwable? = this
    while (error != null) {
        if (error is java.net.BindException) return true
        val message = error.message.orEmpty()
        if (message.contains("Address already in use", ignoreCase = true) ||
            message.contains("EADDRINUSE", ignoreCase = true)
        ) {
            return true
        }
        error = error.cause
    }
    return false
}
