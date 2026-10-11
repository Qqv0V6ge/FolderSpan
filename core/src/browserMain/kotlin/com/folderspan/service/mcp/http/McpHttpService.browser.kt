package com.folderspan.service.mcp.http

import com.folderspan.service.http.server.PlatformHttpCallbacks
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.mcp.McpServerSettings
import com.folderspan.service.webui.BrowserHttpListener

actual class McpHttpService actual constructor(handler: PlatformHttpCallbacks) : McpHttpServiceInterface {
    private val listener = BrowserHttpListener(handler)
    private var current = McpHttpServiceStatus(supported = true, running = false)
    private var configured: McpServerSettings? = null
    actual override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus {
        if (!settings.enabled) return stop()
        if (listener.running && settings == configured) return status()
        val result = listener.start("mcp", settings.port, settings.lanAccess)
        configured = settings
        current = McpHttpServiceStatus(true, true, settings.port,
            buildMcpAdvertisedEndpoints(mcpAdvertisedHosts(settings.lanAccess, getAllIPAddresses(SocketClientIPEnum.ALL)), settings.port),
            result.fingerprint)
        return current
    }
    actual override suspend fun stop(): McpHttpServiceStatus {
        try { listener.stop() } finally { current = McpHttpServiceStatus(true, false); configured = null }
        return current
    }
    actual override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus { stop(); return start(settings) }
    actual override fun status() = current.copy(running = listener.running)
}
