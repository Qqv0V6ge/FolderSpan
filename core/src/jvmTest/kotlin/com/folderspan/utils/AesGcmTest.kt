package com.folderspan.utils

import com.folderspan.test.createInMemorySettings
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmTest {
    @Test
    fun matchesJvmAesGcm() {
        val key = ByteArray(32) { index -> (index + 3).toByte() }
        val nonce = ByteArray(12) { index -> (index + 9).toByte() }
        val aad = "FolderSpan.CredentialCrypto.v1".encodeToByteArray()
        val plaintext = "network-password".encodeToByteArray()

        val sealed = AesGcm.encrypt(key, nonce, plaintext, aad)
        val expected = jvmAesGcmEncrypt(key, nonce, plaintext, aad)

        assertContentEquals(expected, sealed)
        assertContentEquals(plaintext, AesGcm.decrypt(key, nonce, sealed, aad))
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12) { 11 }
        val aad = "FolderSpan.CredentialCrypto.v1".encodeToByteArray()
        val sealed = AesGcm.encrypt(key, nonce, "secret".encodeToByteArray(), aad).clone()
        sealed[sealed.lastIndex] = (sealed.lastIndex.toByte())

        assertFailsWith<IllegalArgumentException> {
            AesGcm.decrypt(key, nonce, sealed, aad)
        }
    }

    private fun jvmAesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }
}

class DataEncryptionKeyTest {
    @AfterTest
    fun tearDown() {
        DataEncryptionKey.clearForTests()
    }

    @Test
    fun rejectsLegacyHardcodedKeyAndCreatesRandomDek() {
        val settings = createInMemorySettings(
            SettingsUtils.KEY_CRYPTO_KEY to "J83YldwkAf924SMxRbqhqBq3jaqwsO9x",
        )
        DataEncryptionKey.init(settings)

        assertEquals(null, DataEncryptionKey.encodedOrNull())
        val first = DataEncryptionKey.keyBytes()
        val second = DataEncryptionKey.keyBytes()
        assertEquals(32, first.size)
        assertContentEquals(first, second)
        assertTrue(DataEncryptionKey.isValidEncoded(settings.getString(SettingsUtils.KEY_CRYPTO_KEY, "")))
        assertFalse(DataEncryptionKey.isValidEncoded("J83YldwkAf924SMxRbqhqBq3jaqwsO9x"))
    }

    @Test
    fun remoteDekReplacesLocalKey() {
        val settings = createInMemorySettings()
        DataEncryptionKey.init(settings)
        val local = DataEncryptionKey.keyBytes()
        val remote = DataEncryptionKey.encode(ByteArray(32) { 42 })

        assertTrue(DataEncryptionKey.replaceFromRemote(remote))
        assertNotEquals(local.toList(), DataEncryptionKey.keyBytes().toList())
        assertContentEquals(ByteArray(32) { 42 }, DataEncryptionKey.keyBytes())
    }
}

class CredentialCryptoTest {
    @AfterTest
    fun tearDown() {
        DataEncryptionKey.clearForTests()
    }

    @Test
    fun sameDekDecryptsOnAnotherSettingsStore() {
        val first = createInMemorySettings()
        DataEncryptionKey.init(first)
        val ciphertext = SymmetricCrypto.encrypt("smb-secret")
        val encodedDek = DataEncryptionKey.encodedForSync()

        val second = createInMemorySettings()
        DataEncryptionKey.init(second)
        assertTrue(DataEncryptionKey.replaceFromRemote(checkNotNull(encodedDek)))
        assertEquals("smb-secret", SymmetricCrypto.decrypt(ciphertext))
    }
}
