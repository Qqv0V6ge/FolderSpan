package com.folderspan.service.mcp.http

import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.service.mcp.McpServerSettings

actual class McpHttpService actual constructor(
    private val handler: McpHttpRequestHandler,
) : McpHttpServiceInterface {
    private var server: LinkShareRawHttpServer? = null
    private var currentStatus = McpHttpServiceStatus(supported = true, running = false)
    private var startedLanAccess: Boolean? = null

    actual override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus {
        if (!settings.enabled) return stop()
        if (currentStatus.running &&
            currentStatus.port == settings.port &&
            startedLanAccess == settings.lanAccess
        ) {
            return currentStatus
        }
        if (currentStatus.running) stop()
        currentStatus = try {
            server = LinkShareRawHttpServer(
                "auto",
                McpHttpRequestHandler.MAX_REQUEST_BODY_BYTES.toLong(),
                handler::handle,
                allowPlainHttpOnLan = false,
            ).also { item -> item.start(settings.port, bindLoopback = !settings.lanAccess) }
            startedLanAccess = settings.lanAccess
            McpHttpServiceStatus(
                supported = true,
                running = true,
                port = settings.port,
                endpoints = advertisedIosEndpoints(settings.port, settings.lanAccess),
                tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
            )
        } catch (error: Throwable) {
            runCatching { server?.stop() }
            server = null
            startedLanAccess = null
            McpHttpServiceStatus(
                supported = true,
                running = false,
                port = settings.port,
                errorCode = if (error.isAddressInUse()) "port_in_use" else "start_failed",
                errorMessage = if (error.isAddressInUse()) "MCP port is already in use" else "MCP service failed to start",
            )
        }
        return currentStatus
    }

    actual override suspend fun stop(): McpHttpServiceStatus {
        runCatching { server?.stop() }
        server = null
        handler.clearSessions()
        startedLanAccess = null
        currentStatus = McpHttpServiceStatus(supported = true, running = false)
        return currentStatus
    }

    actual override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus {
        stop()
        return start(settings)
    }

    actual override fun status(): McpHttpServiceStatus = currentStatus
}

private fun advertisedIosEndpoints(port: Int, lanAccess: Boolean): List<McpAdvertisedEndpoint> =
    buildMcpAdvertisedEndpoints(
        mcpAdvertisedHosts(
            lanAccess,
            getAllIPAddresses(SocketClientIPEnum.ALL).filterNot { address -> ':' in address },
        ),
        port,
    )

private fun Throwable.isAddressInUse(): Boolean {
    var error: Throwable? = this
    while (error != null) {
        if (error.message?.contains("Address already in use", ignoreCase = true) == true) return true
        error = error.cause
    }
    return false
}
