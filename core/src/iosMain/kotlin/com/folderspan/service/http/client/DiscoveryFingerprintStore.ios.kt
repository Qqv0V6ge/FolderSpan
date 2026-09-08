package com.folderspan.service.http.client

import platform.Foundation.NSLock

internal actual class DiscoveryFingerprintStore actual constructor() {
    private val lock = NSLock()
    private val fingerprints = mutableMapOf<String, String>()

    actual fun put(key: String, value: String) {
        lock.lock()
        try {
            fingerprints[key] = value
        } finally {
            lock.unlock()
        }
    }

    actual fun get(key: String): String? {
        lock.lock()
        return try {
            fingerprints[key]
        } finally {
            lock.unlock()
        }
    }
}
