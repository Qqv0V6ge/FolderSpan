package com.folderspan.service.http.client

import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.HttpTransferStatus
import kotlin.math.roundToLong

private const val START_BYTE_CHUNK_BYTES = 4 * 1024 * 1024
private const val MIN_BYTE_CHUNK_BYTES = 64 * 1024
private const val START_STREAM_RANGE_BYTES = 16 * 1024 * 1024
private const val MIN_STREAM_RANGE_BYTES = 4 * 1024 * 1024
private const val START_PARALLELISM = 4
private const val MIN_TIMEOUT_MILLIS = 30_000L
private const val MAX_TIMEOUT_MILLIS = 120_000L
private const val TIMEOUT_SAFETY_MULTIPLIER = 4.0
private const val TIMEOUT_FIXED_HEADROOM_MILLIS = 5_000L

internal data class AdaptiveHttpTransferPlan(
    val byteChunkBytes: Int,
    val streamRangeBytes: Int,
    val readParallelism: Int,
    val writeParallelism: Int,
    val queueDepth: Int,
    val requestTimeoutMillis: Long,
)

internal class AdaptiveHttpTransferPlanner {
    private var targetByteChunkBytes = START_BYTE_CHUNK_BYTES
    private var targetStreamRangeBytes = START_STREAM_RANGE_BYTES
    private var targetParallelism = START_PARALLELISM
    private var successStreak = 0
    private var observedBytesPerSecond: Double? = null

    fun plan(status: HttpTransferStatus): AdaptiveHttpTransferPlan {
        val remote = status
            .takeIf { item -> item.sampledAtMillis > 0L }
            ?.clamped()
        val remoteMaxChunkBytes = remote?.maxChunkBytes ?: HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
        val remoteRecommendedChunkBytes = remote?.recommendedChunkBytes ?: remoteMaxChunkBytes
        val remoteMaxParallelism = remote?.maxParallelRequests ?: HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS
        val remoteRecommendedParallelism = remote?.recommendedParallelRequests ?: remoteMaxParallelism

        val requestedByteChunkBytes = minOf(
            targetByteChunkBytes,
            remoteRecommendedChunkBytes,
            remoteMaxChunkBytes,
            HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
        ).coerceAtLeast(MIN_BYTE_CHUNK_BYTES)
        val requestedParallelism = minOf(
            targetParallelism,
            remoteRecommendedParallelism,
            remoteMaxParallelism,
            HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
        ).coerceAtLeast(1)
        val runtime = HttpTransferRuntimeTuning.plan(
            maxChunkBytes = requestedByteChunkBytes,
            maxParallelRequests = requestedParallelism,
        )
        val byteChunkBytes = runtime.recommendedChunkBytes
            .coerceIn(MIN_BYTE_CHUNK_BYTES, requestedByteChunkBytes)
        val readParallelism = runtime.recommendedParallelRequests
            .coerceIn(1, requestedParallelism)
        val queueDepth = minOf(
            HttpRouteClientManager.DEVICE_DIRECT_PREFETCH_QUEUE_DEPTH,
            readParallelism,
        ).coerceAtLeast(1)
        val streamRangeBytes = minOf(
            targetStreamRangeBytes,
            HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES,
        ).coerceAtLeast(MIN_STREAM_RANGE_BYTES)

        return AdaptiveHttpTransferPlan(
            byteChunkBytes = byteChunkBytes,
            streamRangeBytes = streamRangeBytes,
            readParallelism = readParallelism,
            writeParallelism = readParallelism,
            queueDepth = queueDepth,
            requestTimeoutMillis = adaptiveHttpTransferTimeoutMillis(
                rangeBytes = byteChunkBytes.toLong(),
                observedBytesPerSecond = observedBytesPerSecond,
            ),
        )
    }

    fun recordSuccess(bytesTransferred: Long, durationMillis: Long) {
        if (bytesTransferred > 0L && durationMillis > 0L) {
            val sampleBytesPerSecond = bytesTransferred.toDouble() * 1000.0 / durationMillis.toDouble()
            observedBytesPerSecond = observedBytesPerSecond
                ?.let { previous -> previous * 0.7 + sampleBytesPerSecond * 0.3 }
                ?: sampleBytesPerSecond
        }
        successStreak += 1
        if (successStreak >= 2) {
            successStreak = 0
            targetParallelism = (targetParallelism + 1)
                .coerceAtMost(HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS)
            targetByteChunkBytes = (targetByteChunkBytes * 2)
                .coerceAtMost(HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH)
            targetStreamRangeBytes = (targetStreamRangeBytes * 2)
                .coerceAtMost(HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES)
        }
    }

    fun recordTimeout() {
        reducePressure()
    }

    fun recordBusy() {
        reducePressure()
    }

    fun recordFailure(error: Throwable) {
        val message = error.message.orEmpty().lowercase()
        if (message.contains("timeout") || message.contains("timed out")) {
            recordTimeout()
        } else {
            successStreak = 0
        }
    }

    private fun reducePressure() {
        successStreak = 0
        targetParallelism = (targetParallelism / 2).coerceAtLeast(1)
        targetByteChunkBytes = (targetByteChunkBytes / 2).coerceAtLeast(MIN_BYTE_CHUNK_BYTES)
        targetStreamRangeBytes = (targetStreamRangeBytes / 2).coerceAtLeast(MIN_STREAM_RANGE_BYTES)
    }
}

internal fun adaptiveHttpTransferTimeoutMillis(
    rangeBytes: Long,
    observedBytesPerSecond: Double?,
): Long {
    val throughput = observedBytesPerSecond?.takeIf { item -> item > 0.0 }
        ?: return MIN_TIMEOUT_MILLIS
    val estimatedMillis = (rangeBytes.coerceAtLeast(1L).toDouble() / throughput) * 1000.0
    return (estimatedMillis * TIMEOUT_SAFETY_MULTIPLIER)
        .roundToLong()
        .plus(TIMEOUT_FIXED_HEADROOM_MILLIS)
        .coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)
}
