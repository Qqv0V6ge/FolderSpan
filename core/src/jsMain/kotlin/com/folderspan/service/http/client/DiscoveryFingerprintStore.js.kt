package com.folderspan.service.http.client

internal actual class DiscoveryFingerprintStore actual constructor() {
    private val fingerprints = mutableMapOf<String, String>()

    actual fun put(key: String, value: String) {
        fingerprints[key] = value
    }

    actual fun get(key: String): String? {
        return fingerprints[key]
    }
}
