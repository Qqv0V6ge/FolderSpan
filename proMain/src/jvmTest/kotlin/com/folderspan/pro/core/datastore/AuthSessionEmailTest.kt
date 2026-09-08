package com.folderspan.pro.core.datastore

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthSessionEmailTest {
    @Test
    fun parseAuthSessionReadsEmailFromTopLevelPayload() {
        val payload = Json.parseToJsonElement(
            """
            {
              "data": {
                "accessToken": "token-value",
                "refreshToken": "refresh-value",
                "email": "user@example.com"
              }
            }
            """.trimIndent(),
        )

        val session = parseAuthSession(payload)

        assertEquals("user@example.com", session?.userEmail)
    }

    @Test
    fun parseAuthSessionKeepsFullDataPayload() {
        val payload = Json.parseToJsonElement(
            """
            {
              "data": {
                "accessToken": "token-value",
                "refreshToken": "refresh-value",
                "email": "user@example.com",
                "profile": {
                  "name": "User Name",
                  "avatar": "https://example.com/avatar.png"
                }
              }
            }
            """.trimIndent(),
        )

        val session = parseAuthSession(payload)

        assertEquals(
            (payload as JsonObject)["data"],
            session?.loginData,
        )
    }

    @Test
    fun parseAuthSessionReadsEmailFromNestedUserPayload() {
        val payload = Json.parseToJsonElement(
            """
            {
              "data": {
                "accessToken": "token-value",
                "user": {
                  "email": "nested@example.com"
                }
              }
            }
            """.trimIndent(),
        )

        val session = parseAuthSession(payload)

        assertEquals("nested@example.com", session?.userEmail)
    }

    @Test
    fun settingsAuthSessionStorePersistsUserEmail() {
        val settings = MapSettings()
        val store = SettingsAuthSessionStore(settings)

        store.save(
            AuthSession(
                accessToken = "token-value",
                refreshToken = "refresh-value",
                userEmail = "saved@example.com",
            ),
        )

        assertEquals("saved@example.com", store.load()?.userEmail)
    }

    @Test
    fun settingsAuthSessionStorePersistsFullLoginDataPayload() {
        val settings = MapSettings()
        val store = SettingsAuthSessionStore(settings)
        val loginData = Json.parseToJsonElement(
            """
            {
              "accessToken": "token-value",
              "refreshToken": "refresh-value",
              "email": "saved@example.com",
              "profile": {
                "name": "Saved User"
              }
            }
            """.trimIndent(),
        ) as JsonObject

        store.save(
            AuthSession(
                accessToken = "token-value",
                refreshToken = "refresh-value",
                userEmail = "saved@example.com",
                loginData = loginData,
            ),
        )

        assertEquals(loginData, store.load()?.loginData)
    }

    @Test
    fun settingsAuthSessionStoreClearKeepsRememberedEmail() {
        val settings = MapSettings()
        val store = SettingsAuthSessionStore(settings)
        store.save(
            AuthSession(
                accessToken = "token-value",
                userEmail = "remembered@example.com",
                loginData = Json.parseToJsonElement(
                    """
                    {
                      "accessToken": "token-value",
                      "email": "remembered@example.com"
                    }
                    """.trimIndent(),
                ) as JsonObject,
            ),
        )

        store.clear()

        assertEquals(null, store.load())
        assertEquals("remembered@example.com", store.loadRememberedEmail())
    }

    @Test
    fun settingsAuthSessionStoreClearRemovesCryptoAndAccessKey() {
        val settings = MapSettings().apply {
            putString("auth.accessToken", "token-value")
            putString(SettingsUtils.KEY_CRYPTO_KEY, "dek-secret")
            putString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "access-key-secret")
            putString(SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC, "dek-sync-secret")
        }
        val store = SettingsAuthSessionStore(settings)

        store.clear()

        assertEquals(null, store.load())
        assertFalse(settings.hasKey(SettingsUtils.KEY_CRYPTO_KEY))
        assertFalse(settings.hasKey(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        assertFalse(settings.hasKey(SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC))
    }
}
