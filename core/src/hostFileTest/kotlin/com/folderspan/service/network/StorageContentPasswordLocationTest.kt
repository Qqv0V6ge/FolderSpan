package com.folderspan.service.network

import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class StorageContentPasswordLocationTest {
    private val vaultId = "00112233-4455-6677-8899-aabbccddeeff"
    private val key = ByteArray(32) { it.toByte() }
    private val password = " 密码 café 🔐 pass ".toCharArray()

    @Test
    fun independentlyGeneratedUtf8VectorPreservesSpacesAndUnicode() {
        val encoded = VECTOR.decodeHex().toByteArray()
        val unlocked = StorageContentPasswordLocation.unlock(encoded, password)
        try {
            assertEquals(vaultId, unlocked.vaultId)
            assertContentEquals(key, unlocked.key)
            assertEquals(vaultId, StorageContentPasswordLocation.vaultId(encoded))
            assertEquals("UnlockedContentLocation()", unlocked.toString())
        } finally { unlocked.key.fill(0) }
        assertFails { StorageContentPasswordLocation.unlock(encoded, password.concatToString().trim().toCharArray()) }
        assertFails { StorageContentPasswordLocation.unlock(encoded, " 密码 café 🔐 pass ".toCharArray()) }
    }

    @Test
    fun randomEnvelopeRoundTripsAndWrongPasswordCannotChangeItsBytes() {
        val encoded = StorageContentPasswordLocation.create(vaultId, key, password)
        val original = encoded.copyOf()
        val unlocked = StorageContentPasswordLocation.unlock(encoded, password)
        try { assertContentEquals(key, unlocked.key) } finally { unlocked.key.fill(0) }
        assertFalse(encoded.contentEquals(StorageContentPasswordLocation.create(vaultId, key, password)))
        assertFails { StorageContentPasswordLocation.unlock(encoded, "wrong password".toCharArray()) }
        assertContentEquals(original, encoded)
        assertContentEquals(ByteArray(32) { it.toByte() }, key)
        assertEquals(" 密码 café 🔐 pass ", password.concatToString())
    }

    @Test
    fun openingAnExistingLocationDoesNotApplyNewPasswordLengthPolicy() {
        val unlocked = StorageContentPasswordLocation.unlock(SHORT_VECTOR.decodeHex().toByteArray(), "short".toCharArray())
        try { assertContentEquals(key, unlocked.key) } finally { unlocked.key.fill(0) }
        for (invalid in listOf("", "short", "x".repeat(1025))) {
            assertFails { StorageContentPasswordLocation.create(vaultId, key, invalid.toCharArray()) }
        }
    }

    @Test
    fun invalidVersionLengthAndDerivationAreRejectedBeforePasswordWork() {
        val encoded = VECTOR.decodeHex().toByteArray()
        val invalid = listOf(
            byteArrayOf(), encoded.copyOf(encoded.size - 1), encoded + byteArrayOf(0),
            encoded.copyOf().also { it[11] = 2 },
            encoded.copyOf().also { it[28] = 0x7f },
            encoded.copyOf().also { it.fill(0, 28, 32) },
        )
        invalid.forEach { bytes ->
            assertFails { StorageContentPasswordLocation.vaultId(bytes) }
            assertFails { StorageContentPasswordLocation.unlock(bytes, password) }
        }
    }

    @Test
    fun everyEnvelopeFieldIsAuthenticatedAndInputIsNeverModified() {
        val encoded = VECTOR.decodeHex().toByteArray()
        for (offset in listOf(0, 12, 32, 64, 76, encoded.lastIndex)) {
            val tampered = encoded.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val original = tampered.copyOf()
            assertFails { StorageContentPasswordLocation.unlock(tampered, password) }
            assertContentEquals(original, tampered)
        }
    }

    companion object {
        // Independent Node crypto PBKDF2(UTF-8)/AES-256-GCM vectors; salt 20..3f, nonce 40..4b, key 00..1f.
        private const val PREFIX = "465350414e564c540000000100112233445566778899aabbccddeeff000927c0202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b"
        private const val VECTOR = PREFIX + "af356b12c397c547a226aa368230f33c6f56444de33b8beb81cb20ad2ce35a270e84fd0d1eb45f1b51cbeadd9ea4f972"
        private const val SHORT_VECTOR = PREFIX + "7afa591231c81ca8c56db45d7c333d55450e25690c0cb55123313aef0a55e9311312bc6d5e236867c21209df7a1ba7bc"
    }
}
