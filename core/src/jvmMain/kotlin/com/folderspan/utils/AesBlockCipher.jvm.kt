package com.folderspan.utils

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == AesGcm.BLOCK_BYTES) { "AES block must be 16 bytes" }
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(block)
}
