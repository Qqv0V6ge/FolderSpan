package com.folderspan

import com.folderspan.test.createInMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeviceNameSettingsMigrationTest {
    @Test
    fun removesLegacyStoredDeviceNameWithoutTouchingOtherSettings() {
        val settings = createInMemorySettings(
            LEGACY_DEVICE_NAME_SETTING_KEY to "Custom name",
            "settings.keep" to "value",
        )

        removeLegacyStoredDeviceName(settings)

        assertFalse(settings.hasKey(LEGACY_DEVICE_NAME_SETTING_KEY))
        assertEquals("value", settings.getString("settings.keep", ""))
    }
}
