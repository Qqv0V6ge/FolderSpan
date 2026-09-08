package com.folderspan.utils

import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64

/**
 * 配置目标级数据包装密钥（DEK）。
 *
 * 本机保存在敏感设置金库；Pro 同步时作为 `app.secrets.dataEncryptionKey`
 * 随当前 device target 上传，以便共用该配置的设备能解开网盘/WebRTC 口令密文。
 */
object DataEncryptionKey {
    const val SIZE_BYTES = AesGcm.KEY_BYTES

    private var settings: Settings? = null

    fun init(settings: Settings) {
        this.settings = settings
    }

    fun encodedOrNull(): String? {
        val stored = settings?.getStringOrNull(SettingsUtils.KEY_CRYPTO_KEY) ?: return null
        return stored.takeIf(::isValidEncoded)
    }

    fun encodedForSync(): String? = encodedOrNull()

    fun keyBytes(): ByteArray = decode(getOrCreateEncoded())

    fun replaceFromRemote(encoded: String): Boolean {
        val target = settings ?: return false
        if (!isValidEncoded(encoded)) return false
        target.putString(SettingsUtils.KEY_CRYPTO_KEY, encoded)
        return true
    }

    internal fun clearForTests() {
        settings = null
    }

    private fun getOrCreateEncoded(): String {
        val target = checkNotNull(settings) { "Settings must be initialized before encrypting credentials" }
        encodedOrNull()?.let { return it }
        val encoded = encode(secureRandomBytes(SIZE_BYTES))
        target.putString(SettingsUtils.KEY_CRYPTO_KEY, encoded)
        return encoded
    }

    internal fun isValidEncoded(value: String): Boolean =
        runCatching { decode(value).size == SIZE_BYTES }.getOrDefault(false)

    internal fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    private fun decode(value: String): ByteArray = Base64.decode(value)
}
