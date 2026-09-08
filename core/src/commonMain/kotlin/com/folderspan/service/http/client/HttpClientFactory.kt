package com.folderspan.service.http.client

import io.ktor.client.*

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
