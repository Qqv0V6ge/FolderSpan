package com.folderspan.service.operation

import com.folderspan.service.http.client.HttpRouteClientManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val STATUS_VERSION = 1
private const val MIN_CHUNK_BYTES = 64 * 1024
private const val MIN_PARALLEL_REQUESTS = 1
private const val BUSY_RETRY_AFTER_MILLIS = 50L
internal const val HTTP_TRANSFER_STATUS_FRESH_MILLIS = 5_000L
private const val LOW_MEMORY_CRITICAL_BYTES = 8L * 1024L * 1024L
private const val LOW_MEMORY_SEVERE_BYTES = 16L * 1024L * 1024L
private const val LOW_MEMORY_MODERATE_BYTES = 32L * 1024L * 1024L
private const val LOW_MEMORY_RECOVERY_BYTES = 64L * 1024L * 1024L
private const val LOW_MEMORY_CRITICAL_CHUNK_BYTES = 64 * 1024
private const val LOW_MEMORY_SEVERE_CHUNK_BYTES = 512 * 1024
private const val LOW_MEMORY_MODERATE_CHUNK_BYTES = 1024 * 1024
private const val LOW_MEMORY_RECOVERY_CHUNK_BYTES = 4 * 1024 * 1024
private const val CONSTRAINED_MAX_HEAP_BYTES = 128L * 1024L * 1024L
private const val SMALL_MAX_HEAP_BYTES = 256L * 1024L * 1024L
private const val MODERATE_MAX_HEAP_BYTES = 384L * 1024L * 1024L
private const val CONSTRAINED_MAX_HEAP_CHUNK_BYTES = 1024 * 1024
private const val SMALL_MAX_HEAP_CHUNK_BYTES = 4 * 1024 * 1024
private const val MODERATE_MAX_HEAP_CHUNK_BYTES = 8 * 1024 * 1024
private const val CONSTRAINED_MAX_HEAP_PARALLEL_REQUESTS = 2
private const val SMALL_MAX_HEAP_PARALLEL_REQUESTS = 4
private const val MODERATE_MAX_HEAP_PARALLEL_REQUESTS = 8

internal data class HttpTransferRuntimeMemoryStatus(
    val availableHeapBytes: Long,
    val maxHeapBytes: Long,
    val lowMemory: Boolean = false,
) {
    val isKnown: Boolean
        get() = availableHeapBytes >= 0L && maxHeapBytes > 0L && maxHeapBytes < Long.MAX_VALUE

    companion object {
        fun unknown(): HttpTransferRuntimeMemoryStatus =
            HttpTransferRuntimeMemoryStatus(
                availableHeapBytes = Long.MAX_VALUE,
                maxHeapBytes = Long.MAX_VALUE,
                lowMemory = false,
            )
    }
}

internal expect object HttpTransferRuntimeMemoryStatusProvider {
    fun sample(): HttpTransferRuntimeMemoryStatus
}

internal data class HttpTransferRuntimePlan(
    val recommendedChunkBytes: Int,
    val recommendedParallelRequests: Int,
    val busy: Boolean,
)

internal object HttpTransferRuntimeTuning {
    private var memoryStatusOverrideForTests: HttpTransferRuntimeMemoryStatus? = null

