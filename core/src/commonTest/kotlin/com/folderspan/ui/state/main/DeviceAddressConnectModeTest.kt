package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAddressConnectModeTest {
    @Test
    fun browserAddressConnectUsesWebRtcInsteadOfPing() {
        assertEquals(
            DeviceAddressConnectMode.BrowserWebRtc,
            resolveDeviceAddressConnectMode(DeviceType.JS),
        )
    }

    @Test
    fun nativeAddressConnectUsesSessionPort() {
        assertEquals(DeviceAddressConnectMode.SessionPort, resolveDeviceAddressConnectMode(DeviceType.Android))
        assertEquals(DeviceAddressConnectMode.SessionPort, resolveDeviceAddressConnectMode(DeviceType.IOS))
        assertEquals(DeviceAddressConnectMode.SessionPort, resolveDeviceAddressConnectMode(DeviceType.JVM))
    }

    @Test
    fun nativeBrowserWebRtcHostDoesNotRequireBrowserSecureContext() {
        assertTrue(
            canUseBrowserWebRtcSignalingBaseUrl(
                baseUrl = "http://127.0.0.1:12040",
                platformType = DeviceType.JVM,
                browserSecureContext = false,
            )
        )
    }

    @Test
    fun jsBrowserWebRtcStillRequiresBrowserSecureContext() {
        assertFalse(
            canUseBrowserWebRtcSignalingBaseUrl(
                baseUrl = "http://127.0.0.1:12040",
                platformType = DeviceType.JS,
                browserSecureContext = false,
            )
        )
        assertTrue(
            canUseBrowserWebRtcSignalingBaseUrl(
                baseUrl = "http://127.0.0.1:12040",
                platformType = DeviceType.JS,
                browserSecureContext = true,
            )
        )
    }
}
