package com.folderspan.utils

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative" }
    return ByteArray(size).also { bytes -> secureRandom.nextBytes(bytes) }
}
