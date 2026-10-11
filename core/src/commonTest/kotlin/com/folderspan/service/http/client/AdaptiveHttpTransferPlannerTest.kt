package com.folderspan.service.http.client

import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveHttpTransferPlannerTest {
    @Test
    fun startsWithConservativePlanWhenRemoteStatusIsMissing() = withMemory(
        availableHeapBytes = 512L * 1024L * 1024L,
    ) {
        val planner = AdaptiveHttpTransferPlanner()

        val plan = planner.plan(status = HttpTransferStatus.default())

        assertEquals(4 * 1024 * 1024, plan.byteChunkBytes)
        assertEquals(16 * 1024 * 1024, plan.streamRangeBytes)
        assertEquals(4, plan.readParallelism)
        assertEquals(4, plan.writeParallelism)
        assertEquals(4, plan.queueDepth)
        assertEquals(30_000L, plan.requestTimeoutMillis)
    }

    @Test
    fun successfulFastRangesIncreaseThroughputWithinProtocolLimits() = withMemory(
        availableHeapBytes = 512L * 1024L * 1024L,
    ) {
        val planner = AdaptiveHttpTransferPlanner()
        val before = planner.plan(HttpTransferStatus.default())

        planner.recordSuccess(bytesTransferred = before.byteChunkBytes.toLong(), durationMillis = 500L)
        planner.recordSuccess(bytesTransferred = before.byteChunkBytes.toLong(), durationMillis = 500L)
        val after = planner.plan(HttpTransferStatus.default())

        assertTrue(after.byteChunkBytes > before.byteChunkBytes)
        assertTrue(after.streamRangeBytes > before.streamRangeBytes)
        assertTrue(after.readParallelism > before.readParallelism)
        assertTrue(after.byteChunkBytes <= HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)
        assertTrue(after.streamRangeBytes <= HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES)
        assertTrue(after.readParallelism <= HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS)
    }

    @Test
    fun smallHeapsDoNotPromoteByteChunksToSixteenMegabytes() = withMemory(
        availableHeapBytes = 96L * 1024L * 1024L,
        maxHeapBytes = 192L * 1024L * 1024L,
    ) {
        val planner = AdaptiveHttpTransferPlanner()

        repeat(4) {
            planner.recordSuccess(bytesTransferred = 16L * 1024L * 1024L, durationMillis = 500L)
        }
        val plan = planner.plan(HttpTransferStatus.default())

        assertEquals(4 * 1024 * 1024, plan.byteChunkBytes)
        assertTrue(plan.byteChunkBytes < HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)
    }

    @Test
    fun timeoutAndBusyReducePressureBeforeMoreRangesAreScheduled() = withMemory(
        availableHeapBytes = 512L * 1024L * 1024L,
    ) {
        val planner = AdaptiveHttpTransferPlanner()
        repeat(4) {
            planner.recordSuccess(bytesTransferred = 4L * 1024L * 1024L, durationMillis = 500L)
        }
        val stable = planner.plan(HttpTransferStatus.default())

        planner.recordTimeout()
        val afterTimeout = planner.plan(HttpTransferStatus.default())

        assertTrue(afterTimeout.readParallelism < stable.readParallelism)
        assertTrue(afterTimeout.byteChunkBytes < stable.byteChunkBytes)

        planner.recordBusy()
        val afterBusy = planner.plan(HttpTransferStatus.default())

        assertTrue(afterBusy.readParallelism <= afterTimeout.readParallelism)
        assertTrue(afterBusy.byteChunkBytes <= afterTimeout.byteChunkBytes)
    }

    @Test
    fun memoryPressureClampsChunkParallelismAndQueueDepth() = withMemory(
        availableHeapBytes = 2L * 1024L * 1024L,
        lowMemory = true,
    ) {
        val planner = AdaptiveHttpTransferPlanner()

        val plan = planner.plan(
            status = HttpTransferStatus(
                recommendedChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
                recommendedParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
                maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
                maxParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
                sampledAtMillis = 42L,
            )
        )

        assertEquals(64 * 1024, plan.byteChunkBytes)
        assertEquals(1, plan.readParallelism)
        assertEquals(1, plan.writeParallelism)
        assertEquals(1, plan.queueDepth)
    }

    @Test
    fun timeoutIsDerivedFromSelectedRangeAndObservedThroughput() = withMemory(
        availableHeapBytes = 512L * 1024L * 1024L,
    ) {
        val planner = AdaptiveHttpTransferPlanner()

        planner.recordSuccess(bytesTransferred = 4L * 1024L * 1024L, durationMillis = 8_000L)
        val slow = planner.plan(HttpTransferStatus.default())

        assertTrue(slow.requestTimeoutMillis > 30_000L)
        assertTrue(slow.requestTimeoutMillis <= 120_000L)
    }

    private inline fun withMemory(
        availableHeapBytes: Long,
        maxHeapBytes: Long = 1024L * 1024L * 1024L,
        lowMemory: Boolean = false,
        block: () -> Unit,
    ) {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = availableHeapBytes,
                    maxHeapBytes = maxHeapBytes,
                    lowMemory = lowMemory,
                )
            )
            block()
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }
}
