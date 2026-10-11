package com.folderspan.service.http.tls

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class JvmTlsIdentityFileStoreTest {
    @Test
    fun writesAndReadsIdentityThroughAtomicFileReplacement() {
        val directory = Files.createTempDirectory("fm-tls-store-test")
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
    fun reportsCorruptIdentityFile() {
        val directory = Files.createTempDirectory("fm-tls-store-corrupt-test")
        try {
            val store = createStore(directory.toFile())
            store.writeIdentityPayload("payload".encodeToByteArray())
            directory.toFile().listFiles().orEmpty().single().writeBytes(byteArrayOf(1, 2, 3))

            assertFailsWith<IOException> {
                store.readIdentityPayload()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAuthenticatedIdentityAfterCiphertextBitFlip() {
        val directory = Files.createTempDirectory("fm-tls-store-tamper-test")
        try {
            val store = createStore(directory.toFile())
            store.writeIdentityPayload("authenticated-payload".encodeToByteArray())
            val identityFile = directory.resolve(store.identityFileName()).toFile()
            val tampered = identityFile.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            identityFile.writeBytes(tampered)

            assertFailsWith<IOException> {
                store.readIdentityPayload()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsLegacyCbcIdentityWithoutMigration() {
        val directory = Files.createTempDirectory("fm-tls-legacy-reject-test")
        try {
            val store = createStore(directory.toFile())
            val payload = "legacy-persisted-identity".encodeToByteArray()
            val identityFile = directory.resolve(store.identityFileName()).toFile()
            val legacyBytes = encodeLegacyIdentityContainer(payload, TEST_KEY_MATERIAL)
            identityFile.writeBytes(legacyBytes)

            assertFailsWith<IOException> {
                store.readIdentityPayload()
            }
            assertContentEquals(legacyBytes, identityFile.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptPersistedIdentityIsNotSilentlyRegenerated() {
        val directory = Files.createTempDirectory("fm-tls-no-regeneration-test")
        try {
            val store = createStore(directory.toFile())
            store.writeIdentityPayload("persisted-identity".encodeToByteArray())
            val identityFile = directory.resolve(store.identityFileName()).toFile()
            val tampered = identityFile.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            identityFile.writeBytes(tampered)
            val bytesBeforeLoad = identityFile.readBytes()

            assertFailsWith<IOException> {
                DeviceTlsIdentity.loadOrCreateUncached(store, MapSettings())
            }
            assertContentEquals(bytesBeforeLoad, identityFile.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ignoresAnIncompleteTemporaryFileFromAnInterruptedWrite() {
        val directory = Files.createTempDirectory("fm-tls-interrupted-write-test")
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
    fun failedAtomicReplacementPreservesPreviousIdentity() {
        val directory = Files.createTempDirectory("fm-tls-replacement-failure-test")
        try {
            val previousPayload = "previous-pkcs12-payload".encodeToByteArray()
            val store = createStore(directory.toFile())
            store.writeIdentityPayload(previousPayload)
            val failingStore = createStore(
                directory = directory.toFile(),
                replaceIdentityFile = { _, _ -> throw IOException("simulated atomic replacement failure") }
            )

            assertFailsWith<IOException> {
                failingStore.writeIdentityPayload("replacement-pkcs12-payload".encodeToByteArray())
            }

            assertContentEquals(previousPayload, store.readIdentityPayload())
            assertEquals(1, directory.toFile().listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistedPkcs12KeepsFingerprintAcrossUncachedReloads() {
        val directory = Files.createTempDirectory("fm-tls-restart-test")
        try {
            val settings = MapSettings()
            val firstIdentity = DeviceTlsIdentity.loadOrCreateUncached(createStore(directory.toFile()), settings)
            val secondIdentity = DeviceTlsIdentity.loadOrCreateUncached(createStore(directory.toFile()), settings)

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

    @Test
    fun createsEntropySaltAndReloadsTheSameIdentityPayload() {
        val directory = Files.createTempDirectory("fm-tls-salt-test")
        try {
            val deviceId = "device-for-salt"
            val payload = "pkcs12-payload".encodeToByteArray()
            val opened = openJvmTlsIdentityFileStore(
                directory = directory.toFile(),
                deviceId = deviceId,
                random = JvmDeterministicStorageRandom(),
            )
            opened.writeIdentityPayload(payload)

            assertTrue(directory.resolve(TlsIdentityStorageKeyMaterial.ENTROPY_SALT_FILE).toFile().isFile)
            assertContentEquals(payload, opened.readIdentityPayload())

            val reopened = openJvmTlsIdentityFileStore(
                directory = directory.toFile(),
                deviceId = deviceId,
                random = JvmDeterministicStorageRandom(),
            )
            assertContentEquals(payload, reopened.readIdentityPayload())
            assertEquals(opened.identityFileName(), reopened.identityFileName())
            assertOwnerOnlyDirectory(directory)
            assertOwnerOnlyFile(directory.resolve(TlsIdentityStorageKeyMaterial.ENTROPY_SALT_FILE))
            assertOwnerOnlyFile(directory.resolve(opened.identityFileName()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun tlsIdentityKeyMaterialDiffersAcrossRandomSalts() {
        val deviceId = "public-device-id"
        val directory = "/Library/Application Support/FolderSpan/tls-identity"
        val first = TlsIdentityStorageKeyMaterial.build(deviceId, directory, entropySaltHex = "AA".repeat(32))
        val second = TlsIdentityStorageKeyMaterial.build(deviceId, directory, entropySaltHex = "BB".repeat(32))
        assertNotEquals(first, second)
        assertTrue(first.contains(TlsIdentityStorageKeyMaterial.CONSTANT_SALT))
        assertTrue(first.contains(deviceId))
    }

    @Test
    fun iosTlsIdentityStoreUsesEntropySaltInsteadOfHardcodedStorageSalt() {
        val text = locateRepoFile(
            "core/src/iosMain/kotlin/com/folderspan/service/http/tls/IosTlsIdentityFileStore.kt"
        ).readText()

        assertFalse(text.contains("const val STORAGE_SALT"))
        assertTrue(text.contains("openIosTlsIdentityFileStore"))
        assertTrue(text.contains("TlsIdentityStorageKeyMaterial.build"))
        assertTrue(text.contains("ENTROPY_SALT_FILE") || text.contains("storage.salt"))
    }

    private fun assertOwnerOnlyDirectory(path: java.nio.file.Path) {
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            permissions,
        )
    }

    private fun assertOwnerOnlyFile(path: java.nio.file.Path) {
        if (!Files.exists(path)) return
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
        )
    }

    private fun createStore(
        directory: File,
        replaceIdentityFile: ((source: File, destination: File) -> Unit)? = null
    ): JvmTlsIdentityFileStore {
        val random = JvmDeterministicStorageRandom()
        return if (replaceIdentityFile == null) {
            JvmTlsIdentityFileStore(
                directory = directory,
                keyMaterial = TEST_KEY_MATERIAL,
                random = random
            )
        } else {
            JvmTlsIdentityFileStore(
                directory = directory,
                keyMaterial = TEST_KEY_MATERIAL,
                random = random,
                replaceIdentityFile = replaceIdentityFile
            )
        }
    }

    private companion object {
        const val TEST_KEY_MATERIAL = "test-device-key-material"
    }
}

private fun encodeLegacyIdentityContainer(payload: ByteArray, keyMaterial: String): ByteArray {
    val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(keyMaterial.encodeToByteArray()),
        "AES",
    )
    val segments = listOf(
        byteArrayOf(0x49) + payload,
        byteArrayOf(0x52, 0x01, 0x02),
        byteArrayOf(0x52, 0x03, 0x04),
    )
    return ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.write(byteArrayOf(0x46, 0x53, 0x54, 0x4C, 0x53, 0x49, 0x44, 0x31))
            output.writeByte(1)
            output.writeByte(segments.size)
            segments.forEachIndexed { index, plain ->
                val iv = ByteArray(16) { offset -> (index * 16 + offset + 1).toByte() }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
                val encrypted = cipher.doFinal(plain)
                output.writeShort(iv.size)
                output.writeInt(encrypted.size)
                output.write(iv)
                output.write(encrypted)
            }
        }
        buffer.toByteArray()
    }
}

private class JvmDeterministicStorageRandom(
    private val nextInts: MutableList<Int> = mutableListOf()
) : TlsIdentityStorageRandom {
    private var byteValue = 0

    override fun nextInt(until: Int): Int {
        val value = if (nextInts.isEmpty()) 0 else nextInts.removeAt(0)
        return value.mod(until)
    }

    override fun nextBytes(size: Int): ByteArray {
        return ByteArray(size) {
            byteValue = (byteValue + 1) and 0xFF
            byteValue.toByte()
        }
    }
}

private fun locateRepoFile(relativePath: String): File {
    var directory = File(System.getProperty("user.dir")).absoluteFile
    repeat(8) {
        val candidate = File(directory, relativePath)
        if (candidate.isFile) return candidate
        directory = directory.parentFile ?: return@repeat
    }
    error("missing $relativePath from ${System.getProperty("user.dir")}")
}
