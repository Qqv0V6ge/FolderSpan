package com.folderspan.service.http.tls

internal interface TlsIdentitySegmentCipher {
    val ivSize: Int
    fun encrypt(plain: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray
}

internal interface TlsIdentityStorageRandom {
    fun nextInt(until: Int): Int
    fun nextBytes(size: Int): ByteArray
}

internal object SegmentedTlsIdentityContainer {
    private val magic = byteArrayOf(0x46, 0x53, 0x54, 0x4C, 0x53, 0x49, 0x44, 0x31)
    private const val VERSION = 2
    private const val SEGMENT_COUNT = 3
    private const val IDENTITY_SEGMENT = 0x49.toByte()
    private const val FILLER_SEGMENT = 0x52.toByte()
    private const val MIN_FILLER_BYTES = 128
    private const val FILLER_VARIANCE_BYTES = 512

    fun encodeIdentityPayload(
        payload: ByteArray,
        cipher: TlsIdentitySegmentCipher,
        random: TlsIdentityStorageRandom
    ): ByteArray {
        require(payload.isNotEmpty()) { "TLS identity payload cannot be empty" }
        require(cipher.ivSize > 0) { "Segment IV size must be positive" }

        val identityIndex = random.nextInt(SEGMENT_COUNT)
        val segments = List(SEGMENT_COUNT) { index ->
            val plain = if (index == identityIndex) {
                byteArrayOf(IDENTITY_SEGMENT) + payload
            } else {
                val fillerSize = MIN_FILLER_BYTES + random.nextInt(FILLER_VARIANCE_BYTES)
                byteArrayOf(FILLER_SEGMENT) + random.nextBytes(fillerSize)
            }
            val iv = random.nextBytes(cipher.ivSize)
            val encrypted = cipher.encrypt(plain, iv, segmentAssociatedData(index))
            SegmentEnvelope(iv = iv, encrypted = encrypted)
        }

        return ByteArrayWriter().apply {
            writeBytes(magic)
            writeByte(VERSION)
            writeByte(SEGMENT_COUNT)
            segments.forEach { segment ->
                writeShort(segment.iv.size)
                writeInt(segment.encrypted.size)
                writeBytes(segment.iv)
                writeBytes(segment.encrypted)
            }
        }.toByteArray()
    }

    fun decodeIdentityPayloadOrNull(
        data: ByteArray,
        cipher: TlsIdentitySegmentCipher,
    ): ByteArray? {
        return runCatching {
            val reader = ByteArrayReader(data)
            if (!reader.readBytes(magic.size).contentEquals(magic)) return null
            if (reader.readByteAsInt() != VERSION) return null
            if (reader.readByteAsInt() != SEGMENT_COUNT) return null

            val identities = mutableListOf<ByteArray>()
            repeat(SEGMENT_COUNT) { index ->
                val ivSize = reader.readUnsignedShort()
                val encryptedSize = reader.readInt()
                if (ivSize <= 0 || encryptedSize <= 0) return null
                val iv = reader.readBytes(ivSize)
                val encrypted = reader.readBytes(encryptedSize)
                val plain = cipher.decrypt(encrypted, iv, segmentAssociatedData(index))
                if (plain.isEmpty()) return null
                when (plain.first()) {
                    IDENTITY_SEGMENT -> identities += plain.copyOfRange(1, plain.size)
                    FILLER_SEGMENT -> Unit
                    else -> return null
                }
            }
            if (!reader.isAtEnd()) return null
            identities.singleOrNull()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun segmentAssociatedData(index: Int): ByteArray {
        return magic + byteArrayOf(VERSION.toByte(), SEGMENT_COUNT.toByte(), index.toByte())
    }

    private data class SegmentEnvelope(
        val iv: ByteArray,
        val encrypted: ByteArray
    )
}

private class ByteArrayWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeByte(value: Int) {
        bytes += (value and 0xFF).toByte()
    }

    fun writeShort(value: Int) {
        require(value in 0..0xFFFF) { "Value out of unsigned short range" }
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun writeInt(value: Int) {
        require(value >= 0) { "Value must be non-negative" }
        writeByte(value ushr 24)
        writeByte(value ushr 16)
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun writeBytes(value: ByteArray) {
        value.forEach { bytes += it }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private class ByteArrayReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readByteAsInt(): Int {
        ensureAvailable(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int {
        val high = readByteAsInt()
        val low = readByteAsInt()
        return (high shl 8) or low
    }

    fun readInt(): Int {
        val b1 = readByteAsInt()
        val b2 = readByteAsInt()
        val b3 = readByteAsInt()
        val b4 = readByteAsInt()
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    fun readBytes(size: Int): ByteArray {
        ensureAvailable(size)
        return bytes.copyOfRange(offset, offset + size).also {
            offset += size
        }
    }

    fun isAtEnd(): Boolean = offset == bytes.size

    private fun ensureAvailable(size: Int) {
        require(size >= 0) { "Size must be non-negative" }
        require(offset + size <= bytes.size) { "Unexpected end of data" }
    }
}
