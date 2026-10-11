package com.folderspan

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.getNetworkInterfacesInfo
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.server.selectPreferredAdvertisedIpv4Host
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.settings.DESKTOP_SECURE_SETTINGS_DIRECTORY
import com.folderspan.settings.FileAesGcmSecretVault
import com.folderspan.settings.SensitiveSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Locale
import java.util.prefs.Preferences

internal val isMacOs: Boolean = run {
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
    osName.contains("mac") || osName.contains("darwin")
}
internal val isMacAppBundle: Boolean = run {
    val marker = ".app/Contents"
    val javaHome = System.getProperty("java.home").orEmpty()
    val classPath = System.getProperty("java.class.path").orEmpty()
    val userDir = System.getProperty("user.dir").orEmpty()
    listOf(javaHome, classPath, userDir).any { item ->  item.contains(marker) }
}

private val desktopSettings: Settings by lazy {
    val preferences = Preferences.userRoot()
    val vault = FileAesGcmSecretVault(
        resolveDesktopApplicationDataDirectory().resolve(DESKTOP_SECURE_SETTINGS_DIRECTORY),
    )
    val settings = SensitiveSettings(PreferencesSettings(preferences), vault)
    removeLegacyStoredDeviceName(settings)
    if (!settings.hasKey(SettingsUtils.KEY_DEVICE_ID)) {
        settings.putString(SettingsUtils.KEY_DEVICE_ID, generateDesktopDeviceId())
    }
    settings
}

actual fun createSettings(): Settings = desktopSettings

actual val PlatformType: DeviceType = DeviceType.JVM

actual fun currentOsName(): String = System.getProperty("os.name").orEmpty()

actual fun currentDeviceName(): String =
    "${System.getProperty("user.name")}-${System.getProperty("os.name")}"

actual fun getSocketDevice(): SocketDevice {
    val settings = createSettings()
    val sessionPort = settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 12040)
    val currentHost = runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("127.0.0.1")
    val interfaces = runCatching { getNetworkInterfacesInfo() }.getOrDefault(emptyList())
    val host = selectPreferredAdvertisedIpv4Host(
        currentHost = currentHost,
        interfaces = interfaces,
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

private fun generateDesktopDeviceId(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> "%02x".format(byte) }
}
