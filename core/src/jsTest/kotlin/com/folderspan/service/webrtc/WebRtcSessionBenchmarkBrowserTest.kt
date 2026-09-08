package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import kotlin.test.Test

class WebRtcSessionBenchmarkBrowserTest {
    @Test
    fun sampleThroughputBufferedAmountAndJsHeap() = runSuspendTest {
        measureRealWebRtcSession { (js("performance.memory.usedJSHeapSize") as Double).toLong() }
    }
}
