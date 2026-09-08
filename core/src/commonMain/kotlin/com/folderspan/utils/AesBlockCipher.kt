package com.folderspan.utils

/** AES-256 encrypt of a single 16-byte block. */
internal expect fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray
