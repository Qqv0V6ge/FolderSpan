package com.folderspan

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformAndroidTest {
    @Test
    fun fallbackAddressPrefersWifiRegardlessOfEnumerationOrder() {
        val address = selectAndroidFallbackIpv4Address(
            listOf(
                candidate("eth0", "10.0.2.15"),
                candidate("wlan0", "10.0.2.17"),
            )
        )

        assertEquals("10.0.2.17", address?.hostAddress)
    }

    @Test
    fun fallbackAddressKeepsEthernetAndCellularCandidates() {
        val ethernetAddress = selectAndroidFallbackIpv4Address(
            listOf(
                candidate("rmnet_data0", "100.64.0.2"),
                candidate("eth0", "192.168.1.20"),
            )
        )
        val cellularAddress = selectAndroidFallbackIpv4Address(
            listOf(candidate("rmnet_data0", "100.64.0.2"))
        )

        assertEquals("192.168.1.20", ethernetAddress?.hostAddress)
        assertEquals("100.64.0.2", cellularAddress?.hostAddress)
    }

    @Test
    fun fallbackAddressSelectionIsStableWithinInterfaceType() {
        val address = selectAndroidFallbackIpv4Address(
            listOf(
                candidate("wlan1", "192.168.1.30"),
                candidate("wlan0", "192.168.1.20"),
                candidate("wlan0", "192.168.1.10"),
            )
        )

        assertEquals("192.168.1.10", address?.hostAddress)
    }

    private fun candidate(interfaceName: String, address: String): AndroidIpv4Candidate {
        return AndroidIpv4Candidate(
            interfaceName = interfaceName,
            address = InetAddress.getByName(address) as Inet4Address,
        )
    }
}
