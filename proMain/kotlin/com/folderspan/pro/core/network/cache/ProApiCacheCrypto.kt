package com.folderspan.pro.core.network.cache

import com.folderspan.createSettings
import com.folderspan.utils.secureRandomBytes
import korlibs.crypto.AES
import kotlin.io.encoding.Base64
import kotlin.math.min

class ProApiCacheCrypto internal constructor(
    private val keyProvider: ProApiCacheKeyProvider = EmbeddedProApiCacheKeyProvider,
) {
    fun encrypt(
        plainText: ByteArray,
        namespace: String,
        key: ApiCacheKey,
    ): EncryptedApiCachePayload {
        val keyMaterial = keyProvider.keyMaterial()
        val nonce = secureRandomBytes(NONCE_SIZE_BYTES)
        val sealed = ProApiCacheAesGcm.encrypt(
            plainText = plainText,
            key = keyMaterial.encryptionKey,
            nonce = nonce,
            associatedData = associatedData(namespace, key),
        )
        return EncryptedApiCachePayload(
            algorithm = ALGORITHM,
            nonce = nonce.base64(),
            cipherText = sealed.cipherText.base64(),
            tag = sealed.tag.base64(),
        )
    }

    suspend fun decrypt(
        payload: EncryptedApiCachePayload,
        namespace: String,
        key: ApiCacheKey,
    ): ByteArray? = runCatching {
        if (payload.algorithm != ALGORITHM) return null
        val keyMaterial = keyProvider.keyMaterial()
        val nonce = payload.nonce.base64Bytes()
        val cipherText = payload.cipherText.base64Bytes()
        val tag = payload.tag.base64Bytes()
        ProApiCacheAesGcm.decrypt(
            cipherText = cipherText,
            tag = tag,
            key = keyMaterial.encryptionKey,
            nonce = nonce,
            associatedData = associatedData(namespace, key),
        )
    }.getOrNull()

    private fun associatedData(namespace: String, key: ApiCacheKey): ByteArray =
        listOf(CACHE_FORMAT_VERSION.toString(), namespace, key.storageKey)
            .joinToString("\n")
            .encodeToByteArray()

    companion object {
        const val ALGORITHM = "AES-GCM-256"
        const val CACHE_FORMAT_VERSION = 1
        private const val NONCE_SIZE_BYTES = 12
    }
}

class EncryptedApiCachePayload internal constructor(
    val algorithm: String,
    val nonce: String,
    val cipherText: String,
    val tag: String,
)

internal interface ProApiCacheKeyProvider {
    fun keyMaterial(): ProApiCacheKeyMaterial
}

internal data class ProApiCacheKeyMaterial(
    val encryptionKey: ByteArray,
) {
    init {
        require(encryptionKey.size == KEY_SIZE_BYTES) { "Encryption key must be 256 bits." }
    }

    companion object {
        const val KEY_SIZE_BYTES = 32
    }
}

internal object EmbeddedProApiCacheKeyProvider : ProApiCacheKeyProvider {
    override fun keyMaterial(): ProApiCacheKeyMaterial {
        return ProApiCacheKeyMaterial(
            encryptionKey = installationDek(),
        )
    }

    private fun installationDek(): ByteArray {
        val settings = createSettings()
        val existing = settings.getString(PRO_HTTP_CACHE_DEK_KEY, "")
        if (existing.isNotBlank()) {
            val decoded = runCatching { Base64.decode(existing) }.getOrNull()
            if (decoded != null && decoded.size == ProApiCacheKeyMaterial.KEY_SIZE_BYTES) {
                return decoded
            }
        }
        val bytes = secureRandomBytes(ProApiCacheKeyMaterial.KEY_SIZE_BYTES)
        settings.putString(PRO_HTTP_CACHE_DEK_KEY, Base64.encode(bytes))
        return bytes
    }
}

internal const val PRO_HTTP_CACHE_DEK_KEY = "pro.httpCache.dek"

internal object ProApiCacheAesGcm {
    fun encrypt(
        plainText: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): ProApiCacheAesGcmPayload {
        require(key.size == ProApiCacheKeyMaterial.KEY_SIZE_BYTES) { "AES-GCM key must be 256 bits." }
        require(nonce.size == NONCE_SIZE_BYTES) { "AES-GCM nonce must be 96 bits." }
        val cipherText = cryptCtr(plainText, key, nonce)
        return ProApiCacheAesGcmPayload(
            cipherText = cipherText,
            tag = authenticationTag(key, nonce, associatedData, cipherText),
        )
    }

