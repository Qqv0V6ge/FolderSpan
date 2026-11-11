package com.folderspan.service.network

import androidx.test.platform.app.InstrumentationRegistry
import com.folderspan.AndroidContextHolder
import com.folderspan.createSettings
import com.folderspan.security.TinkEncryption
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import java.security.KeyStore
import kotlin.test.*

class StorageContentPasswordAndroidTest {
    @Test
    fun portablePasswordVectorPreservesSpacesAndUnicodeOnAndroid() {
        val encoded = ("465350414e564c540000000100112233445566778899aabbccddeeff000927c0" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b" +
            "af356b12c397c547a226aa368230f33c6f56444de33b8beb81cb20ad2ce35a270e84fd0d1eb45f1b51cbeadd9ea4f972")
            .decodeHex().toByteArray()
        val password = " 密码 café 🔐 pass ".toCharArray()
        val unlocked = StorageContentPasswordLocation.unlock(encoded, password)
        try {
            assertContentEquals(ByteArray(32) { it.toByte() }, unlocked.key)
            assertEquals("00112233-4455-6677-8899-aabbccddeeff", unlocked.vaultId)
            assertFails { StorageContentPasswordLocation.unlock(encoded, password.concatToString().trim().toCharArray()) }
            assertEquals(StorageContentErrorCode.UnlockFailed,
                (assertFails { StorageContentPasswordLocation.unlock(encoded, "wrong".toCharArray()) } as StorageContentException).code)
        } finally { unlocked.key.fill(0); password.fill('\u0000') }
    }

    @Test
    fun protectedKeysStayLockedAfterCloseAndAuthenticateRealFileBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidContextHolder.initialize(context)
        TinkEncryption.requireContentKeyProtection(context)
        val key = StorageContentEncryptionV1.newKey()
        val vault = java.util.UUID.randomUUID().toString()
        val scope = "android-password-test:" + vault
        val owner = "disposable-android-owner"
        val settings = createSettings()
        val keyName = "auth.storage.password-location.key." +
            StorageContentCrypto.sha256(scope.encodeToByteArray()).toByteString().hex()
        val directory = context.cacheDir.absolutePath.toPath() / ("password-test-" + vault)
        StorageContentFileProtection.createPrivateDirectory(directory)
        val plain = ByteArray(StorageContentEncryptionV1.CHUNK_BYTES + 31) { (it % 251).toByte() }
        val destination = directory / "verified"
        try {
            assertNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("tink_master_key", null).encoded)
            StorageContentKeyStore(settings).use { keys ->
                keys.unlockPasswordLocation(scope, owner, key)
                assertEquals(true to true, keys.passwordLocationStatus(scope, owner))
                val encrypted = Buffer()
                val cipherScope = storageContentObjectScope(vault, java.util.UUID.randomUUID().toString())
                val operationKey = keys.keyForPasswordOperation(scope, owner)
                try { StorageContentEncryptionV1.encrypt(Buffer().write(plain), encrypted, operationKey, cipherScope) }
                finally { operationKey.fill(0) }
                val bytes = encrypted.readByteArray()
                val stored = settings.getString(keyName, "")
                val persisted = FileSystem.SYSTEM.read(context.filesDir.absolutePath.toPath()
                    .resolve("datastore/secure_settings.preferences_pb")) { readByteArray().decodeToString() }
                assertTrue(stored.isNotEmpty())
                assertFalse(persisted.contains(stored))
                assertFalse(persisted.contains(key.toByteString().base64()))
                FileSystem.SYSTEM.write(destination) { writeUtf8("keep original") }
                val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(damaged), destination, key, cipherScope) }
                assertEquals("keep original", FileSystem.SYSTEM.read(destination) { readUtf8() })
                StorageContentEncryptionV1.decryptAndPublish(Buffer().write(bytes), destination, key, cipherScope)
                assertContentEquals(plain, FileSystem.SYSTEM.read(destination) { readByteArray() })
                keys.lockPasswordLocation(scope, owner)
                assertFails { keys.keyForPasswordOperation(scope, owner) }
            }
            StorageContentKeyStore(createSettings()).use { reopened ->
                assertEquals(true to false, reopened.passwordLocationStatus(scope, owner))
                assertFails { reopened.keyForPasswordOperation(scope, owner) }
            }
        } finally {
            settings.remove(keyName)
            key.fill(0); plain.fill(0)
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }
}
