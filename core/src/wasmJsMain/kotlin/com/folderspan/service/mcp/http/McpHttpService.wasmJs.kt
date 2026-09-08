package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.McpServerSettings

actual class McpHttpService actual constructor(
    @Suppress("unused") handler: McpHttpRequestHandler,
) : McpHttpServiceInterface {
    private val unsupported = McpHttpServiceStatus(
        supported = false,
        running = false,
        errorCode = "unsupported",
        errorMessage = "MCP HTTP service is not supported on Wasm",
    )

    actual override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus = unsupported
    actual override suspend fun stop(): McpHttpServiceStatus = unsupported
    actual override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus = unsupported
    actual override fun status(): McpHttpServiceStatus = unsupported
}
