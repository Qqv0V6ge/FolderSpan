package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import com.folderspan.data.main.webrtc.WebRtcRoomSource

internal fun webRtcRoomListSupportText(
    source: WebRtcRoomSource,
    wssUrl: String,
    lastError: String?,
): String {
    return buildString {
        if (webRtcRoomShowsConnectionFields(source) && wssUrl.isNotBlank()) {
            append(wssUrl)
        }
        if (!lastError.isNullOrBlank()) {
            if (isNotEmpty()) {
                append('\n')
            }
            append(AppStrings.webrtc_error_prefix)
            append(lastError)
        }
    }
}

internal fun webRtcRoomConnectConfirmMessage(
    name: String,
    wssUrl: String,
    roomId: String,
    source: WebRtcRoomSource,
): String {
    return buildString {
        append(AppStrings.ui_whether_connect)
        append(name)
        append("”？")
        append('\n')
        append('\n')
        if (webRtcRoomShowsConnectionFields(source)) {
            append(AppStrings.ui_signaling)
            append(wssUrl)
            append('\n')
        }
        append("Room ID：")
        append(roomId)
        append('\n')
        append(AppStrings.webrtc_source_prefix)
        append(source.label)
    }
}

internal fun webRtcRoomDrawerSubtitle(
    source: WebRtcRoomSource,
    wssUrl: String,
): String = if (webRtcRoomShowsConnectionFields(source)) wssUrl else ""
