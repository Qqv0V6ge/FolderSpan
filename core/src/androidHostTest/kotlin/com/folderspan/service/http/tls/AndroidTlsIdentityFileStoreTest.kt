package com.folderspan.service.http.tls

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class AndroidTlsIdentityFileStoreTest {
    @Test
    fun writesAndReadsIdentityThroughAtomicFileReplacement() {
        val directory = Files.createTempDirectory("folderspan-android-tls-store-test")
        try {
            val store = createStore(directory.toFile())
            val firstPayload = "first-pkcs12-payload".encodeToByteArray()
            val secondPayload = "second-pkcs12-payload".encodeToByteArray()

            store.writeIdentityPayload(firstPayload)
            assertContentEquals(firstPayload, store.readIdentityPayload())

            store.writeIdentityPayload(secondPayload)

            val files = directory.toFile().listFiles().orEmpty()
            assertEquals(1, files.size)
            assertEquals(store.identityFileName(), files.single().name)
            assertFalse(files.single().name.contains("identity", ignoreCase = true))
            assertFalse(files.single().name.contains("tls", ignoreCase = true))
            assertContentEquals(secondPayload, store.readIdentityPayload())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ignoresAnIncompleteTemporaryFileFromAnInterruptedWrite() {
        val directory = Files.createTempDirectory("folderspan-android-tls-interrupted-write-test")
        try {
            val store = createStore(directory.toFile())
            val payload = "persisted-pkcs12-payload".encodeToByteArray()
            store.writeIdentityPayload(payload)
            directory.resolve(".${store.identityFileName()}.interrupted.tmp")
                .toFile()
                .writeBytes(byteArrayOf(1, 2, 3))

            assertContentEquals(payload, store.readIdentityPayload())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistedPkcs12KeepsFingerprintAcrossUncachedReloads() {
        val directory = Files.createTempDirectory("folderspan-android-tls-restart-test")
        try {
            val firstStore = createStore(directory.toFile())
            val settings = createInMemorySettings()
            val firstIdentity = DeviceTlsIdentity.loadOrCreateUncached(firstStore, settings)

            val secondStore = createStore(directory.toFile())
            val secondIdentity = DeviceTlsIdentity.loadOrCreateUncached(secondStore, settings)

            assertTrue(firstIdentity.fingerprintSha256.matches(Regex("[0-9A-F]{64}")))
            assertEquals(firstIdentity.fingerprintSha256, secondIdentity.fingerprintSha256)
            assertEquals(1, directory.toFile().listFiles().orEmpty().size)
            val storedPassword = settings.getStringOrNull(SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD)
            assertNotNull(storedPassword)
            assertTrue(storedPassword.isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createStore(directory: java.io.File): AndroidTlsIdentityFileStore {
        return AndroidTlsIdentityFileStore(
            directory = directory,
            keyMaterial = "test-device-key-material",
            random = AndroidDeterministicStorageRandom()
        )
    }
}

private class AndroidDeterministicStorageRandom : TlsIdentityStorageRandom {
    private var intValue = 0
    private var byteValue = 0

    override fun nextInt(until: Int): Int {
        return intValue++.mod(until)
    }

    override fun nextBytes(size: Int): ByteArray {
        return ByteArray(size) {
            byteValue = (byteValue + 1) and 0xFF
            byteValue.toByte()
        }
    }
}
