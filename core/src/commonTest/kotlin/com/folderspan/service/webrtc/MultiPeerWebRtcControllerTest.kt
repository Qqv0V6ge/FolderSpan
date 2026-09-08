package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.controller.core.chooseTransferTargetPeerId
import com.folderspan.service.webrtc.controller.core.resolveHttpSignalingOpenedRoomState
import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultiPeerWebRtcControllerTest {
    @Test
    fun connectedPeerWinsOverAvailableSelection() {
        assertEquals(
            "connected-peer",
            chooseTransferTargetPeerId(
                selectedPeerId = "available-peer",
                preferredPeerId = null,
                connectedPeerIds = setOf("connected-peer"),
                availablePeerIds = setOf("available-peer", "connected-peer"),
            ),
        )
    }

    @Test
    fun existingConnectedSelectionIsStable() {
        assertEquals(
            "peer-b",
            chooseTransferTargetPeerId(
                selectedPeerId = "peer-b",
                preferredPeerId = "peer-a",
                connectedPeerIds = setOf("peer-a", "peer-b"),
                availablePeerIds = setOf("peer-a", "peer-b"),
            ),
        )
    }

    @Test
    fun httpSignalingPublishesOnlyCurrentRoom() {
        val config = WebRtcConfig(
            wssUrl = "http://127.0.0.1:8080",
            roomId = "browser-room",
            iceServers = emptyList(),
            unreliableMode = false,
        )
        val stale = config.copy(roomId = "stale-room")

        val state = resolveHttpSignalingOpenedRoomState(config, config)

        assertEquals(WebRtcConnectionStatus.Connected, state?.connectionStatus)
        assertEquals(config, state?.activeRoomConfig)
        assertNull(resolveHttpSignalingOpenedRoomState(null, config))
        assertNull(resolveHttpSignalingOpenedRoomState(stale, config))
    }
}
