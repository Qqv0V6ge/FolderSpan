package com.folderspan.ui.state.settings

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_APPROVE
import com.folderspan.utils.SettingsUtils.KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EasyFileShareDefaultsTest {
    @Test
    fun pageAutoStartAndAutoApproveDefaultToEnabled() {
        val settings = createInMemorySettings()
        SettingsUtils.init(settings)
        val state = SettingsState(settings)

        assertTrue(SettingsUtils.easyFileShare.isAutoStartOnOpen())
        assertTrue(SettingsUtils.easyFileShare.getAutoApprove())
        assertTrue(state.easyFileShareAutoStartOnOpen.value)
        assertTrue(state.easyFileShareAutoApprove.value)
        assertEquals("true", SettingsUtils.syncableSettingOrNull(KEY_EASY_FILE_SHARE_AUTO_START_ON_OPEN)?.defaultValue)
        assertEquals("true", SettingsUtils.syncableSettingOrNull(KEY_EASY_FILE_SHARE_AUTO_APPROVE)?.defaultValue)
    }
}
