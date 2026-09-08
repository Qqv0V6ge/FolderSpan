package com.folderspan.pro.core.network.cache

import strings.AppStrings

import kotlinx.coroutines.test.runTest
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProApiCacheAesGcmTest {
    @Test
    fun proHttpCacheMustNotUseCompileTimeGlobalKey() {
        val source = listOf(
            File("proMain/kotlin/com/folderspan/pro/core/network/cache/ProApiCacheCrypto.kt"),
            File("../proMain/kotlin/com/folderspan/pro/core/network/cache/ProApiCacheCrypto.kt"),
            File("kotlin/com/folderspan/pro/core/network/cache/ProApiCacheCrypto.kt"),
        ).firstOrNull { file -> file.exists() }
            ?: error(AppStrings.ui_test_pro_api_cache_aes_gcm_not_found_proapicachecrypto_kt)
        val text = source.readText()
        assertFalse("EMBEDDED_SECRET_KEY" in text)
        assertFalse(Regex("""byteArrayOf\s*\(\s*0x51\s*,\s*0x0d""").containsMatchIn(text))
    }

    @Test
    fun jvmProHttpCacheRootIsApplicationDataNotSystemTmp() {
        val cache = File(FileApiResponseCacheStore.defaultRootDirectory()).canonicalFile
        val tmp = File(System.getProperty("java.io.tmpdir")).canonicalFile
        assertFalse(cache == tmp || cache.path.startsWith(tmp.path + File.separator))
        assertEquals("pro_http_cache", cache.name)
    }

    @Test
    fun twoInstallationsProduceNonInterchangeableCacheCiphertext() = runTest {
        val first = ProApiCacheCrypto(object : ProApiCacheKeyProvider {
            override fun keyMaterial() = ProApiCacheKeyMaterial(ByteArray(32) { 1 })
        })
        val second = ProApiCacheCrypto(object : ProApiCacheKeyProvider {
            override fun keyMaterial() = ProApiCacheKeyMaterial(ByteArray(32) { 2 })
        })
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/me", userScope = "token")
        val encrypted = first.encrypt(
            plainText = """{"email":"user@example.test"}""".encodeToByteArray(),
            namespace = "user",
            key = key,
        )

        assertNull(second.decrypt(encrypted, namespace = "user", key = key))
        assertContentEquals(
            """{"email":"user@example.test"}""".encodeToByteArray(),
            first.decrypt(encrypted, namespace = "user", key = key),
        )
    }

    @Test
    fun encryptMatchesJvmAesGcmReference() = runTest {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index * 3 + 7).toByte() }
        val aad = "1\npro-api\ncache-key".encodeToByteArray()
        val plainText = "vip-cache-payload".encodeToByteArray()

        val actual = ProApiCacheAesGcm.encrypt(
            plainText = plainText,
            key = key,
            nonce = nonce,
            associatedData = aad,
        )

        val reference = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plainText)
        }

        assertContentEquals(reference.copyOfRange(0, reference.size - 16), actual.cipherText)
        assertContentEquals(reference.copyOfRange(reference.size - 16, reference.size), actual.tag)
    }

    @Test
    fun decryptRestoresPlainText() = runTest {
        val key = ByteArray(32) { index -> (index + 11).toByte() }
        val nonce = ByteArray(12) { index -> (index * 5 + 3).toByte() }
        val aad = "1\nplugin-list\ncache-key".encodeToByteArray()
        val plainText = "{\"code\":0}".encodeToByteArray()
        val sealed = ProApiCacheAesGcm.encrypt(
            plainText = plainText,
            key = key,
            nonce = nonce,
            associatedData = aad,
        )

        val decrypted = ProApiCacheAesGcm.decrypt(
            cipherText = sealed.cipherText,
            tag = sealed.tag,
            key = key,
            nonce = nonce,
            associatedData = aad,
        )

        assertContentEquals(plainText, decrypted)
    }
}