    fun decrypt(
        cipherText: ByteArray,
        tag: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == ProApiCacheKeyMaterial.KEY_SIZE_BYTES) { "AES-GCM key must be 256 bits." }
        require(nonce.size == NONCE_SIZE_BYTES) { "AES-GCM nonce must be 96 bits." }
        val expectedTag = authenticationTag(key, nonce, associatedData, cipherText)
        if (!constantTimeEquals(expectedTag, tag)) {
            throw IllegalArgumentException("Invalid AES-GCM tag.")
        }
        return cryptCtr(cipherText, key, nonce)
    }

    private fun cryptCtr(input: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        val counter = initialCounter(nonce)
        var offset = 0
        while (offset < input.size) {
            incrementCounter(counter)
            val stream = encryptBlock(key, counter)
            val count = min(BLOCK_SIZE_BYTES, input.size - offset)
            for (index in 0 until count) {
                output[offset + index] = (input[offset + index].toInt() xor stream[index].toInt()).toByte()
            }
            offset += count
        }
        return output
    }

    private fun authenticationTag(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        cipherText: ByteArray,
    ): ByteArray {
        val hashSubkey = encryptBlock(key, ByteArray(BLOCK_SIZE_BYTES))
        val ghash = ghash(hashSubkey, associatedData, cipherText)
        val mask = encryptBlock(key, initialCounter(nonce))
        return ByteArray(BLOCK_SIZE_BYTES) { index -> (ghash[index].toInt() xor mask[index].toInt()).toByte() }
    }

    private fun ghash(hashSubkey: ByteArray, associatedData: ByteArray, cipherText: ByteArray): ByteArray {
        var value = ByteArray(BLOCK_SIZE_BYTES)
        associatedData.forEachBlock { block ->
            value = multiply(value.xorBlock(block), hashSubkey)
        }
        cipherText.forEachBlock { block ->
            value = multiply(value.xorBlock(block), hashSubkey)
        }
        value = multiply(
            value.xorBlock(associatedData.size.toBitLengthBlock(cipherText.size)),
            hashSubkey,
        )
        return value
    }

    private inline fun ByteArray.forEachBlock(block: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < size) {
            val item = ByteArray(BLOCK_SIZE_BYTES)
            val count = min(BLOCK_SIZE_BYTES, size - offset)
            copyInto(item, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
            block(item)
            offset += count
        }
    }

    private fun multiply(left: ByteArray, right: ByteArray): ByteArray {
        val result = ByteArray(BLOCK_SIZE_BYTES)
        val value = right.copyOf()
        for (bitIndex in 0 until BLOCK_SIZE_BITS) {
            if (left.bitAt(bitIndex) == 1) {
                xorInto(result, value)
            }
            val lsbSet = (value[BLOCK_SIZE_BYTES - 1].toInt() and 1) == 1
            shiftRightOne(value)
            if (lsbSet) {
                value[0] = (value[0].toInt() xor REDUCTION_POLYNOMIAL).toByte()
            }
        }
        return result
    }

    private fun ByteArray.bitAt(bitIndex: Int): Int =
        (this[bitIndex / 8].toInt() ushr (7 - bitIndex % 8)) and 1

    private fun shiftRightOne(bytes: ByteArray) {
        var carry = 0
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            bytes[index] = ((value ushr 1) or carry).toByte()
            carry = (value and 1) shl 7
        }
    }

    private fun xorInto(target: ByteArray, source: ByteArray) {
        for (index in target.indices) {
            target[index] = (target[index].toInt() xor source[index].toInt()).toByte()
        }
    }

    private fun ByteArray.xorBlock(other: ByteArray): ByteArray =
        ByteArray(BLOCK_SIZE_BYTES) { index -> (this[index].toInt() xor other[index].toInt()).toByte() }

    private fun Int.toBitLengthBlock(cipherTextSize: Int): ByteArray {
        val block = ByteArray(BLOCK_SIZE_BYTES)
        putLong(block, offset = 0, value = toLong() * 8L)
        putLong(block, offset = 8, value = cipherTextSize.toLong() * 8L)
        return block
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr ((7 - index) * 8)).toByte()
        }
    }

    private fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val output = block.copyOf()
        AES(key).encrypt(output, offset = 0, len = output.size)
        return output
    }

    private fun initialCounter(nonce: ByteArray): ByteArray =
        nonce + byteArrayOf(0, 0, 0, 1)

    private fun incrementCounter(counter: ByteArray) {
        for (index in counter.lastIndex downTo counter.size - 4) {
            counter[index] = (counter[index] + 1).toByte()
            if (counter[index].toInt() != 0) break
        }
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        var difference = left.size xor right.size
        val count = min(left.size, right.size)
        for (index in 0 until count) {
            difference = difference or (left[index].toInt() xor right[index].toInt())
        }
        return difference == 0
    }

    private const val NONCE_SIZE_BYTES = 12
    private const val BLOCK_SIZE_BYTES = 16
    private const val BLOCK_SIZE_BITS = BLOCK_SIZE_BYTES * 8
    private const val REDUCTION_POLYNOMIAL = 0xe1
}

internal class ProApiCacheAesGcmPayload(
    val cipherText: ByteArray,
    val tag: ByteArray,
)

private fun ByteArray.base64(): String = Base64.encode(this)

private fun String.base64Bytes(): ByteArray = Base64.decode(this)
