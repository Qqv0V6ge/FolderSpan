package com.folderspan.service.webrtc

internal fun interopBridgeUrl(): String = js("globalThis.__karma__.config.webrtcInteropUrl") as String
internal fun interopConfigJson(): String = js("globalThis.__karma__.config.webrtcInteropConfig") as String
internal fun interopHasLocalIceStats(): Boolean = false
