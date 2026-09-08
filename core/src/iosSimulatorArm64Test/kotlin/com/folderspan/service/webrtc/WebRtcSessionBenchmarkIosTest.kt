@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.webrtc

import kotlinx.cinterop.*
import platform.darwin.*
import kotlin.test.Test

class WebRtcSessionBenchmarkIosTest {
    @Test
    fun sampleThroughputBufferedAmountAndResidentMemory() = runIosWebRtcTest {
        measureRealWebRtcSession {
            memScoped {
                val info = alloc<mach_task_basic_info>()
                val count = alloc<UIntVar> { value = MACH_TASK_BASIC_INFO_COUNT.toUInt() }
                check(task_info(mach_task_self_, MACH_TASK_BASIC_INFO.toUInt(), info.ptr.reinterpret(), count.ptr) == KERN_SUCCESS)
                info.resident_size.toLong()
            }
        }
    }
}
