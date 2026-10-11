package com.folderspan.settings

import com.folderspan.utils.AesGcm
import com.folderspan.utils.secureRandomBytes
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 把敏感设置加密后写入同一 [Settings] 后端的非敏感键。
 * 包装密钥仍在该存储中，用于避免 Access Key / DEK / 会话 Token 明文落盘。
 */
internal class AesGcmSettingsSecretVault(
    private val storage: Settings,
) : StringSecretVault {
    private val json = Json
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val masterKey: ByteArray = loadOrCreateMasterKey()
    private var values: MutableMap<String, String> = loadValues()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
        persist()
    }

    override fun remove(key: String) {
        if (values.remove(key) != null) {
            persist()
        }
    }

    override fun contains(key: String): Boolean = key in values

    override fun keys(): Set<String> = values.keys.toSet()

    override fun clear() {
        values.clear()
        persist()
    }

    private fun loadOrCreateMasterKey(): ByteArray {
        val stored = storage.getStringOrNull(MASTER_KEY_STORAGE_KEY)?.takeIf { it.isNotBlank() }
        if (stored != null) {
            val decoded = Base64.decode(stored)
            require(decoded.size == AesGcm.KEY_BYTES) { "Web secret-vault master key is invalid" }
            return decoded
        }
        val generated = secureRandomBytes(AesGcm.KEY_BYTES)
        storage.putString(MASTER_KEY_STORAGE_KEY, Base64.encode(generated))
        return generated
    }

    private fun loadValues(): MutableMap<String, String> {
        val stored = storage.getStringOrNull(VALUES_STORAGE_KEY)?.takeIf { it.isNotBlank() }
            ?: return mutableMapOf()
        val decrypted = decrypt(Base64.decode(stored))
        return json.decodeFromString(mapSerializer, decrypted.decodeToString()).toMutableMap()
    }

    private fun persist() {
        if (values.isEmpty()) {
            storage.remove(VALUES_STORAGE_KEY)
            return
        }
        val encoded = json.encodeToString(mapSerializer, values).encodeToByteArray()
        storage.putString(VALUES_STORAGE_KEY, Base64.encode(encrypt(encoded)))
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = secureRandomBytes(AesGcm.NONCE_BYTES)
        return nonce + AesGcm.encrypt(masterKey, nonce, plaintext, AAD)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES) { "Web secret-vault payload is invalid" }
        val nonce = blob.copyOfRange(0, AesGcm.NONCE_BYTES)
        val ciphertextAndTag = blob.copyOfRange(AesGcm.NONCE_BYTES, blob.size)
        return AesGcm.decrypt(masterKey, nonce, ciphertextAndTag, AAD)
    }

    private companion object {
        const val MASTER_KEY_STORAGE_KEY = "settings.web.secretVault.master"
        const val VALUES_STORAGE_KEY = "settings.web.secretVault.values"
        val AAD = "FolderSpan.WebSecretVault.v1".encodeToByteArray()
    }
}
