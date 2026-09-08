package com.folderspan.service.operation

import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.test.runSuspendTest
import kotlin.test.*

class HttpTransferStatusTest {
    @Test
    fun encodeAndParseRoundTripTransferStatus() {
        val status = HttpTransferStatus(
            recommendedChunkBytes = 512 * 1024,
            recommendedParallelRequests = 6,
            maxChunkBytes = HttpRouteClientManager.MAX_LENGTH,
            maxParallelRequests = 16,
            activeRequests = 8,
            busy = true,
            retryAfterMillis = 150L,
            sampledAtMillis = 1234L,
        )

        val headers = HttpTransferStatusHeaders.encode(status)
        val parsed = HttpTransferStatusHeaders.parse(headers::get)

        assertNotNull(parsed)
        assertEquals(512 * 1024, parsed.recommendedChunkBytes)
        assertEquals(6, parsed.recommendedParallelRequests)
        assertEquals(8, parsed.activeRequests)
        assertTrue(parsed.busy)
        assertEquals(150L, parsed.retryAfterMillis)
        assertEquals(1234L, parsed.sampledAtMillis)
    }

    @Test
    fun parseReturnsNullWhenVersionHeaderMissing() {
        val parsed = HttpTransferStatusHeaders.parse { null }

        assertNull(parsed)
    }

    @Test
    fun parseUsesAggressiveDefaultsWhenOptionalHeadersAreMissing() {
        val headers = mapOf(
            HttpTransferStatusHeaders.VERSION to "1",
        )

        val parsed = HttpTransferStatusHeaders.parse(headers::get)

        assertNotNull(parsed)
        assertEquals(HttpRouteClientManager.MAX_LENGTH, parsed.recommendedChunkBytes)
        assertEquals(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS, parsed.recommendedParallelRequests)
    }

    @Test
    fun parseClampsUnsafeHeaderValuesToSafeDefaults() {
        val headers = mapOf(
            HttpTransferStatusHeaders.VERSION to "1",
            HttpTransferStatusHeaders.RECOMMENDED_CHUNK_BYTES to "1",
            HttpTransferStatusHeaders.RECOMMENDED_PARALLEL_REQUESTS to "-10",
            HttpTransferStatusHeaders.MAX_CHUNK_BYTES to
                (HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH * 4).toString(),
            HttpTransferStatusHeaders.MAX_PARALLEL_REQUESTS to "999",
            HttpTransferStatusHeaders.ACTIVE_REQUESTS to "-1",
            HttpTransferStatusHeaders.BUSY to "not-a-boolean",
            HttpTransferStatusHeaders.RETRY_AFTER_MILLIS to "999999",
            HttpTransferStatusHeaders.SAMPLED_AT_MILLIS to "-50",
        )

        val parsed = HttpTransferStatusHeaders.parse(headers::get)

        assertNotNull(parsed)
        assertEquals(64 * 1024, parsed.recommendedChunkBytes)
        assertEquals(1, parsed.recommendedParallelRequests)
        assertEquals(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH, parsed.maxChunkBytes)
        assertEquals(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS, parsed.maxParallelRequests)
        assertEquals(0, parsed.activeRequests)
        assertFalse(parsed.busy)
        assertEquals(5_000L, parsed.retryAfterMillis)
        assertEquals(0L, parsed.sampledAtMillis)
    }

    @Test
    fun snapshotRecommendsMaxConcurrencyWhenPressureIsLow() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            repeat(8) {
                HttpTransferStatusProvider.requestStarted()
            }

            val status = HttpTransferStatusProvider.snapshot(sampledAtMillis = 42L)

            assertFalse(status.busy)
            assertEquals(HttpRouteClientManager.MAX_LENGTH, status.recommendedChunkBytes)
            assertEquals(
                HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
                status.recommendedParallelRequests,
            )
            assertEquals(0L, status.retryAfterMillis)
            assertEquals(42L, status.sampledAtMillis)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotDefaultsToGenericChunkLimitForSharedRoutes() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            val status = HttpTransferStatusProvider.snapshot(sampledAtMillis = 42L)

            assertEquals(HttpRouteClientManager.MAX_LENGTH, status.recommendedChunkBytes)
            assertEquals(HttpRouteClientManager.MAX_LENGTH, status.maxChunkBytes)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotCanAdvertiseDeviceDirectChunkLimit() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            val status = HttpTransferStatusProvider.snapshot(
                sampledAtMillis = 42L,
                maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
            )

