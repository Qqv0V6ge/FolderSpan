package com.folderspan.service.http.server.linkshare

import korlibs.crypto.SecureRandom
import korlibs.crypto.sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinkSharePasswordVerifier(
    private val iterations: Int = DEFAULT_ITERATIONS,
    salt: ByteArray = randomSalt(),
) {
    init {
        require(iterations > 0) { "PBKDF2 iterations must be positive" }
        require(salt.size >= MIN_SALT_SIZE) { "PBKDF2 salt is too short" }
    }

    private val salt = salt.copyOf()
    private val mutex = Mutex()
    private var cachedPassword: String? = null
    private var cachedHash: ByteArray = ByteArray(0)

    suspend fun matches(expected: String, actual: String): Boolean {
        if (expected.isEmpty()) return false
        val expectedHash = expectedHash(expected)
        val actualHash = pbkdf2HmacSha256(
            password = actual.encodeToByteArray(),
            salt = salt,
            iterations = iterations,
            derivedKeySize = DERIVED_KEY_SIZE,
        )
        return constantTimeEquals(expectedHash, actualHash)
    }

    private suspend fun expectedHash(expected: String): ByteArray = mutex.withLock {
        val cached = cachedPassword
        if (cached != null && cached == expected && cachedHash.isNotEmpty()) {
            return@withLock cachedHash.copyOf()
        }
        val hash = pbkdf2HmacSha256(
            password = expected.encodeToByteArray(),
            salt = salt,
            iterations = iterations,
            derivedKeySize = DERIVED_KEY_SIZE,
        )
        cachedPassword = expected
        cachedHash = hash
        hash.copyOf()
    }

    companion object {
        const val DEFAULT_ITERATIONS = 32_768
        private const val MIN_SALT_SIZE = 16
        private const val DERIVED_KEY_SIZE = 32

        internal fun randomSalt(size: Int = MIN_SALT_SIZE): ByteArray {
            return ByteArray(size).also(SecureRandom::nextBytes)
        }
    }
}

internal fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    derivedKeySize: Int,
): ByteArray {
    require(iterations > 0)
    require(derivedKeySize > 0)
    val blockCount = (derivedKeySize + HMAC_SHA256_SIZE - 1) / HMAC_SHA256_SIZE
    val derived = ByteArray(blockCount * HMAC_SHA256_SIZE)
    val blockSalt = ByteArray(salt.size + 4)
    salt.copyInto(blockSalt)
    var offset = 0
    for (blockIndex in 1..blockCount) {
        blockSalt[salt.size] = (blockIndex ushr 24).toByte()
        blockSalt[salt.size + 1] = (blockIndex ushr 16).toByte()
        blockSalt[salt.size + 2] = (blockIndex ushr 8).toByte()
        blockSalt[salt.size + 3] = blockIndex.toByte()
        var block = hmacSha256(password, blockSalt)
        val xorBlock = block.copyOf()
        repeat(iterations - 1) {
            block = hmacSha256(password, block)
            for (index in xorBlock.indices) {
                xorBlock[index] = (xorBlock[index].toInt() xor block[index].toInt()).toByte()
            }
        }
        xorBlock.copyInto(derived, offset)
        offset += HMAC_SHA256_SIZE
    }
    return derived.copyOf(derivedKeySize)
}

internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    var difference = left.size xor right.size
    val length = maxOf(left.size, right.size)
    for (index in 0 until length) {
        val leftByte = left.getOrElse(index) { 0 }
        val rightByte = right.getOrElse(index) { 0 }
        difference = difference or (leftByte.toInt() xor rightByte.toInt())
    }
    return difference == 0
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val normalizedKey = if (key.size > HMAC_SHA256_BLOCK_SIZE) key.sha256().bytes else key
    val keyBlock = ByteArray(HMAC_SHA256_BLOCK_SIZE)
    normalizedKey.copyInto(keyBlock)
    val innerPad = ByteArray(HMAC_SHA256_BLOCK_SIZE)
    val outerPad = ByteArray(HMAC_SHA256_BLOCK_SIZE)
    for (index in 0 until HMAC_SHA256_BLOCK_SIZE) {
        val keyByte = keyBlock[index].toInt()
        innerPad[index] = (keyByte xor 0x36).toByte()
        outerPad[index] = (keyByte xor 0x5c).toByte()
    }
    return (outerPad + (innerPad + data).sha256().bytes).sha256().bytes
}

private const val HMAC_SHA256_BLOCK_SIZE = 64
private const val HMAC_SHA256_SIZE = 32
