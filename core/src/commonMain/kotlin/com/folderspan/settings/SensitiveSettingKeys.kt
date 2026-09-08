package com.folderspan.settings

import com.folderspan.utils.SettingsUtils

internal object SensitiveSettingKeys {
    fun isSensitive(key: String): Boolean {
        return key.startsWith("auth.") || key.startsWith("pro.") || key in exactKeys
    }

    fun assignedDeviceId(existing: String?): String? =
        existing?.trim()?.takeIf { it.isNotEmpty() }

    fun shouldPreserveAssignedDeviceId(key: String, existing: String?): Boolean {
        return key == SettingsUtils.KEY_DEVICE_ID && assignedDeviceId(existing) != null
    }

    fun shouldIgnoreDeviceIdWrite(key: String, existing: String?, incoming: String): Boolean {
        if (key != SettingsUtils.KEY_DEVICE_ID) return false
        if (assignedDeviceId(existing) != null) return true
        return assignedDeviceId(incoming) == null
    }

    private val exactKeys = setOf(
        SettingsUtils.KEY_DEVICE_ID,
        SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY,
        SettingsUtils.KEY_CRYPTO_KEY,
        SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC,
        SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD,
    )
}