    fun plan(
        maxChunkBytes: Int,
        maxParallelRequests: Int,
        memoryStatus: HttpTransferRuntimeMemoryStatus = memoryStatusOverrideForTests
            ?: HttpTransferRuntimeMemoryStatusProvider.sample(),
    ): HttpTransferRuntimePlan {
        val safeMaxChunkBytes = maxChunkBytes.coerceIn(
            MIN_CHUNK_BYTES,
            HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
        )
        val safeMaxParallelRequests = maxParallelRequests.coerceAtLeast(MIN_PARALLEL_REQUESTS)
        if (!memoryStatus.lowMemory && !memoryStatus.isKnown) {
            return HttpTransferRuntimePlan(
                recommendedChunkBytes = safeMaxChunkBytes,
                recommendedParallelRequests = safeMaxParallelRequests,
                busy = false,
            )
        }

        val availableBytes = memoryStatus.availableHeapBytes.coerceAtLeast(0L)
        val maxHeapBytes = memoryStatus.maxHeapBytes.coerceAtLeast(0L)
        val heapLimitedChunkBytes = when {
            !memoryStatus.isKnown -> safeMaxChunkBytes
            maxHeapBytes <= CONSTRAINED_MAX_HEAP_BYTES -> CONSTRAINED_MAX_HEAP_CHUNK_BYTES
            maxHeapBytes <= SMALL_MAX_HEAP_BYTES -> SMALL_MAX_HEAP_CHUNK_BYTES
            maxHeapBytes <= MODERATE_MAX_HEAP_BYTES -> MODERATE_MAX_HEAP_CHUNK_BYTES
            else -> safeMaxChunkBytes
        }
        val pressureLimitedChunkBytes = when {
            memoryStatus.lowMemory || availableBytes < LOW_MEMORY_CRITICAL_BYTES -> LOW_MEMORY_CRITICAL_CHUNK_BYTES
            availableBytes < LOW_MEMORY_SEVERE_BYTES -> LOW_MEMORY_SEVERE_CHUNK_BYTES
            availableBytes < LOW_MEMORY_MODERATE_BYTES -> LOW_MEMORY_MODERATE_CHUNK_BYTES
            availableBytes < LOW_MEMORY_RECOVERY_BYTES -> LOW_MEMORY_RECOVERY_CHUNK_BYTES
            else -> safeMaxChunkBytes
        }
        val memoryLimitedChunkBytes = minOf(pressureLimitedChunkBytes, heapLimitedChunkBytes)
            .coerceIn(MIN_CHUNK_BYTES, safeMaxChunkBytes)

        val heapLimitedParallelRequests = when {
            !memoryStatus.isKnown -> safeMaxParallelRequests
            maxHeapBytes <= CONSTRAINED_MAX_HEAP_BYTES -> CONSTRAINED_MAX_HEAP_PARALLEL_REQUESTS
            maxHeapBytes <= SMALL_MAX_HEAP_BYTES -> SMALL_MAX_HEAP_PARALLEL_REQUESTS
            maxHeapBytes <= MODERATE_MAX_HEAP_BYTES -> MODERATE_MAX_HEAP_PARALLEL_REQUESTS
            else -> safeMaxParallelRequests
        }
        val pressureLimitedParallelRequests = when {
            memoryStatus.lowMemory || availableBytes < LOW_MEMORY_SEVERE_BYTES -> 1
            availableBytes < LOW_MEMORY_MODERATE_BYTES -> 2
            availableBytes < LOW_MEMORY_RECOVERY_BYTES -> 4
            else -> safeMaxParallelRequests
        }
        val memoryLimitedParallelRequests = minOf(pressureLimitedParallelRequests, heapLimitedParallelRequests)
            .coerceIn(MIN_PARALLEL_REQUESTS, safeMaxParallelRequests)

        return HttpTransferRuntimePlan(
            recommendedChunkBytes = memoryLimitedChunkBytes,
            recommendedParallelRequests = memoryLimitedParallelRequests,
            busy = memoryStatus.lowMemory || availableBytes < LOW_MEMORY_SEVERE_BYTES,
        )
    }

    internal fun setMemoryStatusOverrideForTests(status: HttpTransferRuntimeMemoryStatus?) {
        memoryStatusOverrideForTests = status
    }
}

internal class HttpTransferStatusCache(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val freshMillis: Long = HTTP_TRANSFER_STATUS_FRESH_MILLIS,
) {
    private var latestStatus: HttpTransferStatus = HttpTransferStatus.default()
    private var receivedAtMillis: Long = 0L

    fun current(): HttpTransferStatus {
        val status = latestStatus
        if (status.sampledAtMillis <= 0L || receivedAtMillis <= 0L) {
            return status
        }
        return if (nowMillis() - receivedAtMillis <= freshMillis) {
            status
        } else {
            HttpTransferStatus.default()
        }
    }

    fun update(status: HttpTransferStatus) {
        latestStatus = status
        receivedAtMillis = nowMillis()
    }
}

