package com.folderspan.service.http.client

import io.ktor.client.*

actual fun createNoProxyHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    // JS 运行时的代理由浏览器/JS 环境控制，这里不读取系统代理配置
    return HttpClient { config() }
}

actual fun createPinnedNoProxyHttpClient(
    expectedFingerprintSha256: String,
    config: HttpClientConfig<*>.() -> Unit
): HttpClient {
    return HttpClient { config() }
}

@Suppress("UNUSED_PARAMETER")
internal actual fun createDiscoveryNoProxyHttpClient(
    leafCertificateCapture: LeafCertificateCapture,
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    return HttpClient { config() }
}