            assertEquals(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH, status.recommendedChunkBytes)
            assertEquals(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH, status.maxChunkBytes)
            assertEquals(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS, status.recommendedParallelRequests)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotCanAdvertiseDeviceDirectParallelLimit() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            val deviceLimit = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS
            repeat(deviceLimit) {
                HttpTransferStatusProvider.requestStarted()
            }

            val status = HttpTransferStatusProvider.snapshot(
                sampledAtMillis = 42L,
                maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
                maxParallelRequests = deviceLimit,
            )

            assertTrue(status.busy)
            assertEquals(deviceLimit, status.maxParallelRequests)
            assertEquals((deviceLimit * 3) / 4, status.recommendedParallelRequests)
            assertTrue(status.retryAfterMillis > 0L)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotShrinksDeviceDirectRecommendationsWhenHeapIsAlmostFull() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 2L * 1024L * 1024L,
                    maxHeapBytes = 256L * 1024L * 1024L,
                )
            )

            val status = HttpTransferStatusProvider.snapshot(
                sampledAtMillis = 42L,
                maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
            )

            assertEquals(64 * 1024, status.recommendedChunkBytes)
            assertEquals(64 * 1024, status.maxChunkBytes)
            assertEquals(1, status.recommendedParallelRequests)
            assertEquals(1, status.maxParallelRequests)
            assertTrue(status.busy)
            assertTrue(status.retryAfterMillis > 0L)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotDegradesNearSaturationWithoutBusyRetry() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            val nearSaturation = (HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS * 7) / 8
            repeat(nearSaturation) {
                HttpTransferStatusProvider.requestStarted()
            }

            val status = HttpTransferStatusProvider.snapshot()

            assertFalse(status.busy)
            assertEquals(nearSaturation, status.recommendedParallelRequests)
            assertEquals(0L, status.retryAfterMillis)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun snapshotDoesNotThrottleNextFileBecausePreviousRequestWasLong() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(
                HttpTransferRuntimeMemoryStatus(
                    availableHeapBytes = 512L * 1024L * 1024L,
                    maxHeapBytes = 1024L * 1024L * 1024L,
                )
            )
            HttpTransferStatusProvider.requestStarted()
            HttpTransferStatusProvider.requestFinished(120_000L)

            val status = HttpTransferStatusProvider.snapshot(
                sampledAtMillis = 42L,
                maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
            )

            assertFalse(status.busy)
            assertEquals(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH, status.recommendedChunkBytes)
            assertEquals(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS, status.recommendedParallelRequests)
            assertEquals(0L, status.retryAfterMillis)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }

    @Test
    fun statusCacheExpiresStaleRemoteLowMemoryRecommendation() {
        var now = 1_000L
        val cache = HttpTransferStatusCache(nowMillis = { now })
        cache.update(
            HttpTransferStatus(
                recommendedChunkBytes = 256 * 1024,
                recommendedParallelRequests = 1,
                maxChunkBytes = 256 * 1024,
                maxParallelRequests = 1,
                busy = true,
                retryAfterMillis = 50L,
                sampledAtMillis = 42L,
            )
        )

        assertEquals(256 * 1024, cache.current().recommendedChunkBytes)
        assertEquals(1, cache.current().recommendedParallelRequests)

        now += HTTP_TRANSFER_STATUS_FRESH_MILLIS + 1L

        val expired = cache.current()
        assertEquals(HttpRouteClientManager.MAX_LENGTH, expired.recommendedChunkBytes)
        assertEquals(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS, expired.recommendedParallelRequests)
        assertFalse(expired.busy)
    }

    @Test
    fun snapshotKeepsBusyRetryWhenSaturated() = runSuspendTest {
        HttpTransferStatusProvider.resetForTests()
        try {
            repeat(HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS) {
                HttpTransferStatusProvider.requestStarted()
            }

            val status = HttpTransferStatusProvider.snapshot()

            assertTrue(status.busy)
            assertEquals(
                (HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS * 3) / 4,
                status.recommendedParallelRequests,
            )
            assertTrue(status.retryAfterMillis > 0L)
        } finally {
            HttpTransferStatusProvider.resetForTests()
        }
    }
}
