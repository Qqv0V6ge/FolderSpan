package com.folderspan.ui.state.settings

import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.readFileShareAccessKeyConfig
import com.folderspan.service.http.writeFileShareAccessKeyConfig
import com.folderspan.test.createInMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStateFileShareAccessKeyTest {
    @Test
    fun fileShareAccessKeyDefaultsToDisabled() {
        val state = SettingsState(createInMemorySettings())

        assertEquals(FileShareAccessKeyConfig(), state.fileShareAccessKeyConfig.value)
    }

    @Test
    fun fileShareAccessKeyPersistsAsOneConfig() {
        val settings = createInMemorySettings()
        val state = SettingsState(settings)
        val config = FileShareAccessKeyConfig(enabled = true, value = "shared-key")

        state.setFileShareAccessKeyConfig(config)

        assertEquals(config, state.fileShareAccessKeyConfig.value)
        assertEquals(config, settings.readFileShareAccessKeyConfig())
    }

    @Test
    fun reloadUpdatesFileShareAccessKeyAfterRemoteWrite() {
        val settings = createInMemorySettings()
        val state = SettingsState(settings)
        val remoteConfig = FileShareAccessKeyConfig(enabled = true, value = "remote-key")
        settings.writeFileShareAccessKeyConfig(remoteConfig)

        state.reloadFromSettings()

        assertEquals(remoteConfig, state.fileShareAccessKeyConfig.value)
    }
}
