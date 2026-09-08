package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLanBeaconTest {
    @Test
    fun nativePlatformsUseContinuousBeaconDiscovery() {
        listOf(DeviceType.Android, DeviceType.IOS, DeviceType.JVM).forEach { platformType ->
            assertFalse(platformType.usesTriggeredDeviceDiscovery())
        }
    }

    @Test
    fun browserUsesTriggeredWebRtcDiscovery() {
        assertTrue(DeviceType.JS.usesTriggeredDeviceDiscovery())
    }

    @Test
    fun linkAndSiteLocalHostsAreAccepted() {
        assertTrue(isLinkOrSiteLocalHost("192.168.1.20"))
        assertTrue(isLinkOrSiteLocalHost("10.0.0.8"))
        assertTrue(isLinkOrSiteLocalHost("172.16.0.2"))
        assertTrue(isLinkOrSiteLocalHost("fe80::1"))
        assertFalse(isLinkOrSiteLocalHost("8.8.8.8"))
        assertFalse(isLinkOrSiteLocalHost("1.1.1.1"))
    }

    @Test
    fun unsignedOrWrongProtocolBeaconsAreRejected() {
        val beacon = DeviceLanBeacon(
            deviceId = "dev-1",
            name = "Phone",
            type = DeviceType.JVM,
            sessionPort = 12040,
            fingerprintSha256 = "ABC",
            protocol = "http/1.1",
            publicKeyPem = "pem",
            issuedAtEpochSeconds = 1L,
            signature = "sig",
            approvalPort = 12042,
        )
        assertFalse(DeviceLanBeacons.verify(beacon, nowEpochSeconds = 1L))
    }

    @Test
    fun beaconKeepsApprovalAndSessionPortsSeparate() {
        val beacon = DeviceLanBeacon(
            deviceId = "peer-1",
            name = "Phone",
            type = DeviceType.Android,
            sessionPort = 12040,
            fingerprintSha256 = "ABC",
            publicKeyPem = "pem",
            issuedAtEpochSeconds = 1L,
            signature = "sig",
            approvalPort = 12042,
        )

        val decoded = ProtoBufCodec.decode<DeviceLanBeacon>(DeviceLanBeacons.encode(beacon))
        val device = DeviceLanBeacons.toSocketDevice(decoded, "192.168.10.20")

        assertTrue(device.port != device.httpsPort)
        assertEquals(12042, device.port)
        assertEquals(12040, device.httpsPort)
        assertEquals(12042, defaultDeviceShareApprovalPort(12040))
    }

    @Test
    fun beaconWithoutApprovalPortIsRejectedInsteadOfFallingBackToSessionPort() {
        val legacyBeacon = LegacyDeviceLanBeacon(
            deviceId = "peer-1",
            name = "Phone",
            type = DeviceType.Android,
            sessionPort = 12040,
            fingerprintSha256 = "ABC",
            publicKeyPem = "pem",
            issuedAtEpochSeconds = 1L,
            signature = "sig",
        )

        assertNull(DeviceLanBeacons.decode(ProtoBufCodec.encode(legacyBeacon)))
    }

    @Test
    fun beaconReusingSessionPortAsApprovalPortIsRejected() {
        val beacon = DeviceLanBeacon(
            deviceId = "peer-1",
            name = "Phone",
            type = DeviceType.Android,
            sessionPort = 12040,
            fingerprintSha256 = "ABC",
            publicKeyPem = "pem",
            issuedAtEpochSeconds = 1L,
            signature = "sig",
            approvalPort = 12040,
        )

        assertFalse(DeviceLanBeacons.verify(beacon, nowEpochSeconds = 1L))
        assertFailsWith<IllegalArgumentException> {
            DeviceLanBeacons.toSocketDevice(beacon, "192.168.10.20")
        }
    }

    @Test
    fun incomingBeaconFromPeerIsAcceptedWithoutScanning() {
        assertTrue(
            shouldAcceptLanBeacon(
                host = "192.168.10.20",
                beaconDeviceId = "peer-1",
                localDeviceId = "self-1",
                localIpSet = setOf("192.168.10.8"),
            )
        )
        assertFalse(
            shouldAcceptLanBeacon(
                host = "192.168.10.8",
                beaconDeviceId = "peer-1",
                localDeviceId = "self-1",
                localIpSet = setOf("192.168.10.8"),
            )
        )
        assertFalse(
            shouldAcceptLanBeacon(
                host = "192.168.10.20",
                beaconDeviceId = "self-1",
                localDeviceId = "self-1",
                localIpSet = setOf("192.168.10.8"),
            )
        )
    }

    @Test
    fun remoteBeaconSourceIsNotAddedToLocalHostFallbacks() {
        val localHosts = resolveLanBeaconLocalHosts(
            detectedLocalHosts = setOf("10.0.0.122"),
            localDeviceHost = "10.0.0.122",
        )

        assertFalse("10.0.0.226" in localHosts)
        assertTrue(
            shouldAcceptLanBeacon(
                host = "10.0.0.226",
                beaconDeviceId = "peer-1",
                localDeviceId = "self-1",
                localIpSet = localHosts,
            )
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LegacyDeviceLanBeacon(
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
)
