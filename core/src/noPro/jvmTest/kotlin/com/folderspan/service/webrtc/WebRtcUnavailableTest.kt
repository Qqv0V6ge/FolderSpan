package com.folderspan.service.webrtc

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.session.usesTriggeredDeviceDiscovery
import com.folderspan.service.webrtc.controller.MultiPeerWebRtcController
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcUnavailableTest {
    @Test
    fun ordinaryBuildRejectsWebRtcConnectionsAndSkipsBrowserDiscovery() = runTest {
        assertFalse(DeviceType.JS.usesTriggeredDeviceDiscovery())

        val controller = MultiPeerWebRtcController(this)
        controller.requestPeerConnection("remote-device")

        assertEquals(WebRtcConnectionStatus.Error, controller.connectionStatus.value)
        assertEquals(AppStrings.webrtc_pro_only, controller.lastError.value)
        assertTrue(controller.peerSessionStates.value.isEmpty())
    }
}
