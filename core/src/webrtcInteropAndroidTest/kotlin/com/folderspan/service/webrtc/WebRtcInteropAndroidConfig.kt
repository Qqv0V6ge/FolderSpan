package com.folderspan.service.webrtc

import androidx.test.platform.app.InstrumentationRegistry

internal fun interopBridgeUrl(): String =
    checkNotNull(InstrumentationRegistry.getArguments().getString("webrtcInteropUrl"))

internal fun interopConfigJson(): String {
    configureInteropTlsTrust()
    return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        .resolve("webrtc-interop-config.json").takeIf { it.exists() }?.readText()
        ?: """{"expectedPath":"Direct","expectedProtocol":"Udp"}"""
}

internal fun interopHasLocalIceStats(): Boolean = true
