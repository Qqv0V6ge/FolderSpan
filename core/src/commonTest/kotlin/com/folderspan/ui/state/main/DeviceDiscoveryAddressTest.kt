package com.folderspan.ui.state.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceDiscoveryAddressTest {
    @Test
    fun benchmarkAndInvalidAddressesAreNotScanSources() {
        assertFalse(isDiscoveryScanSourceAddress("198.18.0.1"))
        assertFalse(isDiscoveryScanSourceAddress("198.19.255.254"))
        assertFalse(isDiscoveryScanSourceAddress("invalid"))
        assertFalse(isDiscoveryScanSourceAddress("10.0.0.999"))
    }

    @Test
    fun lanAndOverlayAddressesRemainScanSources() {
        assertTrue(isDiscoveryScanSourceAddress("10.0.0.122"))
        assertTrue(isDiscoveryScanSourceAddress("172.16.1.5"))
        assertTrue(isDiscoveryScanSourceAddress("192.168.1.10"))
        assertTrue(isDiscoveryScanSourceAddress("100.64.0.2"))
    }
}
