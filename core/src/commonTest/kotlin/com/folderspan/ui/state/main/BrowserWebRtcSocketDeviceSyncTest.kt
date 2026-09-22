package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.session.DeviceSessionClientManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserWebRtcSocketDeviceSyncTest {
    @Test
    fun unverifiedBeaconIsVisibleButCannotReplaceVerifiedOrPendingIdentity() {
        val incoming = SocketDevice(
            id = "peer", name = "Unverified", pathSeparator = "/", host = "10.0.0.122",
            type = DeviceType.IOS, discoveryStatus = DeviceDiscoveryStatus.Unverified,
        )
        val devices = mutableListOf<SocketDevice>()
        upsertDiscoveredSocketDevice(devices, incoming, false)
        assertEquals(incoming, devices.single())
        listOf(
            incoming.withCopy(name = "Verified", discoveryStatus = DeviceDiscoveryStatus.Verified),
            incoming.withCopy(name = "Trusted", discoveryStatus = DeviceDiscoveryStatus.Trusted),
            incoming.withCopy(name = "Pending", connectType = ConnectType.Loading),
        ).forEach { existing ->
            devices[0] = existing
            upsertDiscoveredSocketDevice(devices, incoming.withCopy(host = "10.0.0.99"), false)
            assertEquals(existing, devices.single())
        }
    }

    @Test
    fun browserDiscoveredHttpDeviceIsStoredAsWebRtcOnly() {
        val devices = mutableListOf<SocketDevice>()
        val discovered = SocketDevice(
            id = "device-1",
            name = "Desktop",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            transportType = DeviceTransportType.Session,
        )

        val stored = upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = true,
        )

        assertEquals(DeviceTransportType.WebRtc, stored.transportType)
        assertEquals(listOf(DeviceTransportType.WebRtc), devices.map { item -> item.transportType })
    }

    @Test
    fun browserDiscoveredHttpDeviceReplacesExistingHttpRecord() {
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                transportType = DeviceTransportType.Session,
            )
        )
        val discovered = devices.first().withCopy(name = "Desktop Updated")

        upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = true,
        )

        assertEquals(1, devices.size)
        assertEquals(DeviceTransportType.WebRtc, devices.single().transportType)
        assertEquals("Desktop Updated", devices.single().name)
    }

    @Test
    fun nativeDiscoveredHttpDeviceStaysHttp() {
        val devices = mutableListOf<SocketDevice>()
        val discovered = SocketDevice(
            id = "device-1",
            name = "Desktop",
            pathSeparator = "/",
            host = "192.168.1.20",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            transportType = DeviceTransportType.Session,
        )

        val stored = upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = false,
        )

        assertEquals(DeviceTransportType.Session, stored.transportType)
        assertEquals(listOf(DeviceTransportType.Session), devices.map { item -> item.transportType })
    }

    @Test
    fun nativeRediscoveryKeepsConnectedSessionClient() {
        val sessionClient = DeviceSessionClientManager()
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                connectType = ConnectType.Connect,
                transportType = DeviceTransportType.Session,
            ).apply { this.sessionClient = sessionClient }
        )
        val discovered = SocketDevice(
            id = "device-1",
            name = "Desktop Updated",
            pathSeparator = "/",
            host = "192.168.1.21",
            port = 12040,
            httpsPort = 12040,
            type = DeviceType.JVM,
            connectType = ConnectType.New,
            transportType = DeviceTransportType.Session,
        )

        upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = false,
        )

        val stored = devices.single()
        assertEquals(ConnectType.Connect, stored.connectType)
        assertEquals("192.168.1.20", stored.host)
        assertEquals(sessionClient, stored.sessionClient)
        assertEquals("Desktop Updated", stored.name)
    }

    @Test
    fun nativeRediscoveryKeepsPendingSessionConnectionLoading() {
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                connectType = ConnectType.Loading,
                transportType = DeviceTransportType.Session,
            )
        )
        val discovered = devices.first().withCopy(
            name = "Desktop Updated",
            host = "192.168.1.21",
            connectType = ConnectType.UnConnect,
        )

        upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = false,
        )

        val stored = devices.single()
        assertEquals(ConnectType.Loading, stored.connectType)
        assertEquals("192.168.1.20", stored.host)
        assertEquals("Desktop Updated", stored.name)
    }

    @Test
    fun nativeRediscoveryRestoresConnectWhenSessionClientSurvivesStaleNewBadge() {
        val sessionClient = DeviceSessionClientManager()
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                connectType = ConnectType.New,
                transportType = DeviceTransportType.Session,
            ).apply { this.sessionClient = sessionClient }
        )
        val discovered = devices.first().withCopy(connectType = ConnectType.New, name = "Desktop")

        upsertDiscoveredSocketDevice(
            socketDevices = devices,
            device = discovered,
            browserWebRtcTransport = false,
        )

        val stored = devices.single()
        assertEquals(ConnectType.Connect, stored.connectType)
        assertEquals(sessionClient, stored.sessionClient)
    }

    @Test
    fun browserWebRtcSyncRemovesSameDeviceHttpEntry() {
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                transportType = DeviceTransportType.Session,
            ),
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                type = DeviceType.JVM,
                connectType = ConnectType.Connect,
                transportType = DeviceTransportType.WebRtc,
            ),
        )

        removeHttpDevicesForBrowserWebRtcPeers(
            socketDevices = devices,
            peerIds = setOf("device-1"),
            browserWebRtcTransport = true,
        )

        assertEquals(1, devices.size)
        assertTrue(devices.all { item -> item.transportType == DeviceTransportType.WebRtc })
    }

    @Test
    fun nativeWebRtcSyncKeepsSameDeviceHttpEntry() {
        val devices = mutableListOf(
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                transportType = DeviceTransportType.Session,
            ),
            SocketDevice(
                id = "device-1",
                name = "Desktop",
                pathSeparator = "/",
                host = "192.168.1.20",
                port = 12040,
                type = DeviceType.JVM,
                connectType = ConnectType.Connect,
                transportType = DeviceTransportType.WebRtc,
            ),
        )

        removeHttpDevicesForBrowserWebRtcPeers(
            socketDevices = devices,
            peerIds = setOf("device-1"),
            browserWebRtcTransport = false,
        )

        assertEquals(
            listOf(DeviceTransportType.Session, DeviceTransportType.WebRtc),
            devices.map { item -> item.transportType }
        )
    }

    @Test
    fun browserRescanRemovesOnlyUnavailableInactiveWebRtcDevices() {
        fun webRtcDevice(id: String, host: String, connectType: ConnectType) = SocketDevice(
            id = id,
            name = id,
            pathSeparator = "/",
            host = host,
            port = 12040,
            type = DeviceType.JVM,
            connectType = connectType,
            transportType = DeviceTransportType.WebRtc,
        )

        val devices = mutableListOf(
            webRtcDevice("unavailable", "192.168.1.20", ConnectType.UnConnect),
            webRtcDevice("reachable", "192.168.1.21", ConnectType.UnConnect),
            webRtcDevice("connected", "192.168.1.22", ConnectType.Connect),
            webRtcDevice("not-probed", "192.168.2.20", ConnectType.UnConnect),
            SocketDevice(
                id = "session",
                name = "session",
                pathSeparator = "/",
                host = "192.168.1.23",
                type = DeviceType.JVM,
                transportType = DeviceTransportType.Session,
            ),
        )

        val removedIds = removeUnavailableBrowserWebRtcDevices(
            socketDevices = devices,
            probedHosts = setOf("192.168.1.20", "192.168.1.21", "192.168.1.22", "192.168.1.23"),
            reachableHosts = setOf("192.168.1.21"),
        )

        assertEquals(setOf("unavailable"), removedIds)
        assertEquals(
            listOf("reachable", "connected", "not-probed", "session"),
            devices.map { device -> device.id },
        )
    }
}
