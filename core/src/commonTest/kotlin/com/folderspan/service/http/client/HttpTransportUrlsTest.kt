package com.folderspan.service.http.client

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpTransportUrlsTest {
    @Test
    fun deviceApiBaseUrlAlwaysUsesHttpsPort() {
        val device = SocketDevice(
            id = "device-1",
            name = "Device",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12443,
            type = DeviceType.JVM,
        )

        assertEquals("https://192.168.1.20:12443", device.deviceApiBaseUrl())
        assertFalse(usePlainHttpDeviceTransport())
    }

    @Test
    fun deviceApiBaseUrlFallsBackToLegacyPortAsHttps() {
        val device = SocketDevice(
            id = "device-2",
            name = "Device",
            pathSeparator = "/",
            host = "fe80::1",
            port = 12040,
            httpsPort = 0,
            type = DeviceType.JVM,
        )

        assertEquals("https://[fe80::1]:12040", device.deviceApiBaseUrl())
    }

    @Test
    fun deviceShareApprovalBaseUrlUsesAdvertisedHttpPortInsteadOfSessionPort() {
        val device = SocketDevice(
            id = "device-share",
            name = "Device",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12042,
            httpsPort = 12040,
            type = DeviceType.JVM,
        )

        assertEquals("https://192.168.1.20:12042", device.deviceShareApprovalBaseUrl())
        assertEquals("https://192.168.1.20:12040", device.deviceApiBaseUrl())
    }

    @Test
    fun deviceShareApprovalBaseUrlRejectsMissingApprovalPort() {
        val device = SocketDevice(
            id = "device-share",
            name = "Device",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 0,
            httpsPort = 12040,
            type = DeviceType.JVM,
            transportType = DeviceTransportType.Session,
        )

        assertFailsWith<IllegalArgumentException> {
            device.deviceShareApprovalBaseUrl()
        }
    }

    @Test
    fun deviceShareApprovalBaseUrlRejectsSessionPortReuse() {
        val device = SocketDevice(
            id = "device-share",
            name = "Device",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            transportType = DeviceTransportType.Session,
        )

        assertFailsWith<IllegalArgumentException> {
            device.deviceShareApprovalBaseUrl()
        }
    }

    @Test
    fun deviceApiProtocolUsesHttpForBrowserTransport() {

        assertEquals(
            URLProtocol.HTTP,
            deviceApiProtocol(
                browserHttpTransport = true,
                plainHttpTransport = false,
            )
        )
    }

    @Test
    fun deviceApiProtocolUsesHttpsForNativeDefaultTransport() {

        assertEquals(
            URLProtocol.HTTPS,
            deviceApiProtocol(
                browserHttpTransport = false,
                plainHttpTransport = false,
            )
        )
    }
}
