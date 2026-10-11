package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.client.normalizedTlsFingerprint
import com.folderspan.service.session.DEVICE_SESSION_RPC_CONNECT
import com.folderspan.service.session.DEVICE_SESSION_RPC_IDENTIFY
import com.folderspan.service.session.DEVICE_SESSION_RPC_LIST_PATH
import com.folderspan.service.session.isDeviceSessionRpcAllowedBeforeAuthorization
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualSessionDeviceConnectTest {
    @Test
    fun knownManualEndpointReusesDiscoveredIdentityAndFingerprint() = runSuspendTest {
        val known = socketDevice(
            id = "known-device",
            host = "10.0.0.226",
            port = 12040,
            fingerprint = FINGERPRINT,
        )
        var identifyCalled = false

        val resolved = resolveManualSessionDevice(
            host = " 10.0.0.226 ",
            port = 12040,
            knownDevices = listOf(known),
            identify = { _, _ ->
                identifyCalled = true
                error("known endpoint must not be probed")
            },
        )

        assertFalse(identifyCalled)
        assertEquals("known-device", resolved.id)
        assertEquals("10.0.0.226", resolved.host)
        assertEquals(FINGERPRINT, resolved.normalizedTlsFingerprint())
    }

    @Test
    fun unknownManualEndpointIsIdentifiedBeforePinnedConnect() = runSuspendTest {
        var identifiedHost = ""
        var identifiedPort = 0

        val resolved = resolveManualSessionDevice(
            host = "10.0.0.226",
            port = 12040,
            knownDevices = listOf(socketDevice(id = "stale", fingerprint = "")),
            identify = { host, port ->
                identifiedHost = host
                identifiedPort = port
                socketDevice(
                    id = "identified-device",
                    host = host,
                    port = port,
                    fingerprint = FINGERPRINT,
                )
            },
        )

        assertEquals("10.0.0.226", identifiedHost)
        assertEquals(12040, identifiedPort)
        assertEquals("identified-device", resolved.id)
        assertEquals(FINGERPRINT, resolved.normalizedTlsFingerprint())
    }

    @Test
    fun onlyIdentifyAndConnectAreAllowedBeforeAuthorization() {
        assertTrue(isDeviceSessionRpcAllowedBeforeAuthorization(DEVICE_SESSION_RPC_IDENTIFY))
        assertTrue(isDeviceSessionRpcAllowedBeforeAuthorization(DEVICE_SESSION_RPC_CONNECT))
        assertFalse(isDeviceSessionRpcAllowedBeforeAuthorization(DEVICE_SESSION_RPC_LIST_PATH))
    }

    private fun socketDevice(
        id: String,
        host: String = "10.0.0.226",
        port: Int = 12040,
        fingerprint: String,
    ): SocketDevice {
        return SocketDevice(
            id = id,
            name = id,
            pathSeparator = "/",
            host = host,
            port = port,
            type = DeviceType.JVM,
            transportType = DeviceTransportType.Session,
            httpsPort = port,
            tlsFingerprintSha256 = fingerprint,
        )
    }

    private companion object {
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    }
}
