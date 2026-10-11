package com.folderspan

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.getNetworkInterfacesInfo
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.server.selectPreferredAdvertisedIpv4Host
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.settings.IosKeychainStore
import com.folderspan.settings.SensitiveSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.secureRandomBytes
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.UIKit.UIDevice

private val iosSettings: Settings by lazy {
    val settings = SensitiveSettings(
        plaintext = NSUserDefaultsSettings.Factory().create("FolderSpan"),
        vault = IosKeychainStore,
    )
    removeLegacyStoredDeviceName(settings)
    if (!settings.hasKey(SettingsUtils.KEY_DEVICE_ID)) {
        settings.putString(SettingsUtils.KEY_DEVICE_ID, generateIosDeviceId())
    }
    settings
}

actual fun createSettings(): Settings = iosSettings

actual val PlatformType: DeviceType = DeviceType.IOS

actual fun currentOsName(): String = ""

actual fun currentDeviceName(): String = UIDevice.currentDevice.name.ifEmpty { "iOS" }

actual fun getSocketDevice(): SocketDevice {
    val settings = createSettings()
    val sessionPort = settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 12040)
    val host = selectPreferredAdvertisedIpv4Host(
        currentHost = "127.0.0.1",
        interfaces = runCatching { getNetworkInterfacesInfo() }.getOrDefault(emptyList()),
    )
    return SocketDevice(
        id = settings.getString(SettingsUtils.KEY_DEVICE_ID, ""),
        name = currentDeviceName(),
        pathSeparator = PathUtils.getPathSeparator(),
        host = host,
        port = defaultDeviceShareApprovalPort(sessionPort),
        httpsPort = sessionPort,
        tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
        type = PlatformType,
        connectType = ConnectType.UnConnect
    )
}

private fun generateIosDeviceId(): String {
    return secureRandomBytes(16).joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        value.toString(16).padStart(2, '0')
    }
}
