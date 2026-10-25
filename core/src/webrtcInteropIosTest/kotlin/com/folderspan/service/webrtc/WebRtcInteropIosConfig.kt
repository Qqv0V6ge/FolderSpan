@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.webrtc

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal fun interopBridgeUrl(): String =
    checkNotNull(getenv("FOLDERSPAN_WEBRTC_INTEROP_URL")?.toKString())

internal fun interopConfigJson(): String =
    getenv("FOLDERSPAN_WEBRTC_INTEROP_CONFIG")?.toKString()
        ?: """{"expectedPath":"Direct","expectedProtocol":"Udp"}"""

internal fun interopHasLocalIceStats(): Boolean = true
