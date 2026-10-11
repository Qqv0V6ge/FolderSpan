package com.folderspan.test

import com.russhwolf.settings.Settings

fun createInMemorySettings(vararg entries: Pair<String, Any>): Settings =
    InMemorySettings(entries.toMap().toMutableMap())

private class InMemorySettings(
    private val values: MutableMap<String, Any> = mutableMapOf()
) : Settings {
    override val keys: Set<String>
        get() = values.keys

    override val size: Int
        get() = values.size

    override fun clear() {
        values.clear()
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun hasKey(key: String): Boolean = values.containsKey(key)

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? = values[key] as? Int

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? = values[key] as? Long

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? = values[key] as? String

    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = values[key] as? Float

    override fun putDouble(key: String, value: Double) {
        values[key] = value
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = values[key] as? Double

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = values[key] as? Boolean
}
