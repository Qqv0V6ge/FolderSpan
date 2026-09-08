package com.folderspan.pro.core.network

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.collections.Map
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.firstOrNull

const val KEY_PRO_API_HEADER_OVERRIDE = "pro.api.headerOverride"

@Serializable
data class ProApiHeaderOverride(
    val host: String? = null,
    val deviceType: String? = null,
    val deviceKey: String? = null,
    val deviceName: String? = null,
    val appKey: String? = null,
)

fun encodeProApiHeaderOverride(override: ProApiHeaderOverride): String =
    proApiHeaderOverrideJson.encodeToString(ProApiHeaderOverride.serializer(), override.normalized())

fun decodeProApiHeaderOverride(raw: String?): ProApiHeaderOverride? =
    raw?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            runCatching {
                proApiHeaderOverrideJson.decodeFromString(ProApiHeaderOverride.serializer(), value).normalized()
            }.getOrNull()
        }

fun proApiHeaderOverrideFromHeaders(headers: Map<String, String>): ProApiHeaderOverride =
    ProApiHeaderOverride(
        host = headers.headerValue(HttpHeaders.Host),
        deviceType = headers.headerValue(PRO_API_DEVICE_TYPE_HEADER),
        deviceKey = headers.headerValue(PRO_API_DEVICE_KEY_HEADER),
        deviceName = headers.headerValue(PRO_API_DEVICE_NAME_HEADER),
        appKey = headers.headerValue(PRO_API_APP_KEY_HEADER),
    ).normalized()

internal fun ProApiHeaderOverride.normalized(): ProApiHeaderOverride =
    copy(
        host = host.normalizedHeaderValue(),
        deviceType = deviceType.normalizedHeaderValue(),
        deviceKey = deviceKey.normalizedHeaderValue(),
        deviceName = deviceName.normalizedHeaderValue(),
        appKey = appKey.normalizedHeaderValue(),
    )

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private fun String?.normalizedHeaderValue(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

private val proApiHeaderOverrideJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}
