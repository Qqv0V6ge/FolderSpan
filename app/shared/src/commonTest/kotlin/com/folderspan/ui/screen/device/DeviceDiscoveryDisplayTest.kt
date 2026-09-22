package com.folderspan.ui.screen.device

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.SocketDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDiscoveryDisplayTest {
    @Test
    fun unverifiedDeviceIsVisibleWithoutPersistedMetadataOrAccessPolicies() {
        val device = SocketDevice(
            id = "peer", name = "Unverified MacBook", pathSeparator = "/", type = DeviceType.JVM,
            connectType = ConnectType.UnConnect, discoveryStatus = DeviceDiscoveryStatus.Unverified,
        )
        val items = buildDeviceDisplayItems(
            devices = emptyList(), serverAccessById = emptyMap(), clientAccessDevices = emptyList(),
            socketDevices = listOf(device), incomingConnectedDevices = emptyList(),
            searchQuery = "macbook", deviceType = DeviceType.JVM,
        )
        val item = items.single()
        assertEquals(device.name, item.name)
        assertEquals(listOf(device), item.outgoingConnections)
        assertNull(item.device)
        assertFalse(item.isManageable)
        assertTrue(item.appearsInDrawer)
    }
}
