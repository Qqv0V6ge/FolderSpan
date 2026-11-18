@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.folderspan.service.webrtc.models

import com.shepeliev.webrtckmp.DataChannel
import com.shepeliev.webrtckmp.externals.RTCDataChannel

// The pinned binding casts RTCErrorEvent to ErrorEvent. Let the native close event release the session.
internal actual fun installDataChannelErrorHandler(channel: DataChannel) {
    closeChannelOnError(channel.js)
}

@JsFun("(channel) => { channel.onerror = () => channel.close(); }")
private external fun closeChannelOnError(channel: RTCDataChannel)
