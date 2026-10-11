package com.folderspan.ui.state.main

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawerStateTest {
    @Test
    fun reloadFromSettingsUpdatesStateFlowsAfterExternalSettingsWrite() {
        val settings = createInMemorySettings(
            SettingsUtils.KEY_DRAWER_SHOW_DEVICE to true,
            SettingsUtils.KEY_DRAWER_SHOW_NETWORK to true,
            SettingsUtils.KEY_FILE_VIEW_GRID to false,
        )
        val state = DrawerState(settings)
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_DEVICE, false)
        settings.putBoolean(SettingsUtils.KEY_DRAWER_SHOW_NETWORK, false)
        settings.putBoolean(SettingsUtils.KEY_FILE_VIEW_GRID, true)

        state.reloadFromSettings()

        assertFalse(state.isShowDevice.value)
        assertFalse(state.isShowNetwork.value)
        assertTrue(state.isFileGridView.value)
    }
}
