package com.folderspan.service.http

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FILE_SHARE_ACCESS_KEY_HEADER = "X-FolderSpan-Key"
const val FILE_SHARE_ACCESS_KEY_MAX_LENGTH = 256

@Serializable
data class FileShareAccessKeyConfig(
    val enabled: Boolean = false,
    val value: String = "",
) {
    fun normalized(): FileShareAccessKeyConfig = copy(value = value.trim())

    fun hasValidValue(): Boolean = isValidFileShareAccessKeyValue(value)
}

private val fileShareAccessKeyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun isValidFileShareAccessKeyValue(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length in 1..FILE_SHARE_ACCESS_KEY_MAX_LENGTH &&
        normalized.all { character -> character.code in 0x20..0x7E }
}

fun Settings.readFileShareAccessKeyConfig(): FileShareAccessKeyConfig {
    val raw = getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "")
    if (raw.isBlank()) return FileShareAccessKeyConfig()
    return runCatching {
        fileShareAccessKeyJson.decodeFromString<FileShareAccessKeyConfig>(raw).normalized()
    }.getOrDefault(FileShareAccessKeyConfig())
}

fun Settings.writeFileShareAccessKeyConfig(config: FileShareAccessKeyConfig) {
    putString(
        SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY,
        fileShareAccessKeyJson.encodeToString(config.normalized()),
    )
}

internal fun constantTimeFileShareAccessKeyEquals(expected: String, actual: String?): Boolean {
    val expectedBytes = expected.encodeToByteArray()
    val actualBytes = actual.orEmpty().encodeToByteArray()
    var difference = expectedBytes.size xor actualBytes.size
    val length = maxOf(expectedBytes.size, actualBytes.size)
    for (index in 0 until length) {
        val expectedByte = expectedBytes.getOrElse(index) { 0 }
        val actualByte = actualBytes.getOrElse(index) { 0 }
        difference = difference or (expectedByte.toInt() xor actualByte.toInt())
    }
    return difference == 0
}
