package com.folderspan.service.webrtc.signaling

import kotlin.io.encoding.Base64
import kotlin.random.Random

fun generateRoomId(): String {
    val bytes = ByteArray(32)
    for (i in bytes.indices) {
        bytes[i] = Random.nextInt(0, 256).toByte()
    }
    return Base64.UrlSafe.encode(bytes).trimEnd('=')
}

fun isValidRoomId(roomId: String): Boolean {
    val cleaned = roomId.trim()
    if (cleaned.isEmpty()) return false
    if (!cleaned.all { it.isBase64UrlChar() || it == '=' }) return false
    val normalized = if (cleaned.length % 4 == 0) {
        cleaned
    } else {
        cleaned.padEnd(((cleaned.length + 3) / 4) * 4, '=')
    }
    return runCatching { Base64.UrlSafe.decode(normalized).size == 32 }.getOrDefault(false)
}

private fun Char.isBase64UrlChar(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'
}
