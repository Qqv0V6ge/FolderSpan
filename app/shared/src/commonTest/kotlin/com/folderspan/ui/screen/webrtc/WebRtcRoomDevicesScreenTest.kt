package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import androidx.compose.ui.unit.dp
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcPeerSessionDisplayStatus
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcRoomDevicesScreenTest : ChineseLocalizationTest() {

    @Test
    fun busyProgressUsesIconButtonActionSlotSize() {
        assertEquals(48.dp, WebRtcRoomDeviceActionSlotSize)
    }

    @Test
    fun busyProgressActionDisconnectsPeer() {
        assertEquals(AppStrings.ui_disconnect, WebRtcRoomPeerDisconnectContentDescription)
    }

    @Test
    fun connectedItemClickDisconnectsPeer() {
        assertEquals(
            WebRtcRoomPeerItemClickAction.Disconnect,
            resolveWebRtcRoomPeerItemClickAction(
                status = WebRtcConnectionStatus.Connected,
                displayStatus = WebRtcPeerSessionDisplayStatus.Connected
            )
        )
    }

    @Test
    fun busyItemClickDisconnectsPeer() {
        assertEquals(
            WebRtcRoomPeerItemClickAction.Disconnect,
            resolveWebRtcRoomPeerItemClickAction(
                status = WebRtcConnectionStatus.Connecting,
                displayStatus = WebRtcPeerSessionDisplayStatus.IceNegotiating
            )
        )
    }

    @Test
    fun idleItemClickConnectsPeer() {
        assertEquals(
            WebRtcRoomPeerItemClickAction.Connect,
            resolveWebRtcRoomPeerItemClickAction(
                status = WebRtcConnectionStatus.Idle,
                displayStatus = WebRtcPeerSessionDisplayStatus.Idle
            )
        )
    }
}
