package com.folderspan.service.http

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileShareAccessKeyTest : ChineseLocalizationTest() {
    @Test
    fun accessKeyConfigDefaultsToDisabled() {
        val settings = createInMemorySettings()

        assertEquals(FileShareAccessKeyConfig(), settings.readFileShareAccessKeyConfig())
    }

    @Test
    fun accessKeyConfigPersistsAtomicallyAndNormalizesValue() {
        val settings = createInMemorySettings()

        settings.writeFileShareAccessKeyConfig(
            FileShareAccessKeyConfig(enabled = true, value = "  shared-key  ")
        )

        assertEquals(
            FileShareAccessKeyConfig(enabled = true, value = "shared-key"),
            settings.readFileShareAccessKeyConfig(),
        )
        assertTrue(settings.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "").contains("shared-key"))
    }

    @Test
    fun invalidStoredConfigFallsBackToDisabled() {
        val settings = createInMemorySettings(
            SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY to "not-json",
        )

        assertEquals(FileShareAccessKeyConfig(), settings.readFileShareAccessKeyConfig())
    }

    @Test
    fun accessKeyValueRequiresPrintableAsciiWithinLimit() {
        assertTrue(isValidFileShareAccessKeyValue("alpha 123-._~"))
        assertTrue(isValidFileShareAccessKeyValue("a".repeat(FILE_SHARE_ACCESS_KEY_MAX_LENGTH)))
        assertFalse(isValidFileShareAccessKeyValue(""))
        assertFalse(isValidFileShareAccessKeyValue("line\nbreak"))
        assertFalse(isValidFileShareAccessKeyValue(AppStrings.ui_key))
        assertFalse(isValidFileShareAccessKeyValue("a".repeat(FILE_SHARE_ACCESS_KEY_MAX_LENGTH + 1)))
    }

    @Test
    fun accessKeyComparisonRequiresExactValue() {
        assertTrue(constantTimeFileShareAccessKeyEquals("shared-key", "shared-key"))
        assertFalse(constantTimeFileShareAccessKeyEquals("shared-key", "Shared-Key"))
        assertFalse(constantTimeFileShareAccessKeyEquals("shared-key", null))
    }
}
