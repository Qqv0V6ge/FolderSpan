package com.folderspan

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.russhwolf.settings.Settings

expect fun createSettings(): Settings

expect val PlatformType: DeviceType

expect fun currentOsName(): String

expect fun currentDeviceName(): String

expect fun getSocketDevice(): SocketDevice

internal const val LEGACY_DEVICE_NAME_SETTING_KEY = "settings.deviceName"

internal fun removeLegacyStoredDeviceName(settings: Settings) {
    if (settings.hasKey(LEGACY_DEVICE_NAME_SETTING_KEY)) {
        settings.remove(LEGACY_DEVICE_NAME_SETTING_KEY)
    }
}
