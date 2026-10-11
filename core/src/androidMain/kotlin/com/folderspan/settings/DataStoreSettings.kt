package com.folderspan.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.folderspan.utils.SettingsUtils
import com.google.crypto.tink.Aead
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Base64

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_settings")

class DataStoreSettings internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val aead: Aead,
) : Settings {
    constructor(context: Context, aead: Aead) : this(context.dataStore, aead)

    private val cacheLock = Any()
    private val encryptedValues = runBlocking {
        dataStore.data.first().asMap().mapNotNull { (key, value) ->
            (value as? String)?.let { encryptedValue -> key.name to encryptedValue }
        }.toMap().toMutableMap()
    }

    private fun encrypt(plaintext: String): String {
        val encrypted = aead.encrypt(plaintext.toByteArray(), null)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decrypt(ciphertext: String): String {
        val encrypted = Base64.getDecoder().decode(ciphertext)
        val decrypted = aead.decrypt(encrypted, null)
        return String(decrypted)
    }

    override fun clear() {
        val deviceId = SensitiveSettingKeys.assignedDeviceId(
            getStringOrNull(SettingsUtils.KEY_DEVICE_ID),
        )
        synchronized(cacheLock) {
            runBlocking {
                dataStore.edit { item -> item.clear() }
            }
            encryptedValues.clear()
        }
        if (deviceId != null) {
            putString(SettingsUtils.KEY_DEVICE_ID, deviceId)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getString(key, defaultValue.toString()).toBoolean()
    }

    override fun getBooleanOrNull(key: String): Boolean? {
        return getStringOrNull(key)?.toBoolean()
    }

    override fun getDouble(key: String, defaultValue: Double): Double {
        return getString(key, defaultValue.toString()).toDouble()
    }

    override fun getDoubleOrNull(key: String): Double? {
        return getStringOrNull(key)?.toDouble()
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return getString(key, defaultValue.toString()).toFloat()
    }

    override fun getFloatOrNull(key: String): Float? {
        return getStringOrNull(key)?.toFloat()
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return getString(key, defaultValue.toString()).toInt()
    }

    override fun getIntOrNull(key: String): Int? {
        return getStringOrNull(key)?.toInt()
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return getString(key, defaultValue.toString()).toLong()
    }

    override fun getLongOrNull(key: String): Long? {
        return getStringOrNull(key)?.toLong()
    }

    override fun getString(key: String, defaultValue: String): String {
        return getStringOrNull(key) ?: defaultValue
    }

    override fun getStringOrNull(key: String): String? {
        val encryptedValue = synchronized(cacheLock) { encryptedValues[key] }
        return encryptedValue?.let(::decrypt)
    }

    override fun hasKey(key: String): Boolean {
        return synchronized(cacheLock) { key in encryptedValues }
    }

    override fun putBoolean(key: String, value: Boolean) {
        putString(key, value.toString())
    }

    override fun putDouble(key: String, value: Double) {
        putString(key, value.toString())
    }

    override fun putFloat(key: String, value: Float) {
        putString(key, value.toString())
    }

    override fun putInt(key: String, value: Int) {
        putString(key, value.toString())
    }

    override fun putLong(key: String, value: Long) {
        putString(key, value.toString())
    }

    override fun putString(key: String, value: String) {
        if (SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(key, getStringOrNull(key), value)) {
            return
        }
        val encryptedValue = encrypt(value)
        synchronized(cacheLock) {
            runBlocking {
                val prefKey = stringPreferencesKey(key)
                dataStore.edit { preferences ->
                    preferences[prefKey] = encryptedValue
                }
            }
            encryptedValues[key] = encryptedValue
        }
    }

    override fun remove(key: String) {
        if (SensitiveSettingKeys.shouldPreserveAssignedDeviceId(key, getStringOrNull(key))) {
            return
        }
        synchronized(cacheLock) {
            runBlocking {
                val prefKey = stringPreferencesKey(key)
                dataStore.edit { preferences ->
                    preferences.remove(prefKey)
                }
            }
            encryptedValues.remove(key)
        }
    }

    override val keys: Set<String>
        get() = synchronized(cacheLock) { encryptedValues.keys.toSet() }

    override val size: Int
        get() = synchronized(cacheLock) { encryptedValues.size }
}
