package com.folderspan.service.network

internal const val NEXTCLOUD_METADATA_BATCH_MAX_FILES = 32
private const val NEXTCLOUD_METADATA_MAX_BYTES = 64 * 1024 + 48
private const val TAR_BLOCK_BYTES = 512
private val metadataMemberName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(commit|root|location)")

internal fun maxNextcloudMetadataTarBytes(expectedSizes: Map<String, Long>): Long {
    require(expectedSizes.size in 1..NEXTCLOUD_METADATA_BATCH_MAX_FILES) { "Invalid metadata batch size" }
    require(expectedSizes.all { (name, size) ->
        metadataMemberName.matches(name) && size in 0..NEXTCLOUD_METADATA_MAX_BYTES.toLong()
    }) { "Invalid metadata batch member" }
    return 2L * TAR_BLOCK_BYTES + expectedSizes.values.sumOf {
        TAR_BLOCK_BYTES + ((it + TAR_BLOCK_BYTES - 1) / TAR_BLOCK_BYTES) * TAR_BLOCK_BYTES
    }
}

/** Accepts only the bounded, uncompressed USTAR file subset returned by Nextcloud DAV. */
internal fun parseNextcloudMetadataTar(
    bytes: ByteArray,
    expectedSizes: Map<String, Long>,
): Map<String, ByteArray> {
    require(bytes.size.toLong() <= maxNextcloudMetadataTarBytes(expectedSizes)) { "Metadata archive exceeds its limit" }
    val result = linkedMapOf<String, ByteArray>()
    var offset = 0
    while (true) {
        require(bytes.size - offset >= TAR_BLOCK_BYTES) { "Truncated metadata archive header" }
        if ((offset until offset + TAR_BLOCK_BYTES).all { bytes[it] == 0.toByte() }) {
            require(bytes.size - offset == 2 * TAR_BLOCK_BYTES &&
                (offset until bytes.size).all { bytes[it] == 0.toByte() }) { "Invalid metadata archive ending" }
            require(result.keys == expectedSizes.keys) { "Metadata archive has missing members" }
            return result
        }
        val header = offset
        val checksum = tarOctal(bytes, header + 148, 8)
        val actualChecksum = (0 until TAR_BLOCK_BYTES).sumOf {
            if (it in 148..155) 32L else (bytes[header + it].toInt() and 0xff).toLong()
        }
        require(checksum == actualChecksum) { "Metadata archive header checksum changed" }
        require(bytes[header + 156] == '0'.code.toByte() || bytes[header + 156] == 0.toByte()) {
            "Metadata archive member is not a regular file"
        }
        require(tarText(bytes, header + 257, 6) == "ustar" &&
            bytes[header + 263] == '0'.code.toByte() && bytes[header + 264] == '0'.code.toByte()) {
            "Unsupported metadata archive format"
        }
        require(tarText(bytes, header + 157, 100).isEmpty()) { "Metadata archive contains a linked path" }
        val prefix = tarText(bytes, header + 345, 155)
        val entryName = tarText(bytes, header, 100)
        // Nextcloud emits prefix "/" plus a bare name, yielding exactly two leading slashes.
        val name = (if (prefix.isEmpty()) entryName else "$prefix/$entryName").removePrefix("//")
        require(metadataMemberName.matches(name) && name in expectedSizes && name !in result) {
            "Metadata archive contains an unexpected or duplicate member"
        }
        val size = tarOctal(bytes, header + 124, 12)
        require(size == expectedSizes.getValue(name)) { "Metadata archive member size changed" }
        val start = header + TAR_BLOCK_BYTES
        val end = start + size.toInt()
        val next = start + ((size.toInt() + TAR_BLOCK_BYTES - 1) / TAR_BLOCK_BYTES) * TAR_BLOCK_BYTES
        require(next <= bytes.size && (end until next).all { bytes[it] == 0.toByte() }) {
            "Truncated metadata archive member or invalid padding"
        }
        result[name] = bytes.copyOfRange(start, end)
        offset = next
    }
}

private fun tarText(bytes: ByteArray, offset: Int, length: Int): String {
    val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: (offset + length)
    require((offset until end).all { bytes[it].toInt() in 1..127 } &&
        (end until offset + length).all { bytes[it] == 0.toByte() }) { "Invalid metadata archive text field" }
    return bytes.decodeToString(offset, end)
}

private fun tarOctal(bytes: ByteArray, offset: Int, length: Int): Long {
    val text = bytes.decodeToString(offset, offset + length).trim('\u0000', ' ')
    require(text.isNotEmpty() && text.all { it in '0'..'7' }) { "Invalid metadata archive number" }
    return text.toLong(8)
}
