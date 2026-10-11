package com.folderspan.service.http.server

import com.folderspan.service.http.IpAddressInfo
import com.folderspan.service.http.NetworkInterfaceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvertisedHttpHostTest {
    @Test
    fun advertisedHostReplacesLoopbackWithReachableInterface() {
        val host = selectAdvertisedHttpHost(
            currentHost = "127.0.0.1",
            targetHost = "192.168.50.9",
            candidateHosts = listOf("10.0.0.5", "192.168.50.10"),
        )

        assertEquals("192.168.50.10", host)
    }

    @Test
    fun advertisedHostFallsBackToAnyReachableInterfaceWhenNoSubnetMatch() {
        val host = selectAdvertisedHttpHost(
            currentHost = "localhost",
            targetHost = "192.168.50.9",
            candidateHosts = listOf("10.0.0.5"),
        )

        assertEquals("10.0.0.5", host)
    }

    @Test
    fun advertisedHostUsesTargetSubnetInsteadOfOtherPhysicalNetwork() {
        val host = selectAdvertisedHttpHost(
            currentHost = "192.168.50.10",
            targetHost = "10.0.2.17",
            candidateHosts = listOf("192.168.50.10", "10.0.2.16"),
        )

        assertEquals("10.0.2.16", host)
    }

    @Test
    fun discoveredHostKeepsAdvertisedHostWhenRemoteHostIsLoopback() {
        val host = resolveDiscoveredHttpDeviceHost(
            advertisedHost = "192.168.1.11",
            remoteHost = "127.0.0.1",
        )

        assertEquals("192.168.1.11", host)
    }

    @Test
    fun discoveredHostPrefersNonLoopbackRemoteHost() {
        val host = resolveDiscoveredHttpDeviceHost(
            advertisedHost = "127.0.0.1",
            remoteHost = "192.168.1.11",
        )

        assertEquals("192.168.1.11", host)
    }

    @Test
    fun preferredHostIgnoresVirtualInterfaceWhenPhysicalInterfaceExists() {
        val host = selectPreferredAdvertisedIpv4Host(
            currentHost = "172.17.0.1",
            interfaces = listOf(
                networkInterface("docker0", "172.17.0.1"),
                networkInterface("wlan0", "192.168.50.10"),
            ),
        )

        assertEquals("192.168.50.10", host)
    }

    @Test
    fun preferredHostSelectionIsStableAcrossInterfaceEnumerationOrder() {
        val firstOrder = listOf(
            networkInterface("pdp_ip0", "100.64.0.2"),
            networkInterface("en0", "192.168.1.20"),
            networkInterface("utun0", "10.8.0.2"),
        )
        val secondOrder = firstOrder.reversed()

        assertEquals(
            selectPreferredAdvertisedIpv4Host("127.0.0.1", interfaces = firstOrder),
            selectPreferredAdvertisedIpv4Host("127.0.0.1", interfaces = secondOrder),
        )
        assertEquals(
            "192.168.1.20",
            selectPreferredAdvertisedIpv4Host("127.0.0.1", interfaces = firstOrder),
        )
    }

    @Test
    fun preferredHostKeepsPhysicalSystemSelectedAddress() {
        val host = selectPreferredAdvertisedIpv4Host(
            currentHost = "192.168.1.20",
            interfaces = listOf(
                networkInterface("wlan0", "192.168.50.10"),
                networkInterface("eth0", "192.168.1.20"),
            ),
        )

        assertEquals("192.168.1.20", host)
    }

    @Test
    fun preferredHostKeepsTargetSubnetSemanticsForVirtualRoute() {
        val host = selectPreferredAdvertisedIpv4Host(
            currentHost = "192.168.50.10",
            targetHost = "10.8.0.8",
            interfaces = listOf(
                networkInterface("wlan0", "192.168.50.10"),
                networkInterface("utun0", "10.8.0.2"),
            ),
        )

        assertEquals("10.8.0.2", host)
    }

    @Test
    fun preferredHostUsesVirtualUnicastBeforePhysicalLinkLocalAddress() {
        val host = selectPreferredAdvertisedIpv4Host(
            currentHost = "169.254.10.20",
            interfaces = listOf(
                networkInterface("en0", "169.254.10.20"),
                networkInterface("utun0", "10.8.0.2"),
            ),
        )

        assertEquals("10.8.0.2", host)
    }

    private fun networkInterface(
        name: String,
        address: String,
        displayName: String = name,
    ): NetworkInterfaceInfo {
        return NetworkInterfaceInfo(
            name = name,
            displayName = displayName,
            isUp = true,
            isLoopback = false,
            addresses = listOf(IpAddressInfo(address, isIPv6 = false)),
        )
    }
}
