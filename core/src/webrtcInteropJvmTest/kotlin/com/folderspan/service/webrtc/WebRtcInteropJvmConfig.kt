package com.folderspan.service.webrtc

internal fun interopBridgeUrl(): String =
    checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_URL"))

internal fun interopConfigJson(): String =
    System.getenv("FOLDERSPAN_WEBRTC_INTEROP_CONFIG") ?: "{}"

internal fun interopHasLocalIceStats(): Boolean = true
