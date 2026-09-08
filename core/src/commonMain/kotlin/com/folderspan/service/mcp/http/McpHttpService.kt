package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.McpServerSettings
import kotlinx.serialization.Serializable

@Serializable
data class McpAdvertisedEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val statefulUrl: String,
    val statelessUrl: String,
)

@Serializable
data class McpHttpServiceStatus(
    val supported: Boolean,
    val running: Boolean,
    val port: Int? = null,
    val endpoints: List<McpAdvertisedEndpoint> = emptyList(),
    val tlsFingerprintSha256: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

fun mcpAdvertisedHosts(lanAccess: Boolean, lanAddresses: Iterable<String> = emptyList()): List<String> {
    val loopback = listOf("127.0.0.1", "localhost")
    if (!lanAccess) return loopback
    return loopback + lanAddresses
}

fun buildMcpAdvertisedEndpoints(hosts: Iterable<String>, port: Int): List<McpAdvertisedEndpoint> =
    hosts.map { item -> item.removePrefix("[").removeSuffix("]") }
        .filter(String::isNotBlank)
        .distinct()
        .flatMap { host ->
            val authorityHost = if (':' in host) "[$host]" else host
            val schemes = if (isLoopbackHost(host)) listOf("https", "http") else listOf("https")
            schemes.map { scheme ->
                McpAdvertisedEndpoint(
                    scheme = scheme,
                    host = host,
                    port = port,
                    statefulUrl = "$scheme://$authorityHost:$port/mcp",
                    statelessUrl = "$scheme://$authorityHost:$port/mcp/stateless",
                )
            }
        }

interface McpHttpServiceInterface {
    suspend fun start(settings: McpServerSettings): McpHttpServiceStatus
    suspend fun stop(): McpHttpServiceStatus
    suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus
    fun status(): McpHttpServiceStatus
}

expect class McpHttpService(handler: McpHttpRequestHandler) : McpHttpServiceInterface {
    override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus
    override suspend fun stop(): McpHttpServiceStatus
    override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus
    override fun status(): McpHttpServiceStatus
}
