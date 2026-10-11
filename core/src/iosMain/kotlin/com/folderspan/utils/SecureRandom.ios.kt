package com.folderspan.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative" }
    val bytes = ByteArray(size)
    if (bytes.isEmpty()) return bytes
    bytes.usePinned { pinned ->
        arc4random_buf(pinned.addressOf(0), bytes.size.convert())
    }
    return bytes
}
