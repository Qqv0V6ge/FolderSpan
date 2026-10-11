package com.folderspan.service.http.tls

import strings.AppStrings

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosTlsIdentityFileStoreTest {
    @Test
    fun replacesVerifiedTemporaryFileAtomicallyInSameDirectory() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = createStore(files)
        val firstPayload = "first-ios-tls-identity".encodeToByteArray()
        val secondPayload = "second-ios-tls-identity".encodeToByteArray()

        store.writeIdentityPayload(firstPayload)
        store.writeIdentityPayload(secondPayload)

        assertContentEquals(secondPayload, store.readIdentityPayload())
        assertEquals(2, files.atomicReplacements.size)
        files.atomicReplacements.forEachIndexed { index, (source, destination) ->
            assertEquals(source.substringBeforeLast('/'), destination.substringBeforeLast('/'))
            assertEquals(source, files.syncedPaths[index])
        }
        assertEquals(setOf("$TEST_DIRECTORY/${store.identityFileName()}"), files.paths())
    }

    @Test
    fun failedTemporaryFileVerificationPreservesOldIdentity() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = createStore(files)
        val oldPayload = "old-ios-tls-identity".encodeToByteArray()
        store.writeIdentityPayload(oldPayload)
        files.corruptNextWrite = true

        assertFailsWith<IllegalStateException> {
            store.writeIdentityPayload("new-ios-tls-identity".encodeToByteArray())
        }

        assertContentEquals(oldPayload, store.readIdentityPayload())
        assertEquals(setOf("$TEST_DIRECTORY/${store.identityFileName()}"), files.paths())
    }

    @Test
    fun failedAtomicReplacementPreservesOldIdentity() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = createStore(files)
        val oldPayload = "old-ios-tls-identity".encodeToByteArray()
        store.writeIdentityPayload(oldPayload)
        files.failNextAtomicReplacement = true

        assertFailsWith<IllegalStateException> {
            store.writeIdentityPayload("new-ios-tls-identity".encodeToByteArray())
        }

        assertContentEquals(oldPayload, store.readIdentityPayload())
        assertEquals(setOf("$TEST_DIRECTORY/${store.identityFileName()}"), files.paths())
    }

    @Test
    fun corruptPersistedIdentityIsReportedInsteadOfTreatedAsMissing() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = createStore(files)
        store.writeIdentityPayload("ios-tls-identity".encodeToByteArray())
        files.overwrite("$TEST_DIRECTORY/${store.identityFileName()}", byteArrayOf(1, 2, 3))

        val error = assertFailsWith<IllegalStateException> {
            store.readIdentityPayload()
        }

        assertTrue(error.message.orEmpty().contains(AppStrings.ui_test_ios_tls_identity_file_store_invalid_or_damaged))
    }

    @Test
    fun leftoverTemporaryFileDoesNotAffectPersistedIdentity() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = createStore(files)
        val payload = "persisted-ios-tls-identity".encodeToByteArray()
        store.writeIdentityPayload(payload)
        files.put(
            "$TEST_DIRECTORY/.${store.identityFileName()}.interrupted.tmp",
            byteArrayOf(1, 2, 3),
        )

        assertContentEquals(payload, store.readIdentityPayload())
    }

    @Test
    fun openStoreWritesEntropySaltAndDoesNotReuseHardcodedKeyMaterial() {
        val files = FakeIosTlsIdentityFileOperations()
        val store = openIosTlsIdentityFileStore(
            directory = TEST_DIRECTORY,
            deviceId = "ios-device",
            random = IosDeterministicStorageRandom(),
            fileOperations = files,
        )
        val payload = "ios-tls-identity-with-salt".encodeToByteArray()
        store.writeIdentityPayload(payload)

        val saltPath = "$TEST_DIRECTORY/${TlsIdentityStorageKeyMaterial.ENTROPY_SALT_FILE}"
        assertTrue(files.fileExists(saltPath))
        assertTrue(files.readFile(saltPath).isNotEmpty())
        assertContentEquals(payload, store.readIdentityPayload())
        assertTrue(files.paths().contains(saltPath))
        assertNotEquals(
            TlsIdentityStorageKeyMaterial.build("ios-device", TEST_DIRECTORY, "AA".repeat(32)),
            TlsIdentityStorageKeyMaterial.build("ios-device", TEST_DIRECTORY, "BB".repeat(32)),
        )
    }

    private fun createStore(fileOperations: FakeIosTlsIdentityFileOperations): IosTlsIdentityFileStore {
        return IosTlsIdentityFileStore(
            directory = TEST_DIRECTORY,
            keyMaterial = "ios-test-device-key-material",
            random = IosDeterministicStorageRandom(),
            fileOperations = fileOperations,
        )
    }
}

private class FakeIosTlsIdentityFileOperations : IosTlsIdentityFileOperations {
    private val files = mutableMapOf<String, ByteArray>()
    val atomicReplacements = mutableListOf<Pair<String, String>>()
    val syncedPaths = mutableListOf<String>()
    var corruptNextWrite: Boolean = false
    var failNextAtomicReplacement: Boolean = false

    override fun ensureDirectory(path: String) = Unit

    override fun fileExists(path: String): Boolean = path in files

    override fun readFile(path: String): ByteArray {
        return files[path]?.copyOf() ?: error("Missing fake file: $path")
    }

    override fun writeNewFile(path: String, bytes: ByteArray) {
        check(path !in files) { "Fake file already exists: $path" }
        files[path] = if (corruptNextWrite) {
            corruptNextWrite = false
            byteArrayOf(1, 2, 3)
        } else {
            bytes.copyOf()
        }
    }

    override fun syncFile(path: String) {
        check(path in files) { "Missing fake file to sync: $path" }
        syncedPaths += path
    }

    override fun replaceFileAtomically(source: String, destination: String) {
        if (failNextAtomicReplacement) {
            failNextAtomicReplacement = false
            throw IllegalStateException("Injected atomic replacement failure")
        }
        val sourceBytes = files.remove(source) ?: error("Missing fake source file: $source")
        files[destination] = sourceBytes
        atomicReplacements += source to destination
    }

    override fun deleteFileIfExists(path: String) {
        files.remove(path)
    }

    fun paths(): Set<String> = files.keys.toSet()

    fun overwrite(path: String, bytes: ByteArray) {
        check(path in files) { "Missing fake file: $path" }
        files[path] = bytes.copyOf()
    }

    fun put(path: String, bytes: ByteArray) {
        check(path !in files) { "Fake file already exists: $path" }
        files[path] = bytes.copyOf()
    }
}

private class IosDeterministicStorageRandom : TlsIdentityStorageRandom {
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

private const val TEST_DIRECTORY = "/ios-tls-store-test"
