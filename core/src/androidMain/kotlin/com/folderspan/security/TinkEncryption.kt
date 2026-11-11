package com.folderspan.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.subtle.Hex
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tink 加密管理器
 *
 * 使用 Google Tink 库提供安全的加密/解密功能。
 * 密钥存储在 Android Keystore 中，提供硬件级别的安全保护。
 *
 * 特性：
 * - 使用 AES-256-GCM 加密算法
 * - 密钥由 Android Keystore 保护
 * - 密钥集存储在加密的 SharedPreferences 中
 */
object TinkEncryption {
    /** AEAD (Authenticated Encryption with Associated Data) 加密原语 */
    private lateinit var aead: Aead
    private lateinit var keysetManager: AndroidKeysetManager
    private var contentProtectionVerified = false

    /**
     * 初始化 Tink 加密
     *
     * @param context Android 应用上下文
     * @throws IllegalStateException 如果初始化失败
     */
    @Synchronized
    fun initialize(context: Context) {
        if (!::aead.isInitialized) {
            // 注册 AEAD 配置
            AeadConfig.register()

            // 创建或加载密钥集
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context, "tink_keyset", "tink_prefs") // 密钥集存储位置
                .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM)) // 使用 AES-256-GCM
                .withMasterKeyUri("android-keystore://tink_master_key") // 主密钥存储在 Android Keystore
                .build()

            // 普通设置沿用现有兼容行为；内容密钥另行强制验证 Keystore 保护。
            val primitive = manager.keysetHandle.getPrimitive(AeadConfig2026.get(), Aead::class.java)
            keysetManager = manager
            aead = primitive
        }
    }

    /** Existing settings remain usable, but content keys require a verified encrypted on-disk keyset. */
    @Synchronized
    fun requireContentKeyProtection(context: Context) {
        initialize(context)
        if (contentProtectionVerified) return
        val verified = enforceKeystoreProtectedKeyset(context, "tink_keyset", "tink_prefs",
            "android-keystore://tink_master_key")
        val primitive = verified.keysetHandle.getPrimitive(AeadConfig2026.get(), Aead::class.java)
        keysetManager = verified
        aead = primitive
        contentProtectionVerified = true
    }

    /**
     * 获取 AEAD 加密原语
     *
     * @return AEAD 实例
     * @throws IllegalStateException 如果未先调用 initialize()
     */
    fun getAead(): Aead {
        if (!::aead.isInitialized) {
            throw IllegalStateException("TinkEncryption must be initialized first")
        }
        return aead
    }
}


/** Rewrap the same keyset, then authenticate the persisted bytes with Android Keystore directly. */
internal fun enforceKeystoreProtectedKeyset(
    context: Context, keysetName: String, preferencesName: String, masterKeyUri: String,
): AndroidKeysetManager {
    val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    check(preferences.contains(keysetName)) { "Existing settings keyset is unavailable" }
    // Loading an existing cleartext keyset never creates its missing master key.
    // This public API creates only an absent alias and validates any existing key without replacing it.
    val masterAead = AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(masterKeyUri)
    val manager = AndroidKeysetManager.Builder()
        .withSharedPref(context, keysetName, preferencesName)
        .withMasterKeyUri(masterKeyUri)
        .build()
    check(manager.isUsingKeystore) { "Android Keystore protection is unavailable for content keys" }
    val handle = manager.keysetHandle
    // Rewriting the unchanged primary wraps legacy cleartext keysets without rotating any settings key.
    manager.setPrimary(handle.primary.id)
    val serialized = Hex.decode(checkNotNull(preferences.getString(keysetName, null)))
    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
    val aad = "FolderSpan content vault verification v1".toByteArray(Charsets.UTF_8)
    try {
        val persisted = TinkProtoKeysetFormat.parseEncryptedKeyset(
            serialized, masterAead, byteArrayOf(), AeadConfig2026.get())
        check(persisted.equalsKeyset(handle)) { "Persisted content vault keyset changed" }
        val originalAead = handle.getPrimitive(AeadConfig2026.get(), Aead::class.java)
        val persistedAead = persisted.getPrimitive(AeadConfig2026.get(), Aead::class.java)
        val ciphertext = originalAead.encrypt(challenge, aad)
        val plaintext = persistedAead.decrypt(ciphertext, aad)
        try { check(MessageDigest.isEqual(challenge, plaintext)) { "Persisted content vault verification failed" } }
        finally { ciphertext.fill(0); plaintext.fill(0) }
    } finally { serialized.fill(0); challenge.fill(0) }
    return manager
}
