package com.folderspan.utils

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * 配置目标级数据包装密钥（DEK）。
 *
 * 本机保存在敏感设置金库；Pro 同步时作为 `app.secrets.dataEncryptionKey`
 * 随当前 device target 上传，以便共用该配置的设备能解开网盘/WebRTC 口令密文。
 */
object DataEncryptionKey {
    const val SIZE_BYTES = AesGcm.KEY_BYTES

    private val lock = CredentialKeyLock()
    private val credentialAccess = Mutex()
    private var settings: Settings? = null

    fun init(settings: Settings) = withKeyLock {
        this.settings = settings
    }

    fun encodedOrNull(): String? = withKeyLock {
        val stored = settings?.getStringOrNull(SettingsUtils.KEY_CRYPTO_KEY) ?: return@withKeyLock null
        stored.takeIf(::isValidEncoded)
    }

    fun encodedForSync(): String? = encodedOrNull()

    // 保护依赖密钥的整次读写；持有期间可挂起，但不能再次进入此范围。
    suspend fun <T> withCredentialAccess(block: suspend () -> T): T = credentialAccess.withLock { block() }

    fun keyBytes(): ByteArray = withKeyLock { decode(getOrCreateEncoded()) }

    fun replaceFromRemote(encoded: String): Boolean = withKeyLock {
        val target = settings ?: return@withKeyLock false
        if (!isValidEncoded(encoded)) return@withKeyLock false
        target.putString(SettingsUtils.KEY_CRYPTO_KEY, encoded)
        true
    }

    internal fun clearForTests() = withKeyLock {
        settings = null
    }

    private inline fun <T> withKeyLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
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

internal expect class CredentialKeyLock() {
    fun lock()
    fun unlock()
}
