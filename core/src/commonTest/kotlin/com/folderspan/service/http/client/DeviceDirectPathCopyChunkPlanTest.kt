package com.folderspan.service.http.client

import strings.AppStrings

import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.ui.state.main.DeviceEndpointUnavailableException
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class DeviceDirectPathCopyChunkPlanTest {
    private inline fun withStableRuntimeMemory(block: () -> Unit) {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 512L * 1024L * 1024L,
                    maxHeapBytes = 1024L * 1024L * 1024L,
                )
            )
            block()
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }

    @Test
    fun pathCopyUsesSmallerChunksToFillTwelveRequestsWithinMemoryBudget() = withStableRuntimeMemory {
        val chunkBytes = deviceDirectPathCopyChunkBytes(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)

        assertEquals(10 * 1024 * 1024, chunkBytes)
        assertTrue(chunkBytes < HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)
        assertEquals(
            HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
            HttpRouteClientManager.DEVICE_DIRECT_MAX_IN_FLIGHT_BYTES / chunkBytes,
        )
    }

    @Test
    fun pathCopyHonorsLowerStatusChunkRecommendations() = withStableRuntimeMemory {
        val lowerRecommendation = 8 * 1024 * 1024

        assertEquals(lowerRecommendation, deviceDirectPathCopyChunkBytes(lowerRecommendation))
    }

    @Test
    fun pathDownloadTransferInfoLogsAreDisabled() {
        assertFalse(shouldWritePathTransferInfoLog(PathTransferLogKind.Download))
        assertTrue(shouldWritePathTransferInfoLog(PathTransferLogKind.Upload))
        assertTrue(shouldWritePathTransferInfoLog(PathTransferLogKind.DeviceTransfer))
    }

    @Test
    fun pathCopyUsesSmallerLocalChunksWhenHeapIsAlmostFull() {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 2L * 1024L * 1024L,
                    maxHeapBytes = 256L * 1024L * 1024L,
                )
            )

            assertEquals(64 * 1024, deviceDirectPathCopyChunkBytes(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH))
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }

    @Test
    fun pathCopyCapsChunksForSmallAndroidHeapEvenBeforeLowMemory() {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 96L * 1024L * 1024L,
                    maxHeapBytes = 192L * 1024L * 1024L,
                )
            )

            assertEquals(
                4 * 1024 * 1024,
                deviceDirectPathCopyChunkBytes(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH),
            )
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }

    @Test
    fun streamWorkerCountUsesLocalMemoryPressure() {
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 2L * 1024L * 1024L,
                    maxHeapBytes = 256L * 1024L * 1024L,
                )
            )

            assertEquals(
                1,
                httpStreamWorkerCount(
                    chunkCount = HttpRouteClientManager.DEVICE_DIRECT_STREAM_PARALLEL_REQUESTS,
                    recommendedParallelRequests = null,
                    maxParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_STREAM_PARALLEL_REQUESTS,
                )
            )
        } finally {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
        }
    }

    @Test
    fun deviceDirectLongStreamKeepsTwelveWideConnectionsWithFewerRequests() = withStableRuntimeMemory {
        assertEquals(64 * 1024 * 1024, HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES)
        assertEquals(32 * 1024 * 1024, HttpRouteClientManager.DEVICE_DIRECT_STREAM_TARGET_RANGE_BYTES)
        assertEquals(HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS, HttpRouteClientManager.DEVICE_DIRECT_STREAM_PARALLEL_REQUESTS)
        assertEquals(4 * 1024 * 1024, HttpRouteClientManager.DEVICE_DIRECT_STREAM_BUFFER_BYTES)
        assertTrue(
            HttpRouteClientManager.DEVICE_DIRECT_STREAM_TARGET_RANGE_BYTES >
                deviceDirectPathCopyChunkBytes(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)
        )
        assertTrue(HttpRouteClientManager.DEVICE_DIRECT_STREAM_TARGET_RANGE_BYTES < HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES)
    }

    @Test
    fun largeDeviceDirectLocalReadsPreferStreamFileWhileSmallReadsUseReadBytes() = withStableRuntimeMemory {
        val planner = AdaptiveHttpTransferPlanner()
        val plan = planner.plan(status = com.folderspan.service.operation.HttpTransferStatus.default())

        assertEquals(
            DeviceDirectReadRoute.ReadBytes,
            selectDeviceDirectReadRoute(plan.byteChunkBytes.toLong(), plan),
        )
        assertEquals(
            DeviceDirectReadRoute.StreamFile,
            selectDeviceDirectReadRoute(plan.byteChunkBytes.toLong() + 1L, plan),
        )
        assertNotEquals(DeviceDirectReadRoute.ReadBytes, DeviceDirectReadRoute.StreamFile)
    }

    @Test
    fun deviceDirectDownloadToFileKeepsOnlySmallRangesOnReadBytesRoute() {
        assertEquals(
            DeviceDirectReadRoute.ReadBytes,
            selectDeviceDirectDownloadToFileRoute(4L * 1024L * 1024L),
        )
        assertEquals(
            DeviceDirectReadRoute.StreamFile,
            selectDeviceDirectDownloadToFileRoute(4L * 1024L * 1024L + 1L),
        )
    }

    @Test
    fun streamFallbackRetriesOnlyUnfinishedRanges() {
        val fallbackRanges = remainingDeviceDirectFallbackRanges(
            totalBytes = 96L * 1024L * 1024L,
            streamChunkBytes = 32 * 1024 * 1024,
            fallbackChunkBytes = 4 * 1024 * 1024,
            streamWrittenBytes = longArrayOf(
                32L * 1024L * 1024L,
                8L * 1024L * 1024L,
                0L,
            ),
        )

        assertTrue(fallbackRanges.none { (start, _) -> start < 32L * 1024L * 1024L })
        assertEquals(32L * 1024L * 1024L + 8L * 1024L * 1024L, fallbackRanges.first().first)
        assertEquals(96L * 1024L * 1024L, fallbackRanges.last().second)
    }

    @Test
    fun streamFallbackSkipsTaskLevelEndpointFailures() {
        assertFalse(shouldFallbackDeviceDirectStreamFailure(DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)))
        assertFalse(shouldFallbackDeviceDirectStreamFailure(CancellationException(AppStrings.message_task_cancelled)))
        assertTrue(shouldFallbackDeviceDirectStreamFailure(Exception("socket closed")))
    }

    @Test
    fun rangeTransferLogsTaskLevelEndpointFailuresAsWarnings() {
        assertFalse(shouldLogHttpRangeTransferFailureAsError(DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)))
        assertTrue(shouldLogHttpRangeTransferFailureAsError(Exception("socket closed")))
    }
}
