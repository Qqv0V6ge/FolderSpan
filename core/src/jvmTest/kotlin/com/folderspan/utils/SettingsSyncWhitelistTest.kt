package com.folderspan.utils

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSyncWhitelistTest : ChineseLocalizationTest() {
    @Test
    fun syncableSettingsMatchDocumentedUploadWhitelist() {
        val documentedKeys = readDocumentedUploadKeys()
        val codeKeys = SettingsUtils.syncableSettings.map { it.key }.toSet()

        assertEquals(documentedKeys, codeKeys)
    }

    @Test
    fun syncableSettingsExcludeSensitiveAndLocalOnlyKeys() {
        val keys = SettingsUtils.syncableSettings.map { it.key }.toSet()

        assertFalse(SettingsUtils.KEY_DEVICE_TLS_PKCS12_PASSWORD in keys)
        assertFalse(SettingsUtils.KEY_DEVICE_ID in keys)
        assertFalse(SettingsUtils.KEY_CRYPTO_KEY in keys)
        assertFalse(SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC in keys)
        assertFalse(SettingsUtils.KEY_LAST_CRASH in keys)
        assertFalse(SettingsUtils.KEY_ONBOARDING_COMPLETED in keys)
        assertFalse(SettingsUtils.KEY_REMOTE_OPEN_DOWNLOAD_DIR in keys)
        assertFalse(SettingsUtils.KEY_EDITOR_SHOW_LINE_NUMBERS in keys)
        assertFalse(SettingsUtils.KEY_EDITOR_AUTOMATIC_WRAP in keys)
        assertFalse(SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED in keys)
        assertFalse(SettingsUtils.KEY_ANNOUNCEMENT_READ_CUTOFF in keys)
        assertFalse(SettingsUtils.KEY_APP_UPDATE_CHANNEL in keys)
        assertFalse("settings.deviceName" in keys)
    }

    @Test
    fun syncableSettingsIncludeExpectedSafeUiKeys() {
        val keys = SettingsUtils.syncableSettings.map { it.key }.toSet()

        assertTrue(SettingsUtils.KEY_FILE_SHARE_ENABLED in keys)
        assertTrue(SettingsUtils.KEY_FILE_SHARE_PORT in keys)
        assertTrue(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY in keys)
        assertTrue(SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS in keys)
        assertTrue(SettingsUtils.KEY_APPEARANCE_THEME_MODE in keys)
        assertTrue(SettingsUtils.KEY_APPEARANCE_DYNAMIC_COLOR in keys)
        assertTrue(SettingsUtils.KEY_ROOT_REQUEST_ON_STARTUP in keys)
        assertTrue(SettingsUtils.KEY_REMOTE_OPEN_CONFIRM in keys)
    }

    @Test
    fun allSettingsKeysUseSettingsPrefixExceptSnapshots() {
        val keys = SettingsUtils::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }

        val unexpected = keys.filterNot {
                it.startsWith("settings.") ||
                it == SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT ||
                it == SettingsUtils.KEY_EDITOR_SEARCH_HISTORY_SYNC_SNAPSHOT ||
                it == SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT ||
                it == SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC
        }

        assertEquals(emptyList(), unexpected.sorted())
    }

    private fun readDocumentedUploadKeys(): Set<String> {
        val path = listOf(
            Path.of("docs/product/settings-sync-whitelist.md"),
            Path.of("../docs/product/settings-sync-whitelist.md"),
        ).firstOrNull { it.exists() } ?: error(AppStrings.ui_test_settings_sync_whitelist_not_found_docs_product_settings_sync_whitelist)

        return path.readLines()
            .asSequence()
            .filter { it.startsWith("| `") }
            .map { line -> line.split("|").map { it.trim() } }
            .filter { columns -> columns.size >= 5 && columns[2] == AppStrings.ui_test_settings_sync_whitelist_is }
            .map { columns -> columns[1].removeSurrounding("`") }
            .toSet()
    }
}
