package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import kotlin.test.Test

class WebRtcSessionBenchmarkAndroidTest {
    @Test
    fun sampleThroughputBufferedAmountAndHeap() = runSuspendTest {
        measureRealWebRtcSession {
            val runtime = Runtime.getRuntime()
            runtime.totalMemory() - runtime.freeMemory()
        }
    }
}
