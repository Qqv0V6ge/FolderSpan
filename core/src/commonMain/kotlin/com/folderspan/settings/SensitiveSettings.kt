package com.folderspan.settings

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings

/**
 * 把敏感键写入独立密文存储；明文 Preferences / NSUserDefaults 只保留非敏感项。
 */
internal class SensitiveSettings(
    private val plaintext: Settings,
    private val vault: StringSecretVault,
) : Settings {
    init {
        scrubPlaintextSecrets()
    }

    override val keys: Set<String>
        get() = plaintext.keys + vault.keys()

    override val size: Int
        get() = keys.size

    override fun clear() {
        val deviceId = SensitiveSettingKeys.assignedDeviceId(
            getStringOrNull(SettingsUtils.KEY_DEVICE_ID),
        )
        vault.clear()
        plaintext.clear()
        if (deviceId != null) {
            vault.put(SettingsUtils.KEY_DEVICE_ID, deviceId)
        }
    }

    override fun remove(key: String) {
        if (SensitiveSettingKeys.shouldPreserveAssignedDeviceId(key, getStringOrNull(key))) {
            return
        }
        if (SensitiveSettingKeys.isSensitive(key)) {
            vault.remove(key)
        }
        plaintext.remove(key)
    }

    override fun hasKey(key: String): Boolean {
        return if (SensitiveSettingKeys.isSensitive(key)) {
            vault.contains(key)
        } else {
            plaintext.hasKey(key)
        }
    }

    override fun putString(key: String, value: String) {
        if (SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(key, getStringOrNull(key), value)) {
            return
        }
        if (SensitiveSettingKeys.isSensitive(key)) {
            vault.put(key, value)
            plaintext.remove(key)
            return
        }
        plaintext.putString(key, value)
    }

    private fun scrubPlaintextSecrets() {
        plaintext.keys.filter(SensitiveSettingKeys::isSensitive).forEach { key ->
            plaintext.remove(key)
        }
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? {
        return if (SensitiveSettingKeys.isSensitive(key)) {
            vault.get(key)
        } else {
            plaintext.getStringOrNull(key)
        }
    }

    override fun putInt(key: String, value: Int) {
        plaintext.putInt(key, value)
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        plaintext.getInt(key, defaultValue)

    override fun getIntOrNull(key: String): Int? =
        plaintext.getIntOrNull(key)

    override fun putLong(key: String, value: Long) {
        plaintext.putLong(key, value)
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        plaintext.getLong(key, defaultValue)

    override fun getLongOrNull(key: String): Long? =
        plaintext.getLongOrNull(key)

    override fun putFloat(key: String, value: Float) {
        plaintext.putFloat(key, value)
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        plaintext.getFloat(key, defaultValue)

    override fun getFloatOrNull(key: String): Float? =
        plaintext.getFloatOrNull(key)

    override fun putDouble(key: String, value: Double) {
        plaintext.putDouble(key, value)
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        plaintext.getDouble(key, defaultValue)

    override fun getDoubleOrNull(key: String): Double? =
        plaintext.getDoubleOrNull(key)

    override fun putBoolean(key: String, value: Boolean) {
        plaintext.putBoolean(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        plaintext.getBoolean(key, defaultValue)

    override fun getBooleanOrNull(key: String): Boolean? =
        plaintext.getBooleanOrNull(key)
}

internal interface StringSecretVault {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun contains(key: String): Boolean
    fun keys(): Set<String>
    fun clear()
}

internal class MemoryStringSecretVault(
    initial: Map<String, String> = emptyMap(),
) : StringSecretVault {
    private val values = initial.toMutableMap()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = key in values

    override fun keys(): Set<String> = values.keys.toSet()

    override fun clear() {
        values.clear()
    }
}
