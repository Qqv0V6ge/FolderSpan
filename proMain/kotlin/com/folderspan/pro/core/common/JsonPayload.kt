package com.folderspan.pro.core.common

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json

fun JsonElement.apiDataOrSelf(): JsonElement =
    (this as? JsonObject)?.get("data") ?: this

fun JsonElement.apiMessage(): String? =
    (this as? JsonObject)?.stringValue("msg")
        ?: (this as? JsonObject)?.stringValue("message")
        ?: (apiDataOrSelf() as? JsonObject)?.stringValue("msg")
        ?: (apiDataOrSelf() as? JsonObject)?.stringValue("message")

fun JsonElement.apiCode(): Int? =
    ((this as? JsonObject)?.get("code") as? JsonPrimitive)
        ?.contentOrNull
        ?.toIntOrNull()

internal fun parseApiMessage(payload: String): String? = runCatching {
    jsonPayloadParser.parseToJsonElement(payload).apiMessage()
}.getOrNull()

private val jsonPayloadParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
