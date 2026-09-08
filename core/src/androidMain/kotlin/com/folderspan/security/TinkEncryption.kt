package com.folderspan.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager

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

    /**
     * 初始化 Tink 加密
     *
     * @param context Android 应用上下文
     * @throws IllegalStateException 如果初始化失败
     */
    fun initialize(context: Context) {
        if (!::aead.isInitialized) {
            // 注册 AEAD 配置
            AeadConfig.register()

            // 创建或加载密钥集
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, "tink_keyset", "tink_prefs") // 密钥集存储位置
                .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate()) // 使用 AES-256-GCM
                .withMasterKeyUri("android-keystore://tink_master_key") // 主密钥存储在 Android Keystore
                .build()
                .keysetHandle

            // 获取 AEAD 加密原语
            aead = keysetHandle.getPrimitive(Aead::class.java)
        }
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
