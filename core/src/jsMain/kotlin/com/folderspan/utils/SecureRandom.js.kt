package com.folderspan.utils

import org.khronos.webgl.Uint8Array

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative" }
    val bytes = ByteArray(size)
    if (bytes.isEmpty()) return bytes

    val hasGlobal = js("typeof globalThis !== 'undefined'") as Boolean
    val crypto: dynamic = if (hasGlobal) js("globalThis.crypto") else null
    if (crypto?.getRandomValues == null) {
        throw IllegalStateException("Secure random source is unavailable")
    }

    val uintArray = Uint8Array(size)
    crypto.getRandomValues(uintArray)
    val uintArrayDynamic = uintArray.asDynamic()
    for (index in bytes.indices) {
        bytes[index] = (uintArrayDynamic[index] as Number).toInt().toByte()
    }
    return bytes
}
