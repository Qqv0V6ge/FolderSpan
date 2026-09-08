package com.folderspan.ui.state.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeviceAutoConnectSettingsTest {
    @Test
    fun accountDeviceAutoConnectDefaultsToDisabled() {
        val settings = createInMemorySettings()
        SettingsUtils.init(settings)

        assertFalse(SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled())
        assertFalse(SettingsState(settings).fileShareAccountDeviceAutoConnectEnabled.value)
    }

    @Test
    fun accountDeviceAutoConnectPersistsAndReloads() {
        val settings = createInMemorySettings()
        SettingsUtils.init(settings)
        val state = SettingsState(settings)

        state.setFileShareAccountDeviceAutoConnectEnabled(true)

        assertTrue(settings.getBoolean(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, false))
        assertTrue(SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled())
        assertTrue(SettingsState(settings).fileShareAccountDeviceAutoConnectEnabled.value)

        settings.putBoolean(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED, false)
        state.reloadFromSettings()

        assertFalse(state.fileShareAccountDeviceAutoConnectEnabled.value)
    }

    @Test
    fun accountDeviceAutoConnectIsExcludedFromProSync() {
        val syncableKeys = SettingsUtils.syncableSettings.map { item -> item.key }.toSet()

        assertFalse(KEY_FILE_SHARE_ACCOUNT_DEVICE_AUTO_CONNECT_ENABLED in syncableKeys)
    }
}
