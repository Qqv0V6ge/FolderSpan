package com.folderspan.pro.core.network

import com.folderspan.PlatformType
import com.folderspan.createSettings
import com.folderspan.currentDeviceName
import com.folderspan.getSocketDevice
import com.folderspan.utils.SettingsUtils

data class DeviceIdentity(
    val type: String,
    val key: String,
    val name: String,
)

internal fun runtimeDeviceIdentity(): DeviceIdentity {
    val socketDevice = runCatching { getSocketDevice() }.getOrNull()
    val settings = runCatching { createSettings() }.getOrNull()
    return DeviceIdentity(
        type = socketDevice?.type?.name ?: runCatching { PlatformType.name }.getOrDefault(""),
        key = socketDevice?.id?.takeIf { it.isNotBlank() }
            ?: settings?.getString(SettingsUtils.KEY_DEVICE_ID, "").orEmpty(),
        name = socketDevice?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { currentDeviceName() }.getOrDefault(""),
    )
}
