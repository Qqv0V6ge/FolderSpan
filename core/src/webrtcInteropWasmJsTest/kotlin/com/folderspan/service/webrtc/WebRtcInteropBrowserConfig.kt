@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.folderspan.service.webrtc

internal fun interopHasLocalIceStats(): Boolean = false

internal fun interopBridgeUrl(): String = bridgeUrlJs()
internal fun interopConfigJson(): String = configJsonJs()

@JsFun("() => globalThis.__karma__.config.webrtcInteropUrl")
private external fun bridgeUrlJs(): String

@JsFun("() => globalThis.__karma__.config.webrtcInteropConfig")
private external fun configJsonJs(): String
