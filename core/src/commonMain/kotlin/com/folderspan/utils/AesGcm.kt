package com.folderspan.utils

/**
 * AES-256-GCM with a 12-byte nonce and 16-byte tag.
 * Ciphertext layout used by callers is `nonce || ciphertext || tag`.
 */
internal object AesGcm {
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val BLOCK_BYTES = 16
    const val KEY_BYTES = 32

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-GCM key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        val ciphertext = ByteArray(plaintext.size)
        val tag = ByteArray(TAG_BYTES)
        crypt(key, nonce, aad, plaintext, ciphertext, tag, encrypt = true)
        return ciphertext + tag
    }

    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-GCM key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        require(ciphertextAndTag.size >= TAG_BYTES) { "AES-GCM ciphertext is too short" }
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - TAG_BYTES)
        val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - TAG_BYTES, ciphertextAndTag.size)
        val plaintext = ByteArray(ciphertext.size)
        crypt(key, nonce, aad, ciphertext, plaintext, tag, encrypt = false)
        return plaintext
    }

    private fun crypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        output: ByteArray,
        tag: ByteArray,
        encrypt: Boolean,
    ) {
        val hashKey = aesEncryptBlock(key, ByteArray(BLOCK_BYTES))
        val j0 = ByteArray(BLOCK_BYTES)
        nonce.copyInto(j0)
        j0[15] = 1
        val counter = j0.copyOf()
        increment32(counter)
        gctr(key, counter, input, output)
        val computedTag = computeTag(key, hashKey, j0, aad, if (encrypt) output else input)
        if (encrypt) {
            computedTag.copyInto(tag)
            return
        }
        require(constantTimeEquals(computedTag, tag)) { "AES-GCM authentication failed" }
    }

    private fun computeTag(
        key: ByteArray,
        hashKey: ByteArray,
        j0: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val y = ByteArray(BLOCK_BYTES)
        ghashBlocks(hashKey, y, aad)
        ghashBlocks(hashKey, y, ciphertext)
        val lengths = ByteArray(BLOCK_BYTES)
        writeUInt64(lengths, 0, aad.size.toLong() * 8)
        writeUInt64(lengths, 8, ciphertext.size.toLong() * 8)
        xorBlock(y, lengths)
        multiply(y, hashKey)
        val s = aesEncryptBlock(key, j0)
        xorBlock(y, s)
        return y
    }

    private fun ghashBlocks(hashKey: ByteArray, y: ByteArray, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            if (remaining >= BLOCK_BYTES) {
                xorBlock(y, data, offset)
            } else {
                val padded = ByteArray(BLOCK_BYTES)
                data.copyInto(padded, 0, offset, data.size)
                xorBlock(y, padded)
            }
            multiply(y, hashKey)
            offset += BLOCK_BYTES
        }
    }

    private fun gctr(key: ByteArray, counter: ByteArray, input: ByteArray, output: ByteArray) {
        var offset = 0
        while (offset < input.size) {
            val keystream = aesEncryptBlock(key, counter)
            increment32(counter)
            val remaining = minOf(BLOCK_BYTES, input.size - offset)
            for (index in 0 until remaining) {
                output[offset + index] = (input[offset + index].toInt() xor keystream[index].toInt()).toByte()
            }
            offset += remaining
        }
    }

    private fun increment32(block: ByteArray) {
        var carry = 1
        for (index in 15 downTo 12) {
            val value = (block[index].toInt() and 0xFF) + carry
            block[index] = value.toByte()
            carry = value ushr 8
            if (carry == 0) return
        }
    }

    private fun xorBlock(target: ByteArray, other: ByteArray, otherOffset: Int = 0) {
        for (index in 0 until BLOCK_BYTES) {
            target[index] = (target[index].toInt() xor other[otherOffset + index].toInt()).toByte()
        }
    }

    private fun writeUInt64(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in offset + 7 downTo offset) {
            target[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }

    /**
     * GF(2^128) multiplication used by GHASH. [x] is updated in place with x * y.
     */
    private fun multiply(x: ByteArray, y: ByteArray) {
        val z = ByteArray(BLOCK_BYTES)
        val v = y.copyOf()
        for (bit in 0 until 128) {
            if (bitIsSet(x, bit)) {
                xorBlock(z, v)
            }
            val lsbSet = (v[15].toInt() and 1) == 1
            shiftRight(v)
            if (lsbSet) {
                v[0] = (v[0].toInt() xor 0xE1).toByte()
            }
        }
        z.copyInto(x)
    }

    private fun bitIsSet(bytes: ByteArray, bit: Int): Boolean {
        val value = bytes[bit / 8].toInt() and 0xFF
        val mask = 1 shl (7 - (bit % 8))
        return (value and mask) != 0
    }

    private fun shiftRight(bytes: ByteArray) {
        var carry = 0
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            bytes[index] = ((value ushr 1) or carry).toByte()
            carry = (value and 1) shl 7
        }
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (index in left.indices) {
            diff = diff or (left[index].toInt() xor right[index].toInt())
        }
        return diff == 0
    }
}