data class HttpTransferStatus(
    val version: Int = STATUS_VERSION,
    val recommendedChunkBytes: Int = HttpRouteClientManager.MAX_LENGTH,
    val recommendedParallelRequests: Int = HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
    val maxChunkBytes: Int = HttpRouteClientManager.MAX_LENGTH,
    val maxParallelRequests: Int = HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
    val activeRequests: Int = 0,
    val busy: Boolean = false,
    val retryAfterMillis: Long = 0L,
    val sampledAtMillis: Long = 0L,
) {
    fun clamped(): HttpTransferStatus {
        val safeMaxChunkBytes = maxChunkBytes.coerceIn(
            MIN_CHUNK_BYTES,
            HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
        )
        val safeMaxParallelRequests =
            maxParallelRequests.coerceIn(MIN_PARALLEL_REQUESTS, HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS)
        return copy(
            version = version.coerceAtLeast(STATUS_VERSION),
            maxChunkBytes = safeMaxChunkBytes,
            maxParallelRequests = safeMaxParallelRequests,
            recommendedChunkBytes = recommendedChunkBytes.coerceIn(MIN_CHUNK_BYTES, safeMaxChunkBytes),
            recommendedParallelRequests = recommendedParallelRequests.coerceIn(
                MIN_PARALLEL_REQUESTS,
                safeMaxParallelRequests
            ),
            activeRequests = activeRequests.coerceAtLeast(0),
            retryAfterMillis = retryAfterMillis.coerceIn(0L, 5_000L),
            sampledAtMillis = sampledAtMillis.coerceAtLeast(0L),
        )
    }

    companion object {
        fun default(sampledAtMillis: Long = 0L): HttpTransferStatus {
            return HttpTransferStatus(sampledAtMillis = sampledAtMillis)
        }
    }
}

object HttpTransferStatusHeaders {
    const val VERSION = "X-FolderSpan-Transfer-Status-Version"
    const val RECOMMENDED_CHUNK_BYTES = "X-FolderSpan-Transfer-Recommended-Chunk-Bytes"
    const val RECOMMENDED_PARALLEL_REQUESTS = "X-FolderSpan-Transfer-Recommended-Parallel-Requests"
    const val MAX_CHUNK_BYTES = "X-FolderSpan-Transfer-Max-Chunk-Bytes"
    const val MAX_PARALLEL_REQUESTS = "X-FolderSpan-Transfer-Max-Parallel-Requests"
    const val ACTIVE_REQUESTS = "X-FolderSpan-Transfer-Active-Requests"
    const val BUSY = "X-FolderSpan-Transfer-Busy"
    const val RETRY_AFTER_MILLIS = "X-FolderSpan-Transfer-Retry-After-Millis"
    const val SAMPLED_AT_MILLIS = "X-FolderSpan-Transfer-Sampled-At-Millis"

    fun encode(status: HttpTransferStatus): Map<String, String> {
        val safe = status.clamped()
        return mapOf(
            VERSION to safe.version.toString(),
            RECOMMENDED_CHUNK_BYTES to safe.recommendedChunkBytes.toString(),
            RECOMMENDED_PARALLEL_REQUESTS to safe.recommendedParallelRequests.toString(),
            MAX_CHUNK_BYTES to safe.maxChunkBytes.toString(),
            MAX_PARALLEL_REQUESTS to safe.maxParallelRequests.toString(),
            ACTIVE_REQUESTS to safe.activeRequests.toString(),
            BUSY to safe.busy.toString(),
            RETRY_AFTER_MILLIS to safe.retryAfterMillis.toString(),
            SAMPLED_AT_MILLIS to safe.sampledAtMillis.toString(),
        )
    }

