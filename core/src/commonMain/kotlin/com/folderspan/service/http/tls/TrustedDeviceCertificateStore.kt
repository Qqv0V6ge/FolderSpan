package com.folderspan.service.http.tls

import com.folderspan.createSettings

object TrustedDeviceCertificateStore {
    private const val KEY_PREFIX = "trustedDeviceTlsFingerprintSha256."
    private const val HASHED_KEY_PREFIX = "trustedTlsFp."
    private const val PUBLIC_KEY_PREFIX = "trustedTlsPk."
    private const val MAX_SETTINGS_KEY_LENGTH = 80

    fun save(deviceId: String, fingerprint: String, publicKeyPem: String? = null) {
        val normalized = normalizeTlsFingerprintSha256(fingerprint)
        if (deviceId.isBlank() || normalized.isBlank()) return
        val settings = createSettings()
        settings.putString(settingsKey(deviceId), normalized)
        val pem = publicKeyPem?.trim().orEmpty()
        if (pem.isNotBlank()) {
            settings.putString(publicKeySettingsKey(deviceId), pem)
        }
    }

    fun verify(deviceId: String, fingerprint: String): Boolean {
        val expected = expectedFingerprint(deviceId)
        return expected.isNotBlank() && expected == normalizeTlsFingerprintSha256(fingerprint)
    }

    fun has(deviceId: String): Boolean {
        return expectedFingerprint(deviceId).isNotBlank()
    }

    fun remove(deviceId: String) {
        if (deviceId.isBlank()) return
        val settings = createSettings()
        settings.remove(settingsKey(deviceId))
        settings.remove(publicKeySettingsKey(deviceId))

        val legacyKey = legacySettingsKey(deviceId)
        if (legacyKey.length <= MAX_SETTINGS_KEY_LENGTH) {
            settings.remove(legacyKey)
        }
    }

    fun expectedPublicKeyPem(deviceId: String): String {
        if (deviceId.isBlank()) return ""
        return createSettings().getString(publicKeySettingsKey(deviceId), "").trim()
    }

    fun expectedFingerprint(deviceId: String): String {
        if (deviceId.isBlank()) return ""
        val settings = createSettings()
        val current = settings.getString(settingsKey(deviceId), "")
        if (current.isNotBlank()) return normalizeTlsFingerprintSha256(current)

        val legacyKey = legacySettingsKey(deviceId)
        val legacy = if (legacyKey.length <= MAX_SETTINGS_KEY_LENGTH) {
            settings.getString(legacyKey, "")
        } else {
            ""
        }
        if (legacy.isNotBlank()) {
            val normalized = normalizeTlsFingerprintSha256(legacy)
            settings.putString(settingsKey(deviceId), normalized)
            return normalized
        }

        return ""
    }

    internal fun settingsKey(deviceId: String): String {
        return HASHED_KEY_PREFIX + stableHash64Hex(deviceId)
    }

    internal fun publicKeySettingsKey(deviceId: String): String {
        return PUBLIC_KEY_PREFIX + stableHash64Hex(deviceId)
    }

    private fun legacySettingsKey(deviceId: String): String {
        return KEY_PREFIX + deviceId
    }

    private fun stableHash64Hex(value: String): String {
        var hash = FNV_64_OFFSET_BASIS
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_64_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0').uppercase()
    }

    private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_64_PRIME = 1099511628211L
}
