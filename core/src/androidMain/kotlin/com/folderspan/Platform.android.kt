package com.folderspan

import android.net.ConnectivityManager
import android.os.Build
import com.folderspan.data.main.device.DeviceType
import com.folderspan.security.TinkEncryption
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.settings.DataStoreSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.Key
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

actual fun createSettings(): Settings {
    val context = androidContext()
    TinkEncryption.initialize(context)

    val settings = DataStoreSettings(context, TinkEncryption.getAead())

    removeLegacyStoredDeviceName(settings)
    if (!settings.hasKey(SettingsUtils.KEY_DEVICE_ID)) {
        try {
            val secretKey: Key = SecretKeySpec("1234567890123456".toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(UUID.randomUUID().toString().replace("-", "O").toByteArray())
            val deviceId = Base64.encode(encryptedBytes)
            settings.putString(SettingsUtils.KEY_DEVICE_ID, deviceId.replace("+", "Y").replace("/", "L"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return settings
}

actual val PlatformType: DeviceType = DeviceType.Android

actual fun currentOsName(): String = ""

actual fun currentDeviceName(): String = "${Build.MODEL}-Android"

actual fun getSocketDevice(): SocketDevice {
    val settings = createSettings()
    val sessionPort = settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 12040)
    return SocketDevice(
        id = settings.getString(SettingsUtils.KEY_DEVICE_ID, ""),
        name = currentDeviceName(),
        pathSeparator = PathUtils.getPathSeparator(),
        host = getLocalHostAddress(),
        port = defaultDeviceShareApprovalPort(sessionPort),
        httpsPort = sessionPort,
        tlsFingerprintSha256 = currentDeviceTlsFingerprint(),
        type = PlatformType,
        connectType = ConnectType.UnConnect
    )
}

private fun getLocalHostAddress(): String {
    return activeNetworkIpv4Address()
        ?: fallbackNetworkIpv4Address()
        ?: "127.0.0.1"
}

private fun activeNetworkIpv4Address(): String? = runCatching {
    val connectivityManager = androidContext().getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching null
    val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return@runCatching null

    linkProperties.linkAddresses
        .asSequence()
        .map { item -> item.address }
        .filterIsInstance<Inet4Address>()
        .filter { item -> item.isUsableLocalAddress() }
        .sortedWith(compareBy<Inet4Address>({ item -> item.isLinkLocalAddress }, ::ipv4SortKey))
        .firstOrNull()
        ?.hostAddress
}.getOrNull()

private fun fallbackNetworkIpv4Address(): String? = runCatching {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
    val candidates = buildList {
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val isEligible = runCatching {
                networkInterface.isUp && !networkInterface.isLoopback
            }.getOrDefault(false)
            if (!isEligible) continue

            val addresses = runCatching { networkInterface.inetAddresses }.getOrNull() ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isUsableLocalAddress()) {
                    add(AndroidIpv4Candidate(networkInterface.name, address))
                }
            }
        }
    }

    selectAndroidFallbackIpv4Address(candidates)?.hostAddress
}.getOrNull()

internal data class AndroidIpv4Candidate(
    val interfaceName: String,
    val address: Inet4Address,
)

internal fun selectAndroidFallbackIpv4Address(
    candidates: List<AndroidIpv4Candidate>,
): Inet4Address? {
    return candidates.minWithOrNull(
        compareBy<AndroidIpv4Candidate>(
            { item -> androidNetworkInterfacePriority(item.interfaceName) },
            { item -> item.interfaceName.lowercase(Locale.ROOT) },
            { item -> item.address.isLinkLocalAddress },
            { item -> ipv4SortKey(item.address) },
        )
    )?.address
}

private fun androidNetworkInterfacePriority(interfaceName: String): Int {
    val normalizedName = interfaceName.lowercase(Locale.ROOT)
    return when {
        normalizedName.startsWith("wlan") ||
            normalizedName.startsWith("wifi") ||
            normalizedName.startsWith("swlan") -> 0

        normalizedName.startsWith("eth") ||
            normalizedName.startsWith("en") ||
            normalizedName.startsWith("usb") ||
            normalizedName.startsWith("rndis") -> 1

        normalizedName.startsWith("rmnet") ||
            normalizedName.startsWith("ccmni") ||
            normalizedName.startsWith("ccemni") ||
            normalizedName.startsWith("pdp") -> 2

        else -> 3
    }
}

private fun Inet4Address.isUsableLocalAddress(): Boolean {
    return !isAnyLocalAddress && !isLoopbackAddress && !isMulticastAddress
}

private fun ipv4SortKey(address: Inet4Address): Long {
    return address.address.fold(0L) { result, octet ->
        (result shl 8) or (octet.toInt() and 0xff).toLong()
    }
}
