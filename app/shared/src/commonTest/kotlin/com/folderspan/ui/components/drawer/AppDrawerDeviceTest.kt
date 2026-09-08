package com.folderspan.ui.components.drawer

import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDrawerDeviceTest {

    @Test
    fun appDrawerDeviceNetworkActionsVisible_returnsFalseWhenNoIpAddressesAvailable() {
        assertFalse(appDrawerDeviceNetworkActionsVisible(DeviceType.JS, emptyList()))
    }

    @Test
    fun appDrawerDeviceNetworkActionsVisible_returnsFalseForNativeContinuousDiscovery() {
        assertFalse(appDrawerDeviceNetworkActionsVisible(DeviceType.JVM, listOf("192.168.1.8")))
    }

    @Test
    fun appDrawerDeviceNetworkActionsVisible_returnsTrueForBrowserWithIpAddresses() {
        assertTrue(appDrawerDeviceNetworkActionsVisible(DeviceType.JS, listOf("192.168.1.8")))
    }

    @Test
    fun deviceAddressSuggestionsShowAllIpv4Subnets() {
        assertEquals(
            listOf("192.168.1.", "10.0.0."),
            appDrawerDeviceAddressInputPrefixes(
                listOf("192.168.1.8", "[2001:db8::1]", "10.0.0.4", "192.168.1.20")
            )
        )
    }

    @Test
    fun sessionDeskWithEmptyHostStillHighlightsSelectedDevice() {
        val socketDevice = SocketDevice(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            connectType = ConnectType.Connect,
            transportType = DeviceTransportType.Session,
        )
        val desk = Device(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = mutableMapOf(),
            type = DeviceType.JVM,
            token = "token",
        )

        assertTrue(isSelectedDevice(desk, socketDevice))
    }

    @Test
    fun sessionDeskWithFileClientStillHighlightsSelectedDevice() {
        val socketDevice = SocketDevice(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            connectType = ConnectType.Connect,
            transportType = DeviceTransportType.Session,
        )
        val desk = Device(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = mutableMapOf(),
            type = DeviceType.JVM,
            token = "token",
            transportType = DeviceTransportType.Session,
        )

        assertTrue(isSelectedDevice(desk, socketDevice))
    }

    @Test
    fun webRtcDeskHighlightsMatchingWebRtcDeviceOnly() {
        val sessionDevice = SocketDevice(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            connectType = ConnectType.Connect,
            transportType = DeviceTransportType.Session,
        )
        val webRtcDevice = sessionDevice.withCopy(transportType = DeviceTransportType.WebRtc)
        val desk = Device(
            id = "device-1",
            name = "Linux",
            pathSeparator = "/",
            host = mutableMapOf(),
            type = DeviceType.JVM,
            token = "token",
            transportType = DeviceTransportType.WebRtc,
        )

        assertFalse(isSelectedDevice(desk, sessionDevice))
        assertTrue(isSelectedDevice(desk, webRtcDevice))
    }
}
