package com.folderspan.pro.data.mapper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun JsonObject.arrayValue(name: String): JsonArray? =
    get(name) as? JsonArray

fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun JsonObject.numberString(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun JsonObject.intValue(name: String): Int? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toIntOrNull()

fun JsonObject.longValue(name: String): Long? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toLongOrNull()
