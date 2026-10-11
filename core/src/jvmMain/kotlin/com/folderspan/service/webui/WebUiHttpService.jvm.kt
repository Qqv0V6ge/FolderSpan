package com.folderspan.service.webui

import com.folderspan.service.http.server.HttpPortSchemeSwitchingProxy
import com.folderspan.service.http.server.LinkShareRawHttpServer
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket

actual class WebUiHttpService actual constructor(
    private val handler: WebUiRequestHandler,
) : WebUiHttpServiceInterface {
    private val proxy = HttpPortSchemeSwitchingProxy()
    private val webSocket = WebUiWebSocketServer(handler)
    private var httpServer: LinkShareRawHttpServer? = null
    private var httpsServer: LinkShareRawHttpServer? = null
    private var currentStatus = WebUiHttpServiceStatus(supported = true, running = false)

    actual override suspend fun start(port: Int): WebUiHttpServiceStatus = withContext(Dispatchers.IO) {
        if (currentStatus.running && currentStatus.port == port) {
            return@withContext currentStatus
        }
        if (currentStatus.running) stop()
        try {
            handler.start()
            val httpPort = allocateLoopbackPort(port)
            val httpsPort = allocateLoopbackPort(port, httpPort)
            httpServer = LinkShareRawHttpServer(
                "http",
                Long.MAX_VALUE / 4,
                upgrade = webSocket::upgrade,
                handler = handler::handle,
            ).also { server ->
                server.start(httpPort, "127.0.0.1", tls = false)
            }
            httpsServer = LinkShareRawHttpServer(
                "https",
                Long.MAX_VALUE / 4,
                upgrade = webSocket::upgrade,
                handler = handler::handle,
            ).also { server ->
                server.start(httpsPort, "127.0.0.1", tls = true)
            }
            proxy.start(
                port,
                httpPort,
                httpsPort,
                bindLan = true,
                allowPlainHttpOnLan = true,
            )
            currentStatus = runningStatus(port)
        } catch (error: Throwable) {
            stopTransports()
            handler.stop()
            if (error is kotlinx.coroutines.CancellationException) throw error
            currentStatus = WebUiHttpServiceStatus(
                supported = true,
                running = false,
                port = port,
                errorCode = if (error.isAddressInUse()) "port_in_use" else "start_failed",
                errorMessage = if (error.isAddressInUse()) {
                    "Web UI port is already in use"
                } else {
                    "Web UI service failed to start"
                },
            )
        }
        currentStatus
    }

    actual override suspend fun stop(): WebUiHttpServiceStatus {
        stopTransports()
        handler.stop()
        currentStatus = WebUiHttpServiceStatus(supported = true, running = false)
        return currentStatus
    }

    actual override fun status(): WebUiHttpServiceStatus = currentStatus

    private suspend fun stopTransports() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { proxy.stop() }
        runCatching { httpsServer?.stop() }
        runCatching { httpServer?.stop() }
        httpsServer = null
        httpServer = null
    }

    private fun runningStatus(port: Int): WebUiHttpServiceStatus = WebUiHttpServiceStatus(
        supported = true,
        running = true,
        port = port,
        endpoints = buildWebUiAdvertisedEndpoints(
            webUiAdvertisedHosts(getAllIPAddresses(SocketClientIPEnum.ALL)),
            port,
        ),
        tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
        documentRootReady = true,
    )
}

private fun allocateLoopbackPort(vararg excluded: Int): Int {
    repeat(16) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket -> socket.localPort }
        if (port !in excluded) return port
    }
    error("unable to allocate internal Web UI port")
}

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
