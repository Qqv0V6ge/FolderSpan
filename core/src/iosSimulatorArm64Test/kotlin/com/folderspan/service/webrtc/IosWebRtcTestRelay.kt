@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.WebRtcIceServerConfig
import com.folderspan.test.runSuspendTest
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal fun runIosWebRtcTest(block: suspend () -> Unit) = runSuspendTest {
    val previous = realWebRtcTestIceServers
    val relay = getenv("FOLDERSPAN_TEST_TURN_URL")?.toKString()
    try {
        if (!relay.isNullOrBlank()) {
            realWebRtcTestIceServers = listOf(WebRtcIceServerConfig(
                urls = listOf(relay),
                username = "webrtc-test",
                password = "local-test-only",
            ))
        }
        block()
    } finally {
        realWebRtcTestIceServers = previous
    }
}
