package com.folderspan

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.settings.AesGcmSettingsSecretVault
import com.folderspan.settings.SensitiveSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils.KEY_DEVICE_ID
import com.folderspan.utils.buildUserAgentDisplayName
import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings
import kotlinx.browser.window
import kotlin.random.Random

actual fun createSettings(): Settings {
    val plaintext = StorageSettings()
    val settings = SensitiveSettings(plaintext, AesGcmSettingsSecretVault(plaintext))
    removeLegacyStoredDeviceName(settings)
    if (!settings.hasKey(KEY_DEVICE_ID) || settings.getString(KEY_DEVICE_ID, "").isBlank()) {
        settings.putString(
            KEY_DEVICE_ID,
            "web-${Random.nextLong().toString().removePrefix("-")}-${Random.nextInt(100000, 999999)}"
        )
    }
    return settings
}

actual val PlatformType: DeviceType = DeviceType.JS

actual fun currentOsName(): String = ""

actual fun currentDeviceName(): String = buildUserAgentDisplayName(window.navigator.userAgent)

actual fun getSocketDevice(): SocketDevice {
    val settings = createSettings()
    val location = window.location
    val host = location.hostname
    return SocketDevice(
        id = settings.getString(KEY_DEVICE_ID, ""),
        name = currentDeviceName(),
        pathSeparator = PathUtils.getPathSeparator(),
        host = host,
        httpsPort = location.port.toIntOrNull() ?: 0,
        type = PlatformType,
        connectType = ConnectType.UnConnect
    )
}
