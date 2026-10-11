package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import kotlin.test.Test

class WebRtcSessionBenchmarkJvmTest {
    @Test
    fun sampleThroughputBufferedAmountAndHeap() = runSuspendTest {
        val runs = (System.getenv("FOLDERSPAN_WEBRTC_BENCHMARK_RUNS") ?: "1").toInt()
        require(runs in 1..20) { "Benchmark runs must be between 1 and 20" }
        repeat(runs) { iteration ->
            println("WEBRTC_BENCH_RUN iteration=$iteration")
            measureRealWebRtcSession {
                val runtime = Runtime.getRuntime()
                runtime.totalMemory() - runtime.freeMemory()
            }
        }
    }
}
