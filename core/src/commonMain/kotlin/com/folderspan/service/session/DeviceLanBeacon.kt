package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock

internal const val DEVICE_LAN_BEACON_PORT = 12041
internal const val DEVICE_LAN_BEACON_MAX_AGE_SECONDS = 30L

fun DeviceType.usesTriggeredDeviceDiscovery(): Boolean = this == DeviceType.JS

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceLanBeacon(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: DeviceType,
    @ProtoNumber(4) val sessionPort: Int,
    @ProtoNumber(5) val fingerprintSha256: String,
    @ProtoNumber(6) val protocol: String = DEVICE_SESSION_ALPN,
    @ProtoNumber(7) val publicKeyPem: String = "",
    @ProtoNumber(8) val issuedAtEpochSeconds: Long,
    @ProtoNumber(9) val signature: String = "",
    @ProtoNumber(10) val pathSeparator: String = "/",
    @ProtoNumber(11) val approvalPort: Int,
)

internal object DeviceLanBeacons {
    fun create(
        device: SocketDevice,
        sessionPort: Int = device.httpsPort,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): DeviceLanBeacon? {
        val fingerprint = currentDeviceTlsFingerprint()
        val publicKeyPem = DeviceTlsIdentity.publicKeyPem()?.trim().orEmpty()
        if (fingerprint.isBlank() || publicKeyPem.isBlank() || device.id.isBlank()) return null
        val approvalPort = device.port
        if (!validDistinctPorts(sessionPort = sessionPort, approvalPort = approvalPort)) return null
        val unsigned = DeviceLanBeacon(
            deviceId = device.id,
            name = device.name,
            type = device.type,
            sessionPort = sessionPort,
            fingerprintSha256 = normalizeTlsFingerprintSha256(fingerprint),
            protocol = DEVICE_SESSION_ALPN,
            publicKeyPem = publicKeyPem,
            issuedAtEpochSeconds = nowEpochSeconds,
            pathSeparator = device.pathSeparator,
            approvalPort = approvalPort,
        )
        val signature = DeviceTlsIdentity.signSha256WithRsa(unsigned.canonicalPayload())?.takeIf(String::isNotBlank)
            ?: return null
        return unsigned.copy(signature = signature)
    }

    fun encode(beacon: DeviceLanBeacon): ByteArray = ProtoBufCodec.encode(beacon)

    fun decode(bytes: ByteArray): DeviceLanBeacon? = runCatching {
        ProtoBufCodec.decode<DeviceLanBeacon>(bytes)
    }.getOrNull()

    fun verify(beacon: DeviceLanBeacon, nowEpochSeconds: Long = Clock.System.now().epochSeconds): Boolean {
        if (beacon.protocol != DEVICE_SESSION_ALPN) return false
        if (beacon.deviceId.isBlank() || beacon.fingerprintSha256.isBlank() || beacon.publicKeyPem.isBlank()) return false
        if (!validDistinctPorts(beacon.sessionPort, beacon.approvalPort)) return false
        if (kotlin.math.abs(nowEpochSeconds - beacon.issuedAtEpochSeconds) > DEVICE_LAN_BEACON_MAX_AGE_SECONDS) return false
        return DeviceTlsIdentity.verifySha256WithRsa(
            publicKeyPem = beacon.publicKeyPem,
            payload = beacon.copy(signature = "").canonicalPayload(),
            signature = beacon.signature,
        )
    }

    fun toSocketDevice(beacon: DeviceLanBeacon, host: String): SocketDevice {
        require(validDistinctPorts(beacon.sessionPort, beacon.approvalPort)) {
            "LAN beacon must advertise distinct valid Session and share approval ports"
        }
        return SocketDevice(
            id = beacon.deviceId,
            name = beacon.name,
            pathSeparator = beacon.pathSeparator.ifBlank { "/" },
            host = host,
            port = beacon.approvalPort,
            type = beacon.type,
            transportType = DeviceTransportType.Session,
            httpsPort = beacon.sessionPort,
            tlsFingerprintSha256 = normalizeTlsFingerprintSha256(beacon.fingerprintSha256),
        )
    }

    private fun DeviceLanBeacon.canonicalPayload(): ByteArray = buildString {
        append("folderspan-lan-beacon-v2\n")
        append("device=").append(deviceId.trim()).append('\n')
        append("name=").append(name.trim()).append('\n')
        append("type=").append(type.name).append('\n')
        append("port=").append(sessionPort).append('\n')
        append("fingerprint=").append(normalizeTlsFingerprintSha256(fingerprintSha256)).append('\n')
        append("protocol=").append(protocol).append('\n')
        append("issuedAt=").append(issuedAtEpochSeconds).append('\n')
        append("approvalPort=").append(approvalPort).append('\n')
    }.encodeToByteArray()

    private fun validDistinctPorts(sessionPort: Int, approvalPort: Int): Boolean {
        return sessionPort in 1..65535 && approvalPort in 1..65535 && sessionPort != approvalPort
    }
}

internal fun isLinkOrSiteLocalHost(host: String): Boolean {
    val value = host.trim().lowercase().substringBefore('%')
    if (value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd")) return true
    val parts = value.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || a == 127
}

internal fun resolveLanBeaconLocalHosts(
    detectedLocalHosts: Set<String>,
    localDeviceHost: String?,
): Set<String> {
    val advertisedLocalHost = localDeviceHost?.trim().orEmpty()
    return if (advertisedLocalHost.isEmpty()) {
        detectedLocalHosts
    } else {
        detectedLocalHosts + advertisedLocalHost
    }
}

internal fun shouldAcceptLanBeacon(
    host: String,
    beaconDeviceId: String,
    localDeviceId: String,
    localIpSet: Set<String>,
): Boolean {
    if (beaconDeviceId.isBlank() || beaconDeviceId == localDeviceId) return false
    if (host in localIpSet || !isLinkOrSiteLocalHost(host)) return false
    return true
}
