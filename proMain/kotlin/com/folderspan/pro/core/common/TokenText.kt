package com.folderspan.pro.core.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun String?.normalizedAccessTokenOrNull(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun normalizeAccessToken(rawToken: String?): String? =
    rawToken
        ?.trim()
        ?.removePrefix("Bearer ")
        ?.removePrefix("bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun String.accessTokenExpiresAtEpochSeconds(): Long? {
    val token = normalizeAccessToken(this) ?: return null
    val payloadPart = token.split('.').getOrNull(1) ?: return null
    val payloadText = decodeBase64Url(payloadPart) ?: return null
    val payload = runCatching {
        tokenPayloadJson.parseToJsonElement(payloadText) as? JsonObject
    }.getOrNull() ?: return null
    return (payload["exp"] as? JsonPrimitive)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
}

private fun decodeBase64Url(value: String): String? {
    if (value.isEmpty()) return null
    var buffer = 0
    var bitsInBuffer = 0
    val bytes = mutableListOf<Byte>()

    for (char in value) {
        if (char == '=') break
        val sixBits = Base64UrlAlphabet.indexOf(char)
        if (sixBits < 0) return null
        buffer = (buffer shl 6) or sixBits
        bitsInBuffer += 6
        if (bitsInBuffer >= 8) {
            bitsInBuffer -= 8
            bytes += ((buffer shr bitsInBuffer) and 0xff).toByte()
        }
    }

    return bytes.toByteArray().decodeToString()
}

private val tokenPayloadJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private const val Base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
