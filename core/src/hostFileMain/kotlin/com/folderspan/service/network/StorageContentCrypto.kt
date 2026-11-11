package com.folderspan.service.network

internal expect object StorageContentCrypto {
    fun randomBytes(count: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int): ByteArray
    fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray
}

internal fun storageContentConstantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var difference = 0
    for (index in first.indices) difference = difference or (first[index].toInt() xor second[index].toInt())
    return difference == 0
}

internal class StorageContentAuthenticationException : Exception("Content authentication failed")
