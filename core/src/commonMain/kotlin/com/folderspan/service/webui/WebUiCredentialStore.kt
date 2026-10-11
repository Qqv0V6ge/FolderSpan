package com.folderspan.service.webui

import com.folderspan.service.mcp.auth.constantTimeTokenHashEquals
import com.folderspan.service.mcp.auth.hashMcpTokenSecret
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import korlibs.crypto.SecureRandom

data class WebUiCredentialSetup(
    val created: Boolean,
    val oneTimeSecret: String?,
)

class WebUiCredentialStore(
    private val settings: Settings,
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom::nextBytes) },
) {
    fun hasPassword(): Boolean = settings.getStringOrNull(SettingsUtils.KEY_WEB_UI_PASSWORD_HASH).orEmpty().isNotBlank()

    fun ensureInitialized(): WebUiCredentialSetup {
        if (hasPassword()) {
            return WebUiCredentialSetup(created = false, oneTimeSecret = null)
        }
        val secret = generateSecret()
        persistHash(secret)
        return WebUiCredentialSetup(created = true, oneTimeSecret = secret)
    }

    fun rotate(): String {
        val secret = generateSecret()
        persistHash(secret)
        return secret
    }

    fun matches(secret: String): Boolean {
        val submitted = secret.trim()
        if (submitted.isEmpty()) return false
        if (submitted.startsWith("fmcp_")) return false
        val expected = settings.getStringOrNull(SettingsUtils.KEY_WEB_UI_PASSWORD_HASH).orEmpty()
        if (expected.isEmpty()) return false
        val actual = hashSecret(submitted)
        return constantTimeTokenHashEquals(expected, actual)
    }

    private fun persistHash(secret: String) {
        settings.putString(SettingsUtils.KEY_WEB_UI_PASSWORD_HASH, hashSecret(secret))
        settings.putBoolean(SettingsUtils.KEY_WEB_UI_INITIALIZED, true)
        SettingsUtils.notifySettingChanged(SettingsUtils.KEY_WEB_UI_INITIALIZED)
    }

    private fun generateSecret(): String {
        val lookup = randomBytes(8).toHexString()
        val secret = randomBytes(32).toHexString()
        return "fwui_${lookup}_$secret"
    }

    private fun hashSecret(secret: String): String = hashMcpTokenSecret(secret.encodeToByteArray())
}

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xff
    value.toString(16).padStart(2, '0')
}
