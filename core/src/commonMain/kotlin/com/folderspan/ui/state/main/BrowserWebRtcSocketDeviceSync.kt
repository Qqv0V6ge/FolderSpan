package com.folderspan.ui.state.main

import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice

internal fun upsertDiscoveredSocketDevice(
    socketDevices: MutableList<SocketDevice>,
    device: SocketDevice,
    browserWebRtcTransport: Boolean,
): SocketDevice {
    val storedDevice = if (browserWebRtcTransport) {
        device.withCopy(
            connectType = device.connectType,
            transportType = DeviceTransportType.WebRtc,
            httpClient = null,
        )
    } else {
        device
    }
    if (browserWebRtcTransport) {
        socketDevices.removeAll {
            it.id == device.id && it.transportType == DeviceTransportType.Session
        }
    }
    val index = socketDevices.indexOfFirst { item -> item.matchesRecord(storedDevice) }
    if (index == -1) {
        socketDevices.add(storedDevice)
    } else {
        val existing = socketDevices[index]
        val keepConnectionState =
            !browserWebRtcTransport &&
                existing.transportType == DeviceTransportType.Session &&
                (existing.hasActiveConnection() || existing.connectType == ConnectType.Loading)
        socketDevices[index] = storedDevice.withCopy(
            connectType = if (keepConnectionState) {
                if (existing.connectType == ConnectType.Connect || existing.connectType == ConnectType.Loading) {
                    existing.connectType
                } else {
                    ConnectType.Connect
                }
            } else {
                storedDevice.connectType
            },
            host = if (keepConnectionState) existing.host else storedDevice.host,
            port = if (keepConnectionState) existing.port else storedDevice.port,
            httpsPort = if (keepConnectionState) existing.httpsPort else storedDevice.httpsPort,
            tlsFingerprintSha256 = if (keepConnectionState) existing.tlsFingerprintSha256 else storedDevice.tlsFingerprintSha256,
            httpClient = if (keepConnectionState) existing.httpClient else null,
            sessionClient = if (keepConnectionState) existing.sessionClient else null,
        )
    }
    return storedDevice
}

internal fun removeUnavailableBrowserWebRtcDevices(
    socketDevices: MutableList<SocketDevice>,
    probedHosts: Set<String>,
    reachableHosts: Set<String>,
): Set<String> {
    if (probedHosts.isEmpty()) return emptySet()
    val unavailableDevices = socketDevices.filter { device ->
        device.transportType == DeviceTransportType.WebRtc &&
            !device.hasActiveConnection() &&
            device.host in probedHosts &&
            device.host !in reachableHosts
    }
    val removedIds = unavailableDevices.mapTo(mutableSetOf()) { device -> device.id }
    socketDevices.removeAll(unavailableDevices.toSet())
    return removedIds
}

internal fun removeHttpDevicesForBrowserWebRtcPeers(
    socketDevices: MutableList<SocketDevice>,
    peerIds: Set<String>,
    browserWebRtcTransport: Boolean,
) {
    if (!browserWebRtcTransport || peerIds.isEmpty()) return
    socketDevices.removeAll { item ->
        item.transportType == DeviceTransportType.Session && item.id in peerIds
    }
}
