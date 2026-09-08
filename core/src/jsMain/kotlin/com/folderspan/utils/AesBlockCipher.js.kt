package com.folderspan.utils

import kotlin.math.min

@JsModule("crypto-js")
@JsNonModule
private external val CryptoJS: dynamic

internal actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == AesGcm.BLOCK_BYTES) { "AES block must be 16 bytes" }
    val options = js("{}")
    options.mode = CryptoJS.mode.ECB
    options.padding = CryptoJS.pad.NoPadding
    val encrypted = CryptoJS.AES.encrypt(block.toWordArray(), key.toWordArray(), options)
    return wordArrayToByteArray(encrypted.ciphertext)
}

private fun ByteArray.toWordArray(): dynamic {
    val words = js("[]")
    var index = 0
    while (index < size) {
        var word = 0
        val bytesInWord = min(4, size - index)
        for (offset in 0 until bytesInWord) {
            val byteValue = this[index + offset].toInt() and 0xFF
            word = word or (byteValue shl (24 - offset * 8))
        }
        words.push(word)
        index += 4
    }
    return CryptoJS.lib.WordArray.create(words, size)
}

private fun wordArrayToByteArray(wordArray: dynamic): ByteArray {
    val sigBytes = (wordArray.sigBytes as Number).toInt()
    val words = wordArray.words
    val result = ByteArray(sigBytes)
    var byteIndex = 0
    var wordIndex = 0
    while (byteIndex < sigBytes) {
        val word = (words[wordIndex] as Number).toInt()
        for (offset in 0 until 4) {
            if (byteIndex >= sigBytes) break
            val shift = 24 - offset * 8
            result[byteIndex++] = ((word ushr shift) and 0xFF).toByte()
        }
        wordIndex++
    }
    return result
}
