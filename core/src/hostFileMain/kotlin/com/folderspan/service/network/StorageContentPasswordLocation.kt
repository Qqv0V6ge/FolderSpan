package com.folderspan.service.network

import okio.Buffer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Password envelope for a portable remote location; independent of local connection/account identifiers. */
@OptIn(ExperimentalUuidApi::class)
internal object StorageContentPasswordLocation {
    private val magic = "FSPANVLT".encodeToByteArray()
    private val domain = "FolderSpan password location v1\u0000".encodeToByteArray()
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val PREFIX_BYTES = 76
    const val ENCODED_BYTES = PREFIX_BYTES + 48

    internal class Unlocked(val vaultId: String, val key: ByteArray) {
        override fun toString() = "UnlockedContentLocation()"
    }

    fun create(vaultId: String, locationKey: ByteArray, password: CharArray): ByteArray {
        require(locationKey.size == 32) { "Invalid content key" }
        require(password.size in 12..1024) { "Content password must contain 12 to 1024 characters" }
        val salt = StorageContentCrypto.randomBytes(32)
        val nonce = StorageContentCrypto.randomBytes(12)
        val prefix = Buffer().write(magic).writeInt(VERSION).write(Uuid.parse(vaultId).toByteArray())
            .writeInt(ITERATIONS).write(salt).write(nonce).readByteArray()
        val wrappingKey = StorageContentCrypto.pbkdf2Sha256(password, salt, ITERATIONS)
        return try {
            prefix + StorageContentCrypto.aesGcm(true, wrappingKey, nonce, domain + prefix, locationKey)
        } finally { wrappingKey.fill(0) }
    }

    /** This identity is untrusted until unlock authenticates the complete envelope. */
    fun vaultId(encoded: ByteArray): String = parse(encoded).vaultId

    fun unlock(encoded: ByteArray, password: CharArray): Unlocked {
        val parsed = parse(encoded)
        require(password.size <= 1024) { "Content password is too long" }
        val wrappingKey = StorageContentCrypto.pbkdf2Sha256(password, parsed.salt, ITERATIONS)
        return try {
            Unlocked(parsed.vaultId, StorageContentCrypto.aesGcm(false, wrappingKey, parsed.nonce,
                domain + encoded.copyOf(PREFIX_BYTES), parsed.ciphertext))
        } catch (_: StorageContentAuthenticationException) {
            throw StorageContentException(StorageContentErrorCode.UnlockFailed,
                "Unable to unlock; check the content password or location material")
        } finally { wrappingKey.fill(0) }
    }

    private class Parsed(val vaultId: String, val salt: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

    private fun parse(encoded: ByteArray): Parsed {
        if (encoded.size != ENCODED_BYTES) throw StorageContentException(
            StorageContentErrorCode.DamagedLocation, "Invalid password location length")
        val input = Buffer().write(encoded)
        if (!input.readByteArray(8).contentEquals(magic) || input.readInt() != VERSION) throw StorageContentException(
            StorageContentErrorCode.UnsupportedFormat, "Unsupported password location format")
        val vaultId = Uuid.fromByteArray(input.readByteArray(16)).toString()
        if (input.readInt() != ITERATIONS) throw StorageContentException(
            StorageContentErrorCode.UnsupportedFormat, "Unsupported password location derivation")
        return Parsed(vaultId, input.readByteArray(32), input.readByteArray(12), input.readByteArray(48))
    }
}