    fun parse(header: (String) -> String?): HttpTransferStatus? {
        if (header(VERSION)?.toIntOrNull() == null) return null
        return HttpTransferStatus(
            version = header(VERSION)?.toIntOrNull() ?: STATUS_VERSION,
            recommendedChunkBytes = header(RECOMMENDED_CHUNK_BYTES)?.toIntOrNull()
                ?: HttpRouteClientManager.MAX_LENGTH,
            recommendedParallelRequests = header(RECOMMENDED_PARALLEL_REQUESTS)?.toIntOrNull()
                ?: HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
            maxChunkBytes = header(MAX_CHUNK_BYTES)?.toIntOrNull() ?: HttpRouteClientManager.MAX_LENGTH,
            maxParallelRequests = header(MAX_PARALLEL_REQUESTS)?.toIntOrNull()
                ?: HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
            activeRequests = header(ACTIVE_REQUESTS)?.toIntOrNull() ?: 0,
            busy = header(BUSY)?.toBooleanStrictOrNull() ?: false,
            retryAfterMillis = header(RETRY_AFTER_MILLIS)?.toLongOrNull() ?: 0L,
            sampledAtMillis = header(SAMPLED_AT_MILLIS)?.toLongOrNull() ?: 0L,
        ).clamped()
    }
}

object HttpTransferStatusProvider {
    private val mutex = Mutex()
    private var activeRequests = 0

    suspend fun requestStarted() {
        mutex.withLock {
            activeRequests += 1
        }
    }

    suspend fun requestFinished(durationMillis: Long) {
        mutex.withLock {
            activeRequests = (activeRequests - 1).coerceAtLeast(0)
        }
    }

    suspend fun <T> trackRequest(block: suspend () -> T): T {
        val startedAt = nowMillis()
        requestStarted()
        try {
            return block()
        } finally {
            requestFinished(nowMillis() - startedAt)
        }
    }

    suspend fun snapshot(
        sampledAtMillis: Long = nowMillis(),
        maxChunkBytes: Int = HttpRouteClientManager.MAX_LENGTH,
        maxParallelRequests: Int = HttpRouteClientManager.MAX_CONCURRENT_FILE_CHUNKS,
    ): HttpTransferStatus {
        return mutex.withLock {
            val maxParallel = maxParallelRequests.coerceAtLeast(MIN_PARALLEL_REQUESTS)
            val safeMaxChunkBytes = maxChunkBytes.coerceIn(
                MIN_CHUNK_BYTES,
                HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
            )
            val pressureStart = ((maxParallel * 7) / 8).coerceAtLeast(MIN_PARALLEL_REQUESTS)
            val saturationStart = maxParallel.coerceAtLeast(MIN_PARALLEL_REQUESTS)
            val overloadStart = (maxParallel * 2).coerceAtLeast(saturationStart)
            val runtimePlan = HttpTransferRuntimeTuning.plan(
                maxChunkBytes = safeMaxChunkBytes,
                maxParallelRequests = maxParallel,
            )
            val isBusy = activeRequests >= saturationStart ||
                runtimePlan.busy
            val recommendedParallel = when {
                activeRequests >= overloadStart -> max(MIN_PARALLEL_REQUESTS, maxParallel / 2)
                activeRequests >= saturationStart -> max(MIN_PARALLEL_REQUESTS, (maxParallel * 3) / 4)
                activeRequests >= pressureStart -> max(MIN_PARALLEL_REQUESTS, (maxParallel * 7) / 8)
                else -> maxParallel
            }

            HttpTransferStatus(
                recommendedChunkBytes = runtimePlan.recommendedChunkBytes,
                recommendedParallelRequests = minOf(
                    recommendedParallel.coerceIn(MIN_PARALLEL_REQUESTS, maxParallel),
                    runtimePlan.recommendedParallelRequests,
                ),
                maxChunkBytes = runtimePlan.recommendedChunkBytes,
                maxParallelRequests = runtimePlan.recommendedParallelRequests,
                activeRequests = activeRequests,
                busy = isBusy,
                retryAfterMillis = if (isBusy) BUSY_RETRY_AFTER_MILLIS else 0L,
                sampledAtMillis = sampledAtMillis,
            ).clamped()
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    internal suspend fun resetForTests() {
        mutex.withLock {
            activeRequests = 0
        }
        HttpTransferRuntimeTuning.setMemoryStatusOverrideForTests(null)
    }
}

suspend fun delayForHttpTransferBackoff(status: HttpTransferStatus) {
    if (status.busy && status.retryAfterMillis > 0L) {
        delay(status.retryAfterMillis.milliseconds)
    }
}
