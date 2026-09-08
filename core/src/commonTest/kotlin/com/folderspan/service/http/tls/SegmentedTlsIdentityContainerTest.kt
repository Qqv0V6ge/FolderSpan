package com.folderspan.service.http.tls

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SegmentedTlsIdentityContainerTest {
    @Test
    fun roundTripsIdentityPayloadFromThreeEncryptedSegments() {
        val payload = "certificate-private-key-payload".encodeToByteArray()
        val encoded = SegmentedTlsIdentityContainer.encodeIdentityPayload(
            payload = payload,
            cipher = XorTestSegmentCipher,
            random = DeterministicStorageRandom(
                nextInts = mutableListOf(1, 8, 12)
            )
        )

        val decoded = SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(encoded, XorTestSegmentCipher)

        assertNotNull(decoded)
        assertContentEquals(payload, decoded)
    }

    @Test
    fun randomizedFillerSizesChangeTotalFileSize() {
        val payload = "stable-payload".encodeToByteArray()
        val first = SegmentedTlsIdentityContainer.encodeIdentityPayload(
            payload = payload,
            cipher = XorTestSegmentCipher,
            random = DeterministicStorageRandom(
                nextInts = mutableListOf(0, 1, 2)
            )
        )
        val second = SegmentedTlsIdentityContainer.encodeIdentityPayload(
            payload = payload,
            cipher = XorTestSegmentCipher,
            random = DeterministicStorageRandom(
                nextInts = mutableListOf(2, 64, 128)
            )
        )

        assertNotEquals(first.size, second.size)
        assertContentEquals(payload, SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(first, XorTestSegmentCipher))
        assertContentEquals(payload, SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(second, XorTestSegmentCipher))
    }

    @Test
    fun corruptedContainerDoesNotReturnIdentityPayload() {
        val payload = "payload".encodeToByteArray()
        val encoded = SegmentedTlsIdentityContainer.encodeIdentityPayload(
            payload = payload,
            cipher = XorTestSegmentCipher,
            random = DeterministicStorageRandom(
                nextInts = mutableListOf(0, 4, 4)
            )
        )
        val corrupted = encoded.copyOf().also { bytes ->
            val firstSegmentEncryptedOffset = 8 + 1 + 1 + 2 + 4 + XorTestSegmentCipher.ivSize
            bytes[firstSegmentEncryptedOffset] = (bytes[firstSegmentEncryptedOffset].toInt() xor 0x7F).toByte()
        }

        assertNull(SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(corrupted, XorTestSegmentCipher))
    }
}

private object XorTestSegmentCipher : TlsIdentitySegmentCipher {
    override val ivSize: Int = 4

    override fun encrypt(plain: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        return xor(plain, iv)
    }

    override fun decrypt(encrypted: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        return xor(encrypted, iv)
    }

    private fun xor(data: ByteArray, iv: ByteArray): ByteArray {
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor iv[index % iv.size].toInt() xor 0x5A).toByte()
        }
    }
}

private class DeterministicStorageRandom(
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
