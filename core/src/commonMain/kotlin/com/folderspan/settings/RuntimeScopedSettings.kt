package com.folderspan.settings

import com.russhwolf.settings.Settings

/** A fixed namespace: switching Runtime reloads the page and creates new stores. */
internal class RuntimeScopedSettings(private val delegate: Settings, target: String) : Settings {
    private val prefix = "runtime:${target.length}:$target:"
    override val keys get() = delegate.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    override val size get() = keys.size
    override fun clear() = keys.forEach(::remove)
    override fun remove(key: String) = delegate.remove(prefix + key)
    override fun hasKey(key: String) = delegate.hasKey(prefix + key)
    override fun putInt(key: String, value: Int) = delegate.putInt(prefix + key, value)
    override fun getInt(key: String, defaultValue: Int) = delegate.getInt(prefix + key, defaultValue)
    override fun getIntOrNull(key: String) = delegate.getIntOrNull(prefix + key)
    override fun putLong(key: String, value: Long) = delegate.putLong(prefix + key, value)
    override fun getLong(key: String, defaultValue: Long) = delegate.getLong(prefix + key, defaultValue)
    override fun getLongOrNull(key: String) = delegate.getLongOrNull(prefix + key)
    override fun putString(key: String, value: String) = delegate.putString(prefix + key, value)
    override fun getString(key: String, defaultValue: String) = delegate.getString(prefix + key, defaultValue)
    override fun getStringOrNull(key: String) = delegate.getStringOrNull(prefix + key)
    override fun putFloat(key: String, value: Float) = delegate.putFloat(prefix + key, value)
    override fun getFloat(key: String, defaultValue: Float) = delegate.getFloat(prefix + key, defaultValue)
    override fun getFloatOrNull(key: String) = delegate.getFloatOrNull(prefix + key)
    override fun putDouble(key: String, value: Double) = delegate.putDouble(prefix + key, value)
    override fun getDouble(key: String, defaultValue: Double) = delegate.getDouble(prefix + key, defaultValue)
    override fun getDoubleOrNull(key: String) = delegate.getDoubleOrNull(prefix + key)
    override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(prefix + key, value)
    override fun getBoolean(key: String, defaultValue: Boolean) = delegate.getBoolean(prefix + key, defaultValue)
    override fun getBooleanOrNull(key: String) = delegate.getBooleanOrNull(prefix + key)
}
