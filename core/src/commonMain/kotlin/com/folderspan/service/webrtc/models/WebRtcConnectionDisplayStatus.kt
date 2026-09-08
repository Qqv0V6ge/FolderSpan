package com.folderspan.service.webrtc.models

import strings.AppStrings
enum class WebRtcPeerSessionDisplayStatus {
    Idle,
    WaitingForApproval,
    IceNegotiating,
    EstablishingDataChannel,
    Connected,
    Disconnected,
    Error
}

fun resolveWebRtcPeerSessionDisplayStatus(
    status: WebRtcConnectionStatus,
    isAwaitingApproval: Boolean,
    peerConnectionState: WebRtcPeerConnectionState?,
    hasPrimaryChannel: Boolean
): WebRtcPeerSessionDisplayStatus {
    return when {
        status == WebRtcConnectionStatus.Connected && hasPrimaryChannel -> {
            WebRtcPeerSessionDisplayStatus.Connected
        }

        isAwaitingApproval -> WebRtcPeerSessionDisplayStatus.WaitingForApproval

        status == WebRtcConnectionStatus.Connecting &&
            peerConnectionState == WebRtcPeerConnectionState.Connected &&
            !hasPrimaryChannel -> {
            WebRtcPeerSessionDisplayStatus.EstablishingDataChannel
        }

        status == WebRtcConnectionStatus.Connecting -> WebRtcPeerSessionDisplayStatus.IceNegotiating
        status == WebRtcConnectionStatus.Disconnected -> WebRtcPeerSessionDisplayStatus.Disconnected
        status == WebRtcConnectionStatus.Error -> WebRtcPeerSessionDisplayStatus.Error
        else -> WebRtcPeerSessionDisplayStatus.Idle
    }
}

fun WebRtcPeerSessionDisplayStatus.toDisplayLabel(): String {
    return when (this) {
        WebRtcPeerSessionDisplayStatus.Idle -> AppStrings.ui_not_connected
        WebRtcPeerSessionDisplayStatus.WaitingForApproval -> AppStrings.webrtc_waiting_for_peer_approval
        WebRtcPeerSessionDisplayStatus.IceNegotiating -> AppStrings.webrtc_ice_negotiating
        WebRtcPeerSessionDisplayStatus.EstablishingDataChannel -> AppStrings.webrtc_establishing_data_channel
        WebRtcPeerSessionDisplayStatus.Connected -> AppStrings.ui_connected
        WebRtcPeerSessionDisplayStatus.Disconnected -> AppStrings.webrtc_disconnected
        WebRtcPeerSessionDisplayStatus.Error -> AppStrings.ui_error
    }
}

fun WebRtcConnectionStatus.toRoomDisplayLabel(): String {
    return when (this) {
        WebRtcConnectionStatus.Idle -> AppStrings.webrtc_not_in_room
        WebRtcConnectionStatus.Connecting -> AppStrings.webrtc_waiting_to_join_room
        WebRtcConnectionStatus.Connected -> AppStrings.ui_connected
        WebRtcConnectionStatus.Disconnected -> AppStrings.webrtc_left_room
        WebRtcConnectionStatus.Error -> AppStrings.ui_error
    }
}
