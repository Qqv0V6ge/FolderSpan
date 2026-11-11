package com.folderspan.service.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StorageContentEncryptionV1Test {
    private val key = ByteArray(32) { it.toByte() }
    private val scope = "runtime-A/connection-A/library-A/folder-A"

    private fun encrypt(data: ByteArray): ByteArray = ByteArrayOutputStream().also {
        StorageContentEncryptionV1.encrypt(ByteArrayInputStream(data), it, key, scope)
    }.toByteArray()

    @Test
    fun emptyAndMultipleChunksRoundTrip() {
        val directory = Files.createTempDirectory("storage-content-test")
        try {
            for (size in listOf(0, 17, 2 * StorageContentEncryptionV1.CHUNK_BYTES + 13)) {
                val source = ByteArray(size) { (it % 251).toByte() }
                val destination = directory.resolve("result")
                val encrypted = encrypt(source)
                StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(encrypted), destination, key, scope)
                assertContentEquals(source, Files.readAllBytes(destination))
                assertFalse(encrypted.contentEquals(encrypt(source)))
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun corruptionTruncationUnknownFormatAndWrongKeysNeverReplaceDestination() {
        val encrypted = encrypt(ByteArray(67) { it.toByte() })
        val directory = Files.createTempDirectory("storage-content-test")
        val destination = directory.resolve("existing")
        val previous = "existing data".toByteArray()
        try {
            Files.write(destination, previous)
            val invalid = listOf(
                encrypted.copyOf(0), encrypted.copyOf(123), encrypted.copyOf(encrypted.size - 1),
                encrypted + byteArrayOf(1),
                encrypted.copyOf().also { it[11] = 2 },
                encrypted.copyOf().also { it[124] = (it[124].toInt() xor 1).toByte() },
                encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            )
            for (data in invalid) {
                assertFails {
                    StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(data), destination, key, scope)
                }
                assertContentEquals(previous, Files.readAllBytes(destination))
            }
            for ((badKey, badScope) in listOf(ByteArray(32) to scope, key to "another-account")) {
                assertFails {
                    StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(encrypted), destination, badKey, badScope)
                }
            }
            assertContentEquals(previous, Files.readAllBytes(destination))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun reorderedFramesAndCancelledDownloadsAreRejectedAndCleaned() {
        val encrypted = encrypt(ByteArray(2 * StorageContentEncryptionV1.CHUNK_BYTES) { (it % 251).toByte() })
        val frameLength = StorageContentEncryptionV1.CHUNK_BYTES + 20
        val reordered = encrypted.copyOf()
        encrypted.copyInto(reordered, 124, 124 + frameLength, 124 + 2 * frameLength)
        encrypted.copyInto(reordered, 124 + frameLength, 124, 124 + frameLength)
        val directory = Files.createTempDirectory("storage-content-test")
        val destination = directory.resolve("result")
        try {
            assertFails {
                StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(reordered), destination, key, scope)
            }
            var calls = 0
            assertFails {
                StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(encrypted), destination, key, scope) {
                    if (++calls == 2) throw CancellationException("cancel")
                }
            }
            assertFalse(Files.exists(destination))
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun recoveryIsPasswordProtectedScopedAndIndependentOfAccountGrant() {
        val password = "correct recovery password".toCharArray()
        val backup = StorageContentEncryptionV1.exportRecovery(key, scope, password)
        assertEquals(140, backup.size)
        assertContentEquals(key, StorageContentEncryptionV1.importRecovery(backup, scope, password))
        assertFails { StorageContentEncryptionV1.importRecovery(backup, scope, "incorrect password".toCharArray()) }
        assertFails { StorageContentEncryptionV1.importRecovery(backup, "another-account", password) }
        assertFails { StorageContentEncryptionV1.importRecovery(backup.copyOf().also { it[11] = 2 }, scope, password) }
        assertFalse(backup.contentEquals(StorageContentEncryptionV1.exportRecovery(key, scope, password)))
        assertNotEquals(key.toList(), backup.takeLast(32))
    }

    @Test
    fun independentNodeReferenceVectorDecryptsAndRecovers() {
        val vector = javaClass.getResourceAsStream("/storage-content-v1-vector.json")!!.use {
            Json.parseToJsonElement(it.readAllBytes().decodeToString()).jsonObject
        }
        fun text(name: String) = vector.getValue(name).jsonPrimitive.content
        fun bytes(name: String) = text(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val directory = Files.createTempDirectory("storage-content-vector")
        try {
            val key = StorageContentEncryptionV1.importRecovery(bytes("recoveryHex"), text("scope"), text("recoveryPassword").toCharArray())
            assertContentEquals(bytes("locationKeyHex"), key)
            assertContentEquals(key, StorageContentEncryptionV1.importRecovery(bytes("recoveryUnicodeHex"), text("scope"), text("recoveryUnicodePassword").toCharArray()))
            val destination = directory.resolve("plain")
            StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(bytes("ciphertextHex")), destination, key, text("scope"))
            assertContentEquals(bytes("plaintextHex"), Files.readAllBytes(destination))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun freshOutputGetsFreshFileIdentityAndKeyWrappingNonce() {
        val first = encrypt(byteArrayOf(1))
        val second = encrypt(byteArrayOf(1))
        assertFalse(first.copyOfRange(16, 32).contentEquals(second.copyOfRange(16, 32)))
        assertFalse(first.copyOfRange(64, 76).contentEquals(second.copyOfRange(64, 76)))
        assertTrue(first.size > 124)
    }

    @Test
    fun shortAndZeroLengthReadsKeepFullAuthenticatedFrames() {
        val plain = ByteArray(StorageContentEncryptionV1.CHUNK_BYTES + 17) { (it % 251).toByte() }
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(plain)
            private var calls = 0
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++calls % 11 == 0) return 0
                return delegate.read(buffer, offset, minOf(length, 4093))
            }
        }
        val encrypted = ByteArrayOutputStream().also {
            StorageContentEncryptionV1.encrypt(input, it, key, scope)
        }.toByteArray()
        val directory = Files.createTempDirectory("storage-content-short-read")
        try {
            val destination = directory.resolve("plain")
            StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(encrypted), destination, key, scope)
            assertContentEquals(plain, Files.readAllBytes(destination))
            assertEquals(StorageContentEncryptionV1.CHUNK_BYTES,
                java.nio.ByteBuffer.wrap(encrypted, 124, 4).int)
            assertEquals(17, java.nio.ByteBuffer.wrap(encrypted, 124 + StorageContentEncryptionV1.CHUNK_BYTES + 20, 4).int)
        } finally { directory.toFile().deleteRecursively() }
    }
}
