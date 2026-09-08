package com.folderspan.ui.state.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.LogCapture
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SettingsUtils.KEY_AUTO_CAPTURE_LOGS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoCaptureLogsSettingsTest {
    @AfterTest
    fun tearDown() {
        LogCapture.setErrorListener(null)
        LogCapture.setEnabled(false)
        LogCapture.clear()
    }
    @Test
    fun autoCaptureLogsDefaultsToDisabled() {
        val settings = createInMemorySettings()
        SettingsUtils.init(settings)

        assertFalse(SettingsState(settings).autoCaptureLogs.value)
        assertFalse(settings.getBoolean(KEY_AUTO_CAPTURE_LOGS, false))
    }

    @Test
    fun autoCaptureLogsPersistsOnAndOffAcrossReload() {
        val settings = createInMemorySettings()
        SettingsUtils.init(settings)
        val state = SettingsState(settings)

        state.setAutoCaptureLogs(true)

        assertTrue(state.autoCaptureLogs.value)
        assertTrue(settings.getBoolean(KEY_AUTO_CAPTURE_LOGS, false))

        settings.putBoolean(KEY_AUTO_CAPTURE_LOGS, false)
        state.reloadFromSettings()

        assertFalse(state.autoCaptureLogs.value)

        state.setAutoCaptureLogs(true)
        assertTrue(SettingsState(settings).autoCaptureLogs.value)
    }

    @Test
    fun autoCaptureLogsIsExcludedFromProSync() {
        val syncableKeys = SettingsUtils.syncableSettings.map { item -> item.key }.toSet()

        assertFalse(KEY_AUTO_CAPTURE_LOGS in syncableKeys)
    }
}
