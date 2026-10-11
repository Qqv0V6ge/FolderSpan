package com.folderspan.service.http.client

import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256

/**
 * 单个发现 HttpClient 在一次 in-flight 请求里记下叶子证书 SHA-256。
 * CIO 的 TrustManager 拿不到对端地址，所以不能在共享客户端上并发复用同一个捕获槽。
 */
internal class LeafCertificateCapture {
    private val store = DiscoveryFingerprintStore()

    fun accept(fingerprintSha256: String) {
        val normalized = normalizeTlsFingerprintSha256(fingerprintSha256)
        if (normalized.isBlank()) return
        store.put(LEAF_KEY, normalized)
    }

    fun take(): String? {
        val fingerprint = store.get(LEAF_KEY)?.takeIf { item -> item.isNotBlank() }
        store.put(LEAF_KEY, "")
        return fingerprint
    }
}

internal fun requireHandshakeTlsPin(handshakeFingerprintSha256: String?): String? {
    return normalizeTlsFingerprintSha256(handshakeFingerprintSha256.orEmpty())
        .takeIf { item -> item.isNotBlank() }
}

internal expect class DiscoveryFingerprintStore() {
    fun put(key: String, value: String)
    fun get(key: String): String?
}

private const val LEAF_KEY = "leaf"
