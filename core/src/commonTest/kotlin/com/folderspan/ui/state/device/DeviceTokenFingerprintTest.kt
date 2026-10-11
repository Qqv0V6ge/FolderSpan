package com.folderspan.ui.state.device

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTokenFingerprintTest {
    @Test
    fun rejectsUserAgentDifferences() {
        val expected = DeviceTokenFingerprint(
            deviceId = "device-1",
            clientIp = "10.0.0.12",
            userAgent = "Ktor client"
        )
        val actual = DeviceTokenFingerprint(
            deviceId = "device-1",
            clientIp = "10.0.0.12",
            userAgent = "okhttp/5"
        )

        assertFalse(expected.matches(actual))
    }

    @Test
    fun matchesIpv4MappedIpv6Address() {
        val expected = DeviceTokenFingerprint(
            deviceId = "device-1",
            clientIp = "::ffff:10.0.0.12",
            userAgent = "ktor"
        )
        val actual = DeviceTokenFingerprint(
            deviceId = "device-1",
            clientIp = "10.0.0.12",
            userAgent = "ktor"
        )

        assertTrue(expected.matches(actual))
    }

    @Test
    fun rejectsDifferentDeviceOrIp() {
        val expected = DeviceTokenFingerprint(
            deviceId = "device-1",
            clientIp = "10.0.0.12",
            userAgent = "ktor"
        )

        assertFalse(expected.matches(DeviceTokenFingerprint("device-2", "10.0.0.12", "ktor")))
        assertFalse(expected.matches(DeviceTokenFingerprint("device-1", "10.0.0.13", "ktor")))
        assertFalse(expected.matches(DeviceTokenFingerprint("device-1", "10.0.0.12", null)))
    }
}
