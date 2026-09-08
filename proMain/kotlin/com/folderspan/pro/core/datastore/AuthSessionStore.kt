package com.folderspan.pro.core.datastore

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

interface AuthSessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    fun clear()
    fun loadRememberedEmail(): String? = load()?.userEmail
}

class SettingsAuthSessionStore(
    private val settings: Settings,
) : AuthSessionStore {
    override fun load(): AuthSession? {
        val accessToken = settings.getStringOrNull(KEY_ACCESS_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return AuthSession(
            accessToken = accessToken,
            refreshToken = settings.getStringOrNull(KEY_REFRESH_TOKEN)?.takeIf { it.isNotBlank() },
            expiresIn = settings.getLongOrNull(KEY_EXPIRES_IN),
            userUuid = settings.getStringOrNull(KEY_USER_UUID)?.takeIf { it.isNotBlank() },
            userEmail = settings.getStringOrNull(KEY_USER_EMAIL)?.takeIf { it.isNotBlank() },
            loginData = settings.getStringOrNull(KEY_LOGIN_DATA)?.toJsonObjectOrNull(),
            expiresAtEpochSeconds = settings.getLongOrNull(KEY_EXPIRES_AT),
        )
    }

    override fun save(session: AuthSession) {
        settings.putString(KEY_ACCESS_TOKEN, session.accessToken)
        settings.putOptionalString(KEY_REFRESH_TOKEN, session.refreshToken)
        settings.putOptionalLong(KEY_EXPIRES_IN, session.expiresIn)
        settings.putOptionalString(KEY_USER_UUID, session.userUuid)
        settings.putOptionalString(KEY_USER_EMAIL, session.userEmail)
        settings.putOptionalString(KEY_REMEMBERED_EMAIL, session.userEmail)
        settings.putOptionalString(KEY_LOGIN_DATA, session.loginData?.toString())
        settings.putOptionalLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
    }

    override fun clear() {
        settings.remove(KEY_ACCESS_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
        settings.remove(KEY_EXPIRES_IN)
        settings.remove(KEY_USER_UUID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_LOGIN_DATA)
        settings.remove(KEY_EXPIRES_AT)
        settings.remove(SettingsUtils.KEY_CRYPTO_KEY)
        settings.remove(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY)
        settings.remove(SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC)
    }

    override fun loadRememberedEmail(): String? =
        settings.getStringOrNull(KEY_REMEMBERED_EMAIL)?.takeIf { it.isNotBlank() }
            ?: load()?.userEmail

    private fun Settings.putOptionalString(key: String, value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            remove(key)
        } else {
            putString(key, normalized)
        }
    }

    private fun Settings.putOptionalLong(key: String, value: Long?) {
        if (value == null) {
            remove(key)
        } else {
            putLong(key, value)
        }
    }

    private fun String.toJsonObjectOrNull(): JsonObject? =
        runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

    private companion object {
        const val KEY_ACCESS_TOKEN = "auth.accessToken"
        const val KEY_REFRESH_TOKEN = "auth.refreshToken"
        const val KEY_EXPIRES_IN = "auth.expiresIn"
        const val KEY_USER_UUID = "auth.userUuid"
        const val KEY_USER_EMAIL = "auth.userEmail"
        const val KEY_REMEMBERED_EMAIL = "auth.rememberedEmail"
        const val KEY_LOGIN_DATA = "auth.loginData"
        const val KEY_EXPIRES_AT = "auth.expiresAtEpochSeconds"
    }
}

class InMemoryAuthSessionStore(
    initialSession: AuthSession? = null,
) : AuthSessionStore {
    private var session: AuthSession? = initialSession
    private var rememberedEmail: String? = initialSession?.userEmail

    override fun load(): AuthSession? = session

    override fun save(session: AuthSession) {
        this.session = session
        rememberedEmail = session.userEmail ?: rememberedEmail
    }

    override fun clear() {
        session = null
    }

    override fun loadRememberedEmail(): String? = rememberedEmail
}
