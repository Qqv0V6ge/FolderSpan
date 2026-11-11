package com.folderspan.service.network

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import okio.source

internal fun StorageContentEncryptionV1.encrypt(
    source: InputStream,
    destination: OutputStream,
    locationKey: ByteArray,
    scope: String,
    checkCancelled: () -> Unit = {},
) = encrypt(object : FilterInputStream(source) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count != 0 || length == 0) return count
        val next = super.read()
        if (next < 0) return -1
        buffer[offset] = next.toByte()
        return 1
    }
}.source().buffer(), destination.sink().buffer(), locationKey, scope, checkCancelled)

internal fun StorageContentEncryptionV1.decryptAndPublish(
    source: InputStream,
    destination: Path,
    locationKey: ByteArray,
    scope: String,
    checkCancelled: () -> Unit = {},
) = decryptAndPublish(source.source().buffer(), destination.toOkioPath(), locationKey, scope, checkCancelled)

internal fun InputStream.readStorageContentBlock(buffer: ByteArray, checkCancelled: () -> Unit = {}): Int {
    var total = 0
    while (total < buffer.size) {
        checkCancelled()
        val count = read(buffer, total, buffer.size - total)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            buffer[total++] = next.toByte()
        } else total += count
    }
    return total
}
