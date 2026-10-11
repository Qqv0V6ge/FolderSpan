package com.folderspan

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.settings.AesGcmSettingsSecretVault
import com.folderspan.settings.RuntimeScopedSettings
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.settings.SensitiveSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils.KEY_DEVICE_ID
import com.folderspan.utils.buildUserAgentDisplayName
import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings
import kotlinx.browser.window
import kotlin.random.Random

actual fun createSettings(): Settings {
    val plaintext = RuntimeScopedSettings(StorageSettings(), WebUiAttachment.target?.origin ?: "unselected")
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
    val port = settings.getInt(com.folderspan.utils.SettingsUtils.KEY_FILE_SHARE_PORT, 12040)
    val host = com.folderspan.service.http.server.getAllIPAddresses(com.folderspan.service.http.server.SocketClientIPEnum.IPV4_UP).firstOrNull() ?: "127.0.0.1"
    return SocketDevice(
        id = settings.getString(KEY_DEVICE_ID, ""),
        name = currentDeviceName(),
        pathSeparator = PathUtils.getPathSeparator(),
        host = host,
        port = com.folderspan.service.http.server.defaultDeviceShareApprovalPort(port),
        httpsPort = port,
        tlsFingerprintSha256 = com.folderspan.service.http.tls.currentDeviceTlsFingerprint(),
        type = PlatformType,
        connectType = ConnectType.UnConnect
    )
}
