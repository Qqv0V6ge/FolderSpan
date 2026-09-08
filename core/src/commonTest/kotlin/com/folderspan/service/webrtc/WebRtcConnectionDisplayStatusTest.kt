package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.WebRtcPeerConnectionState
import com.folderspan.service.webrtc.models.WebRtcPeerSessionDisplayStatus
import com.folderspan.service.webrtc.models.resolveWebRtcPeerSessionDisplayStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcConnectionDisplayStatusTest {
    @Test
    fun waitingApprovalTakesPrecedenceOverGenericConnecting() {
        assertEquals(
            WebRtcPeerSessionDisplayStatus.WaitingForApproval,
            resolveWebRtcPeerSessionDisplayStatus(
                status = WebRtcConnectionStatus.Connecting,
                isAwaitingApproval = true,
                peerConnectionState = null,
                hasPrimaryChannel = false
            )
        )
    }

    @Test
    fun connectedPeerConnectionWithoutChannelMapsToEstablishingDataChannel() {
        assertEquals(
            WebRtcPeerSessionDisplayStatus.EstablishingDataChannel,
            resolveWebRtcPeerSessionDisplayStatus(
                status = WebRtcConnectionStatus.Connecting,
                isAwaitingApproval = false,
                peerConnectionState = WebRtcPeerConnectionState.Connected,
                hasPrimaryChannel = false
            )
        )
    }

    @Test
    fun genericConnectingMapsToIceNegotiating() {
        assertEquals(
            WebRtcPeerSessionDisplayStatus.IceNegotiating,
            resolveWebRtcPeerSessionDisplayStatus(
                status = WebRtcConnectionStatus.Connecting,
                isAwaitingApproval = false,
                peerConnectionState = WebRtcPeerConnectionState.Connecting,
                hasPrimaryChannel = false
            )
        )
    }
}
