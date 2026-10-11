package com.folderspan.ui.state.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils.KEY_APPEARANCE_THEME_MODE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_PORT
import com.folderspan.utils.SettingsUtils.KEY_ROOT_REQUEST_ON_STARTUP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStateRootAccessTest {
    @Test
    fun rootStartupRequestSettingDefaultsToDisabled() {
        val state = SettingsState(createInMemorySettings())

        assertFalse(state.rootStartupRequestEnabled.value)
    }

    @Test
    fun rootStartupRequestSettingPersistsChanges() {
        val settings = createInMemorySettings()
        val state = SettingsState(settings)

        state.setRootStartupRequestEnabled(true)

        assertTrue(state.rootStartupRequestEnabled.value)
        assertTrue(settings.getBoolean(KEY_ROOT_REQUEST_ON_STARTUP, false))
    }

    @Test
    fun reloadFromSettingsUpdatesStateFlowsAfterExternalSettingsWrite() {
        val settings = createInMemorySettings(
            KEY_FILE_SHARE_PORT to 13000,
            KEY_APPEARANCE_THEME_MODE to ThemeMode.System.name,
            KEY_EASY_FILE_SHARE_SHARE_PATHS to """["/old"]""",
        )
        val state = SettingsState(settings)
        settings.putInt(KEY_FILE_SHARE_PORT, 14000)
        settings.putString(KEY_APPEARANCE_THEME_MODE, ThemeMode.Dark.name)
        settings.putString(KEY_EASY_FILE_SHARE_SHARE_PATHS, """["/remote"]""")

        state.reloadFromSettings()

        assertEquals(14000, state.fileSharePort.value)
        assertEquals(ThemeMode.Dark, state.themeMode.value)
        assertEquals(listOf("/remote"), state.easyFileShareSharePaths.value)
    }
}
