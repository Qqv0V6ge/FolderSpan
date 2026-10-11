package com.folderspan.service.mcp

import com.folderspan.utils.McpSettings

const val DEFAULT_MCP_PORT: Int = 52137

data class McpServerSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_MCP_PORT,
    val lanAccess: Boolean = false,
)

class McpServerSettingsStore(
    private val settings: McpSettings = McpSettings,
) {
    fun read(): McpServerSettings = McpServerSettings(
        enabled = settings.isEnabled(),
        port = settings.getPort(DEFAULT_MCP_PORT).takeIf(::isValidMcpPort) ?: DEFAULT_MCP_PORT,
        lanAccess = settings.isLanAccessEnabled(),
    )

    fun write(value: McpServerSettings): McpServerSettings {
        require(isValidMcpPort(value.port)) { "MCP port must be between 1 and 65535" }
        settings.putEnabled(value.enabled)
        settings.putPort(value.port)
        settings.putLanAccessEnabled(value.lanAccess)
        settings.clearAllowedOrigins()
        return value
    }
}

fun isValidMcpPort(port: Int): Boolean = port in 1..65535
