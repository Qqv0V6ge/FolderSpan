package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.McpServerSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes platform foreground/background callbacks around the independent MCP listener. */
class McpHttpServiceLifecycle(
    private val service: McpHttpServiceInterface,
    private val settingsProvider: () -> McpServerSettings,
) {
    private val mutex = Mutex()

    suspend fun enterForeground(): McpHttpServiceStatus = mutex.withLock {
        service.start(settingsProvider())
    }

    suspend fun enterBackground(): McpHttpServiceStatus = mutex.withLock {
        service.stop()
    }
}
