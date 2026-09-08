package com.folderspan.ui.state.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyFileShareAutoUpdateSettingsTest {
    @Test
    fun autoUpdateSettingsDefaultToDisabled() {
        val state = SettingsState(createInMemorySettings())

        assertFalse(state.easyFileShareAutoUpdateLinkShareFiles.value)
        assertFalse(state.easyFileShareAutoUpdateDeviceShareFiles.value)
    }

    @Test
    fun autoUpdateSettingsPersistChanges() {
        val settings = createInMemorySettings()
        val state = SettingsState(settings)

        state.setEasyFileShareAutoUpdateLinkShareFiles(false)
        state.setEasyFileShareAutoUpdateDeviceShareFiles(false)

        assertFalse(state.easyFileShareAutoUpdateLinkShareFiles.value)
        assertFalse(state.easyFileShareAutoUpdateDeviceShareFiles.value)
        assertFalse(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, false))
        assertFalse(settings.getBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, false))
    }

    @Test
    fun reloadFromSettingsUpdatesAutoUpdateStateFlows() {
        val settings = createInMemorySettings(
            KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES to false,
            KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES to false,
        )
        val state = SettingsState(settings)
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_LINK_SHARE_FILES, true)
        settings.putBoolean(KEY_EASY_FILE_SHARE_AUTO_UPDATE_DEVICE_SHARE_FILES, true)

        state.reloadFromSettings()

        assertTrue(state.easyFileShareAutoUpdateLinkShareFiles.value)
        assertTrue(state.easyFileShareAutoUpdateDeviceShareFiles.value)
    }
}
