package com.folderspan.service.http.client

import java.util.concurrent.ConcurrentHashMap

internal actual class DiscoveryFingerprintStore actual constructor() {
    private val fingerprints = ConcurrentHashMap<String, String>()

    actual fun put(key: String, value: String) {
        fingerprints[key] = value
    }

    actual fun get(key: String): String? {
        return fingerprints[key]
    }
}
