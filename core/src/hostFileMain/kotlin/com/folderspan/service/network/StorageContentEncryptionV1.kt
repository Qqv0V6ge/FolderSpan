package com.folderspan.service.network

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Format primitives only; writes are enabled by the caller after recovery-backup verification. */
internal object StorageContentEncryptionV1 {
    const val CHUNK_BYTES = 1024 * 1024
    private val magic = "FSPANENC".encodeToByteArray()
    private val backupMagic = "FSPANKEY".encodeToByteArray()
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val ITERATIONS = 600_000

    fun newKey(): ByteArray = StorageContentCrypto.randomBytes(KEY_BYTES)

    fun encrypt(
        source: BufferedSource,
        destination: BufferedSink,
        locationKey: ByteArray,
        scope: String,
        checkCancelled: () -> Unit = {},
    ) {
        checkKey(locationKey)
        val fileKey = newKey()
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            val prefix = Buffer().write(magic).writeInt(VERSION).writeInt(CHUNK_BYTES)
                .write(StorageContentCrypto.randomBytes(16)).write(scopeDigest(scope)).readByteArray()
            val wrapNonce = StorageContentCrypto.randomBytes(12)
            val header = prefix + wrapNonce + crypt(true, locationKey, wrapNonce, prefix, fileKey)
            destination.write(header)
            var total = 0L
            var index = 0L
            while (true) {
                checkCancelled()
                val count = source.readStorageContentBlock(buffer, checkCancelled)
                if (count == 0) break
                check(total <= Long.MAX_VALUE - count) { "Encrypted file is too large" }
                val plain = buffer.copyOf(count)
                try {
                    destination.writeInt(count)
                    destination.write(crypt(true, fileKey, nonce(index), frameAad(header, index, count, total), plain))
                } finally { plain.fill(0) }
                total += count
                index++
            }
            checkCancelled()
            destination.writeInt(0).writeLong(total).writeLong(index)
                .write(crypt(true, fileKey, nonce(index), frameAad(header, index, 0, total), byteArrayOf()))
            destination.flush()
        } finally { fileKey.fill(0); buffer.fill(0) }
    }

    /** Publish only after every frame, final length, and EOF have been authenticated. */
    fun decryptAndPublish(
        source: BufferedSource,
        destination: Path,
        locationKey: ByteArray,
        scope: String,
        checkCancelled: () -> Unit = {},
    ) {
        checkKey(locationKey)
        val absolute = FileSystem.SYSTEM.canonicalize(destination.parent ?: ".".toPath()) / destination.name
        val temporary = StorageContentFileProtection.createPrivateTempFile(checkNotNull(absolute.parent), ".folderspan-decrypt-")
        try {
            FileSystem.SYSTEM.sink(temporary).buffer().use { output ->
                decrypt(source, output, locationKey, scope, checkCancelled)
            }
            checkCancelled()
            StorageContentFileProtection.atomicMove(temporary, absolute)
        } finally { FileSystem.SYSTEM.delete(temporary, mustExist = false) }
    }

    private fun decrypt(
        input: BufferedSource,
        destination: BufferedSink,
        locationKey: ByteArray,
        scope: String,
        checkCancelled: () -> Unit,
    ) {
        val prefix = input.readByteArray(64)
        val parsed = Buffer().write(prefix)
        require(parsed.readByteArray(8).contentEquals(magic)) { "Unknown encrypted file format" }
        require(parsed.readInt() == VERSION) { "Unsupported encrypted file version" }
        require(parsed.readInt() == CHUNK_BYTES) { "Unsupported encrypted chunk size" }
        parsed.skip(16)
        require(storageContentConstantTimeEquals(parsed.readByteArray(32), scopeDigest(scope))) { "Encrypted location does not match" }
        val wrapNonce = input.readByteArray(12)
        val wrappedKey = input.readByteArray(48)
        val header = prefix + wrapNonce + wrappedKey
        val fileKey = crypt(false, locationKey, wrapNonce, prefix, wrappedKey)
        try {
            checkKey(fileKey)
            var total = 0L
            var index = 0L
            var shortFrame = false
            while (true) {
                checkCancelled()
                val count = input.readInt()
                require(count in 0..CHUNK_BYTES) { "Invalid encrypted frame length" }
                if (count == 0) {
                    require(input.readLong() == total && input.readLong() == index) { "Encrypted length mismatch" }
                    crypt(false, fileKey, nonce(index), frameAad(header, index, 0, total), input.readByteArray(16)).fill(0)
                    require(input.exhausted()) { "Unexpected encrypted trailing data" }
                    return
                }
                require(!shortFrame && total <= Long.MAX_VALUE - count) { "Invalid encrypted frame sequence" }
                val plain = crypt(false, fileKey, nonce(index), frameAad(header, index, count, total),
                    input.readByteArray((count + 16).toLong()))
                try { destination.write(plain) } finally { plain.fill(0) }
                shortFrame = count < CHUNK_BYTES
                total += count
                index++
            }
        } finally { fileKey.fill(0) }
    }

    fun exportRecovery(locationKey: ByteArray, scope: String, password: CharArray): ByteArray {
        checkKey(locationKey)
        require(password.size in 12..1024) { "Recovery password must contain 12 to 1024 characters" }
        val salt = StorageContentCrypto.randomBytes(32)
        val prefix = Buffer().write(backupMagic).writeInt(VERSION).writeInt(ITERATIONS)
            .write(salt).write(scopeDigest(scope)).readByteArray()
        val nonce = StorageContentCrypto.randomBytes(12)
        val key = StorageContentCrypto.pbkdf2Sha256(password, salt, ITERATIONS)
        return try { prefix + nonce + crypt(true, key, nonce, prefix, locationKey) } finally { key.fill(0) }
    }

    fun importRecovery(backup: ByteArray, scope: String, password: CharArray): ByteArray {
        require(backup.size == 140 && password.size in 12..1024) { "Invalid recovery material" }
        val parsed = Buffer().write(backup)
        require(parsed.readByteArray(8).contentEquals(backupMagic) && parsed.readInt() == VERSION) { "Unsupported recovery format" }
        require(parsed.readInt() == ITERATIONS) { "Unsupported recovery derivation" }
        val salt = parsed.readByteArray(32)
        require(storageContentConstantTimeEquals(parsed.readByteArray(32), scopeDigest(scope))) { "Recovery location does not match" }
        val nonce = parsed.readByteArray(12)
        val ciphertext = parsed.readByteArray(48)
        val key = StorageContentCrypto.pbkdf2Sha256(password, salt, ITERATIONS)
        return try { crypt(false, key, nonce, backup.copyOf(80), ciphertext).also(::checkKey) }
        finally { key.fill(0) }
    }

    private fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        StorageContentCrypto.aesGcm(encrypt, key, nonce, aad, data)

    private fun scopeDigest(scope: String): ByteArray {
        val encoded = scope.encodeToByteArray()
        require(encoded.size in 1..4096) { "Invalid encrypted location" }
        return StorageContentCrypto.sha256(encoded)
    }

    private fun nonce(index: Long): ByteArray {
        require(index >= 0) { "Encrypted file is too large" }
        return Buffer().writeInt(0).writeLong(index).readByteArray()
    }

    private fun frameAad(header: ByteArray, index: Long, count: Int, total: Long): ByteArray =
        Buffer().write(header).writeLong(index).writeInt(count).writeLong(total).readByteArray()

    private fun checkKey(key: ByteArray) = require(key.size == KEY_BYTES) { "Invalid content key" }
}

internal fun BufferedSource.readStorageContentBlock(buffer: ByteArray, checkCancelled: () -> Unit = {}): Int {
    var total = 0
    while (total < buffer.size) {
        checkCancelled()
        val count = read(buffer, total, buffer.size - total)
        if (count < 0) break
        if (count == 0) {
            if (exhausted()) break
            buffer[total++] = readByte()
        } else total += count
    }
    return total
}
