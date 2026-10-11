package com.folderspan.routes

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import kotlin.test.Test
import kotlin.test.assertEquals

class RawHttpShareEndpointTest {
    @Test
    fun repeatedEmulatorAdvertisedHostUsesRemotePeerHost() {
        val resolved = resolveShareCallbackDevice(
            advertisedDevice = device(host = "10.0.2.15"),
            remoteHost = "10.0.2.16",
            localHosts = setOf("10.0.2.15", "10.0.2.17"),
        )

        assertEquals("10.0.2.16", resolved.host)
    }

    @Test
    fun natRemoteHostDoesNotOverrideSenderAdvertisedHost() {
        val resolved = resolveShareCallbackDevice(
            advertisedDevice = device(host = "192.168.50.12"),
            remoteHost = "203.0.113.24",
            localHosts = setOf("192.168.80.9"),
        )

        assertEquals("192.168.50.12", resolved.host)
    }

    @Test
    fun receiverLocalRemoteHostDoesNotReplaceAdvertisedHost() {
        val resolved = resolveShareCallbackDevice(
            advertisedDevice = device(host = "10.0.2.15"),
            remoteHost = "10.0.2.17",
            localHosts = setOf("10.0.2.15", "10.0.2.17"),
        )

        assertEquals("10.0.2.15", resolved.host)
    }

    @Test
    fun missingHttpsPortFallsBackToAdvertisedPort() {
        val resolved = resolveShareCallbackDevice(
            advertisedDevice = device(host = "192.168.50.12", port = 12040, httpsPort = 0),
            remoteHost = "203.0.113.24",
            localHosts = setOf("192.168.80.9"),
        )

        assertEquals(12040, resolved.httpsPort)
    }

    private fun device(
        host: String,
        port: Int = 12040,
        httpsPort: Int = port,
    ): SocketDevice {
        return SocketDevice(
            id = "sender-device",
            name = "Sender",
            pathSeparator = "/",
            host = host,
            port = port,
            httpsPort = httpsPort,
            type = DeviceType.Android,
        )
    }
}
