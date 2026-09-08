package com.folderspan.utils

import kotlin.io.encoding.Base64

/**
 * 对称加密接口，定义加密与解密的基本操作。
 */
interface SymmetricCryptoInterface {
    fun encrypt(text: String): String
    fun decrypt(text: String): String
    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * 本地凭据加密：AES-256-GCM，包装密钥来自配置目标级 DEK。
 */
object SymmetricCrypto : SymmetricCryptoInterface {
    private val aad = "FolderSpan.CredentialCrypto.v1".encodeToByteArray()

    override fun encrypt(text: String): String =
        Base64.encode(encrypt(text.encodeToByteArray()))

    override fun decrypt(text: String): String =
        decrypt(Base64.decode(text)).decodeToString()

    override fun encrypt(data: ByteArray): ByteArray {
        val nonce = secureRandomBytes(AesGcm.NONCE_BYTES)
        val sealed = AesGcm.encrypt(
            key = DataEncryptionKey.keyBytes(),
            nonce = nonce,
            plaintext = data,
            aad = aad,
        )
        return nonce + sealed
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size >= AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES) { "Encrypted payload too short" }
        val nonce = data.copyOfRange(0, AesGcm.NONCE_BYTES)
        val ciphertextAndTag = data.copyOfRange(AesGcm.NONCE_BYTES, data.size)
        return AesGcm.decrypt(
            key = DataEncryptionKey.keyBytes(),
            nonce = nonce,
            ciphertextAndTag = ciphertextAndTag,
            aad = aad,
        )
    }
}
