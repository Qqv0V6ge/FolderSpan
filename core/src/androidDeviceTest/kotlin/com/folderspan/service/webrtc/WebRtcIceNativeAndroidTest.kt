package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import kotlin.test.Test

class WebRtcIceNativeAndroidTest {
    @Test
    fun realSelectedPairAndRestartPreserveReliableDataChannel() = runSuspendTest {
        verifyRealWebRtcIcePathAndRestart()
    }
}
