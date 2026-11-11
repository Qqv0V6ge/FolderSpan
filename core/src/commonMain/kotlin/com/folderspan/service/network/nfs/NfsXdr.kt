package com.folderspan.service.network.nfs

import okio.Buffer

internal class XdrWriter {
    private val output = Buffer()
    fun int(value: Int) { output.writeInt(value) }
    fun long(value: Long) { output.writeLong(value) }
    fun bool(value: Boolean) = int(if (value) 1 else 0)
    fun fixed(value: ByteArray) { output.write(value) }
    fun opaque(value: ByteArray) {
        int(value.size)
        fixed(value)
        repeat((4 - value.size % 4) % 4) { output.writeByte(0) }
    }
    fun string(value: String) = opaque(value.encodeToByteArray())
    fun bytes(): ByteArray = output.readByteArray()
}

internal class XdrReader(bytes: ByteArray) {
    private val input = Buffer().write(bytes)
    val remaining: Long get() = input.size
    fun int(): Int { require(remaining >= 4) { "Truncated XDR integer" }; return input.readInt() }
    fun long(): Long { require(remaining >= 8) { "Truncated XDR integer" }; return input.readLong() }
    fun bool(): Boolean = when (val value = int()) {
        0 -> false
        1 -> true
        else -> error("Invalid XDR boolean: $value")
    }
    fun fixed(size: Int): ByteArray {
        require(size >= 0 && size.toLong() <= remaining) { "Truncated XDR data" }
        return input.readByteArray(size.toLong())
    }
    fun skip(size: Int) { fixed(size) }
    fun opaque(limit: Int = 1024 * 1024): ByteArray {
        val size = int()
        require(size in 0..limit) { "XDR data exceeds its limit" }
        val result = fixed(size)
        val padding = fixed((4 - size % 4) % 4)
        require(padding.all { it == 0.toByte() }) { "Invalid XDR padding" }
        return result
    }
    fun string(limit: Int = 4096): String = opaque(limit).decodeToString(throwOnInvalidSequence = true)
    fun end() { require(remaining == 0L) { "Unexpected XDR data" } }
}

internal class NfsStatusException(val status: Int, operation: String) :
    Exception("NFS $operation failed (status $status)")
