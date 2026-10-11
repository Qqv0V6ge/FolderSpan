package com.folderspan.service.http.client

import com.folderspan.AppBuildConfig
import io.ktor.client.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.URLProtocol
import io.ktor.util.PlatformUtils

fun HttpClientConfig<*>.applyConfiguredHttpProxy(proxyUrl: String = AppBuildConfig.HTTP_PROXY_URL) {
    if (PlatformUtils.IS_JS || PlatformUtils.IS_WASM_JS) return
    proxyUrl.trim().takeIf(String::isNotEmpty)?.let { url ->
        engine {
            proxy = ProxyBuilder.http(url)
        }
        if (PlatformUtils.IS_JVM) {
            install("HttpProxyWebSocketScheme") {
                sendPipeline.intercept(HttpSendPipeline.Before) {
                    // CIO 会将完整 URL 发给代理；在 WebSockets 插件准备好 Upgrade 后转换为 HTTP 握手地址。
                    context.url.protocol = when (context.url.protocol) {
                        URLProtocol.WS -> URLProtocol.HTTP
                        URLProtocol.WSS -> URLProtocol.HTTPS
                        else -> context.url.protocol
                    }
                }
            }
        }
    }
}

/**
 * 创建一个 HttpClient：在支持的平台（JVM/Android/iOS）禁用系统代理；
 * JS/Wasm 平台由运行时（浏览器/JS 环境）控制代理，无法在此处覆写。
 *
 * @param config 传入的配置块，用于安装插件、设置默认请求等。
 * @return 已配置的 HttpClient 实例。
 */
expect fun createNoProxyHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

expect fun createPinnedNoProxyHttpClient(
    expectedFingerprintSha256: String,
    config: HttpClientConfig<*>.() -> Unit
): HttpClient

internal expect fun createDiscoveryNoProxyHttpClient(
    leafCertificateCapture: LeafCertificateCapture,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient
