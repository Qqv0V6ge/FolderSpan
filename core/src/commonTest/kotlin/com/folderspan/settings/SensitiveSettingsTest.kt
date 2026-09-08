package com.folderspan.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveSettingsTest {
    @Test
    fun leftoverPlaintextSecretsAreScrubbedOnReadAndWrite() {
        val plaintext = createInMemorySettings(
            SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY to "leftover-access-key",
            SettingsUtils.KEY_APPEARANCE_THEME_MODE to "Dark",
        )
        val vault = MemoryStringSecretVault()
        val settings = SensitiveSettings(plaintext, vault)

        assertEquals("", settings.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, ""))
        assertFalse(settings.hasKey(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        assertNull(vault.get(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        assertFalse(plaintext.hasKey(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        assertEquals("Dark", settings.getString(SettingsUtils.KEY_APPEARANCE_THEME_MODE, ""))
        assertTrue(plaintext.hasKey(SettingsUtils.KEY_APPEARANCE_THEME_MODE))

        settings.putString("auth.accessToken", "new-token")
        assertEquals("new-token", vault.get("auth.accessToken"))
        assertFalse(plaintext.hasKey("auth.accessToken"))
    }

    @Test
    fun newSensitiveWritesDoNotStayInPlaintextStorage() {
        val plaintext = createInMemorySettings()
        val vault = MemoryStringSecretVault()
        val settings = SensitiveSettings(plaintext, vault)

        settings.putString("auth.accessToken", "secret-token")
        settings.putString(SettingsUtils.KEY_APPEARANCE_THEME_MODE, "Dark")

        assertEquals("secret-token", settings.getString("auth.accessToken", ""))
        assertEquals("secret-token", vault.get("auth.accessToken"))
        assertFalse(plaintext.hasKey("auth.accessToken"))
        assertEquals("Dark", plaintext.getString(SettingsUtils.KEY_APPEARANCE_THEME_MODE, ""))
    }

    @Test
    fun removingASensitiveKeyClearsTheVault() {
        val vault = MemoryStringSecretVault(mapOf(SettingsUtils.KEY_CRYPTO_KEY to "k"))
        val settings = SensitiveSettings(createInMemorySettings(), vault)

        settings.remove(SettingsUtils.KEY_CRYPTO_KEY)

        assertNull(settings.getStringOrNull(SettingsUtils.KEY_CRYPTO_KEY))
        assertFalse(settings.hasKey(SettingsUtils.KEY_CRYPTO_KEY))
        assertFalse(vault.contains(SettingsUtils.KEY_CRYPTO_KEY))
    }

    @Test
    fun assignedDeviceIdIsWriteOnce() {
        val plaintext = createInMemorySettings()
        val vault = MemoryStringSecretVault()
        val settings = SensitiveSettings(plaintext, vault)

        settings.putString(SettingsUtils.KEY_DEVICE_ID, "  ")
        assertNull(settings.getStringOrNull(SettingsUtils.KEY_DEVICE_ID))
        assertFalse(settings.hasKey(SettingsUtils.KEY_DEVICE_ID))

        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-a")
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-b")
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-a")
        settings.remove(SettingsUtils.KEY_DEVICE_ID)
        settings.clear()

        assertEquals("device-a", settings.getString(SettingsUtils.KEY_DEVICE_ID, ""))
        assertEquals("device-a", vault.get(SettingsUtils.KEY_DEVICE_ID))
        assertTrue(settings.hasKey(SettingsUtils.KEY_DEVICE_ID))
        assertFalse(plaintext.hasKey(SettingsUtils.KEY_DEVICE_ID))
    }
}

class SensitiveSettingKeysTest {
    @Test
    fun classifiesSessionAndTlsSecrets() {
        assertTrue(SensitiveSettingKeys.isSensitive("auth.accessToken"))
        assertTrue(SensitiveSettingKeys.isSensitive("auth.refreshToken"))
        assertTrue(SensitiveSettingKeys.isSensitive("pro.session"))
        assertTrue(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_DEVICE_ID))
        assertTrue(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        assertTrue(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_CRYPTO_KEY))
        assertTrue(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC))
        assertTrue(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD))
        assertFalse(SensitiveSettingKeys.isSensitive(SettingsUtils.KEY_FILE_SHARE_PORT))
        assertFalse(SensitiveSettingKeys.isSensitive("settings.web.secretVault.master"))
        assertFalse(SensitiveSettingKeys.isSensitive("settings.web.secretVault.values"))
    }

    @Test
    fun assignedDeviceIdWritesAreIgnoredAfterFirstValue() {
        assertNull(SensitiveSettingKeys.assignedDeviceId("  "))
        assertEquals("device-a", SensitiveSettingKeys.assignedDeviceId(" device-a "))

        assertFalse(
            SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(
                SettingsUtils.KEY_DEVICE_ID,
                existing = null,
                incoming = "device-a",
            ),
        )
        assertTrue(
            SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(
                SettingsUtils.KEY_DEVICE_ID,
                existing = null,
                incoming = "  ",
            ),
        )
        assertTrue(
            SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(
                SettingsUtils.KEY_DEVICE_ID,
                existing = "device-a",
                incoming = "device-b",
            ),
        )
        assertTrue(
            SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(
                SettingsUtils.KEY_DEVICE_ID,
                existing = "device-a",
                incoming = "device-a",
            ),
        )
        assertFalse(
            SensitiveSettingKeys.shouldIgnoreDeviceIdWrite(
                SettingsUtils.KEY_CRYPTO_KEY,
                existing = "device-a",
                incoming = "other",
            ),
        )
        assertTrue(
            SensitiveSettingKeys.shouldPreserveAssignedDeviceId(
                SettingsUtils.KEY_DEVICE_ID,
                "device-a",
            ),
        )
        assertFalse(
            SensitiveSettingKeys.shouldPreserveAssignedDeviceId(
                SettingsUtils.KEY_DEVICE_ID,
                "  ",
            ),
        )
    }
}

class AesGcmSettingsSecretVaultTest {
    @Test
    fun encryptedVaultDoesNotLeavePlaintextSecretsInBackingStorage() {
        val plaintext = createInMemorySettings(
            SettingsUtils.KEY_APPEARANCE_THEME_MODE to "System",
        )
        val vault = AesGcmSettingsSecretVault(plaintext)
        val settings = SensitiveSettings(plaintext, vault)

        settings.putString("auth.refreshToken", "refresh-secret")
        settings.putString(SettingsUtils.KEY_CRYPTO_KEY, "dek-secret")
        settings.putString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "access-key-secret")

        assertEquals("refresh-secret", settings.getString("auth.refreshToken", ""))
        assertEquals("dek-secret", settings.getString(SettingsUtils.KEY_CRYPTO_KEY, ""))
        assertEquals("access-key-secret", settings.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, ""))
        assertFalse(plaintext.hasKey("auth.refreshToken"))
        assertFalse(plaintext.hasKey(SettingsUtils.KEY_CRYPTO_KEY))
        assertFalse(plaintext.hasKey(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY))
        plaintext.keys.forEach { key ->
            val stored = plaintext.getStringOrNull(key).orEmpty()
            assertFalse(stored.contains("refresh-secret"), key)
            assertFalse(stored.contains("dek-secret"), key)
            assertFalse(stored.contains("access-key-secret"), key)
        }

        val reloaded = SensitiveSettings(plaintext, AesGcmSettingsSecretVault(plaintext))
        assertEquals("refresh-secret", reloaded.getString("auth.refreshToken", ""))
        assertEquals("dek-secret", reloaded.getString(SettingsUtils.KEY_CRYPTO_KEY, ""))
        assertEquals("access-key-secret", reloaded.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, ""))
    }
}
