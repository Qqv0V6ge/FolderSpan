package com.folderspan.service.session

import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatus
import com.folderspan.service.operation.HttpTransferRuntimeMemoryStatusProvider
import com.folderspan.service.operation.HttpTransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val DEVICE_SESSION_CRITICAL_WINDOW_BYTES = 256 * 1024
internal const val DEVICE_SESSION_TIGHT_WINDOW_BYTES = 1024 * 1024
internal const val DEVICE_SESSION_AMPLE_WINDOW_BYTES = 8 * 1024 * 1024
internal const val DEVICE_SESSION_WIDE_WINDOW_BYTES = 16 * 1024 * 1024
internal const val DEVICE_SESSION_MAX_FRAME_QUEUE_CAPACITY = 64

private const val LOW_MEMORY_CRITICAL_BYTES = 8L * 1024L * 1024L
private const val LOW_MEMORY_SEVERE_BYTES = 16L * 1024L * 1024L
private const val LOW_MEMORY_MODERATE_BYTES = 32L * 1024L * 1024L
private const val CONSTRAINED_MAX_HEAP_BYTES = 128L * 1024L * 1024L
private const val SMALL_MAX_HEAP_BYTES = 256L * 1024L * 1024L

internal data class DeviceSessionWindowPlan(
    val sessionWindowBytes: Int,
    val streamWindowBytes: Int,
    val maxFileStreams: Int,
) {
    init {
        require(sessionWindowBytes > 0) { "sessionWindowBytes must be positive" }
        require(streamWindowBytes > 0) { "streamWindowBytes must be positive" }
        require(maxFileStreams > 0) { "maxFileStreams must be positive" }
        require(streamWindowBytes <= sessionWindowBytes) {
            "stream window must fit the session window"
        }
    }

    companion object {
        fun fromMemory(
            memoryStatus: HttpTransferRuntimeMemoryStatus = HttpTransferRuntimeMemoryStatusProvider.sample(),
        ): DeviceSessionWindowPlan {
            if (!memoryStatus.lowMemory && !memoryStatus.isKnown) {
                return DeviceSessionWindowPlan(
                    sessionWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    maxFileStreams = 16,
                )
            }
            val availableBytes = memoryStatus.availableHeapBytes.coerceAtLeast(0L)
            val maxHeapBytes = memoryStatus.maxHeapBytes.coerceAtLeast(0L)
            val critical = memoryStatus.lowMemory || availableBytes < LOW_MEMORY_CRITICAL_BYTES
            val tight = availableBytes < LOW_MEMORY_SEVERE_BYTES
            val moderate = availableBytes < LOW_MEMORY_MODERATE_BYTES
            val heapConstrained = memoryStatus.isKnown && maxHeapBytes <= CONSTRAINED_MAX_HEAP_BYTES
            val heapSmall = memoryStatus.isKnown && maxHeapBytes <= SMALL_MAX_HEAP_BYTES
            return when {
                critical || heapConstrained -> DeviceSessionWindowPlan(
                    sessionWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
                    maxFileStreams = 1,
                )
                tight -> DeviceSessionWindowPlan(
                    sessionWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_CRITICAL_WINDOW_BYTES,
                    maxFileStreams = 4,
                )
                // A small maximum heap limits concurrency, not the bandwidth of one file.
                heapSmall -> DeviceSessionWindowPlan(
                    sessionWindowBytes = 4 * DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    maxFileStreams = 4,
                )
                moderate -> DeviceSessionWindowPlan(
                    sessionWindowBytes = DEVICE_SESSION_AMPLE_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    maxFileStreams = 8,
                )
                else -> DeviceSessionWindowPlan(
                    sessionWindowBytes = DEVICE_SESSION_WIDE_WINDOW_BYTES,
                    streamWindowBytes = DEVICE_SESSION_TIGHT_WINDOW_BYTES,
                    maxFileStreams = 16,
                )
            }
        }
    }
}

internal fun DeviceSessionWindowPlan.frameQueueCapacity(): Int {
    return frameQueueCapacity(sessionWindowBytes)
}

internal fun DeviceSessionWindowPlan.streamFrameQueueCapacity(): Int {
    return frameQueueCapacity(streamWindowBytes)
}

private fun frameQueueCapacity(windowBytes: Int): Int {
    val frameCount = (windowBytes.toLong() + DEVICE_SESSION_MAX_PAYLOAD_BYTES - 1L) /
        DEVICE_SESSION_MAX_PAYLOAD_BYTES
    return frameCount.coerceIn(1L, DEVICE_SESSION_MAX_FRAME_QUEUE_CAPACITY.toLong()).toInt()
}

internal fun DeviceSessionWindowPlan.recommendedChunkBytes(): Int {
    val aggregateShare = (sessionWindowBytes / maxFileStreams).coerceAtLeast(1)
    return minOf(streamWindowBytes, aggregateShare)
        .coerceAtLeast(DEVICE_SESSION_MAX_PAYLOAD_BYTES)
}

internal fun DeviceSessionWindowPlan.toTransferStatus(
    remoteMaxFileStreams: Int,
    remoteRecommendedChunkBytes: Int,
    sampledAtMillis: Long,
): HttpTransferStatus {
    require(remoteMaxFileStreams > 0) { "remoteMaxFileStreams must be positive" }
    require(remoteRecommendedChunkBytes >= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
        "remoteRecommendedChunkBytes must fit one session frame"
    }
    val effectiveParallelism = minOf(
        maxFileStreams,
        remoteMaxFileStreams,
    ).coerceAtLeast(1)
    val localChunkBytes = recommendedChunkBytes()
    val effectiveChunkBytes = minOf(
        localChunkBytes,
        remoteRecommendedChunkBytes,
    ).coerceAtLeast(DEVICE_SESSION_MAX_PAYLOAD_BYTES)
    return HttpTransferStatus(
        recommendedChunkBytes = effectiveChunkBytes,
        recommendedParallelRequests = effectiveParallelism,
        maxChunkBytes = effectiveChunkBytes,
        maxParallelRequests = effectiveParallelism,
        sampledAtMillis = sampledAtMillis.coerceAtLeast(1L),
    ).clamped()
}

internal class DeviceSessionCredit(
    val plan: DeviceSessionWindowPlan,
) {
    private val mutex = Mutex()
    private val creditVersion = MutableStateFlow(0L)
    private val streamInFlight = HashMap<Int, Int>()
    private var sessionInFlight = 0

    val sessionWindowBytes: Int
        get() = plan.sessionWindowBytes

    val streamWindowBytes: Int
        get() = plan.streamWindowBytes

    suspend fun inFlightBytes(): Int = mutex.withLock { sessionInFlight }

    suspend fun streamInFlightBytes(streamId: Int): Int = mutex.withLock {
        streamInFlight[streamId] ?: 0
    }

    suspend fun remainingSessionBytes(): Int = mutex.withLock {
        (plan.sessionWindowBytes - sessionInFlight).coerceAtLeast(0)
    }

    suspend fun remainingStreamBytes(streamId: Int): Int = mutex.withLock {
        remainingStreamLocked(streamId)
    }

    suspend fun awaitAndConsume(
        streamId: Int,
        bytes: Int,
        checkActive: suspend () -> Unit = {},
    ) {
        require(bytes >= 0) { "bytes must be non-negative" }
        if (bytes == 0) return
        require(bytes <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) { "frame exceeds payload cap" }
        require(bytes <= plan.streamWindowBytes) { "frame exceeds stream window" }
        while (true) {
            val version = mutex.withLock {
                if (canConsumeLocked(streamId, bytes)) {
                    consumeLocked(streamId, bytes)
                    return
                }
                creditVersion.value
            }
            // Each writer must recheck its own stream and the shared session budget.
            // Capture the version under the same lock as that check so a grant between
            // unlocking and subscribing cannot be lost or consumed by another writer.
            checkActive()
            creditVersion.first { it != version }
        }
    }

    suspend fun tryConsume(streamId: Int, bytes: Int): Boolean {
        require(bytes >= 0) { "bytes must be non-negative" }
        if (bytes == 0) return true
        if (bytes > DEVICE_SESSION_MAX_PAYLOAD_BYTES || bytes > plan.streamWindowBytes) return false
        return mutex.withLock {
            if (!canConsumeLocked(streamId, bytes)) {
                false
            } else {
                consumeLocked(streamId, bytes)
                true
            }
        }
    }

    suspend fun grant(streamId: Int, bytes: Int) {
        require(bytes >= 0) { "bytes must be non-negative" }
        if (bytes == 0) return
        mutex.withLock {
            val current = streamInFlight[streamId] ?: 0
            val released = minOf(bytes, current)
            if (released > 0) {
                streamInFlight[streamId] = current - released
                sessionInFlight = (sessionInFlight - released).coerceAtLeast(0)
            }
            if ((streamInFlight[streamId] ?: 0) == 0) {
                streamInFlight.remove(streamId)
            }
            if (released > 0) wakeWaiters()
        }
    }

    suspend fun closeStream(streamId: Int) {
        mutex.withLock {
            val released = streamInFlight.remove(streamId) ?: 0
            sessionInFlight = (sessionInFlight - released).coerceAtLeast(0)
            wakeWaiters()
        }
    }

    fun wakeWaiters() {
        creditVersion.update { it + 1 }
    }

    private fun remainingStreamLocked(streamId: Int): Int {
        val used = streamInFlight[streamId] ?: 0
        val sessionRemaining = (plan.sessionWindowBytes - sessionInFlight).coerceAtLeast(0)
        return minOf(plan.streamWindowBytes - used, sessionRemaining).coerceAtLeast(0)
    }

    private fun canConsumeLocked(streamId: Int, bytes: Int): Boolean {
        return remainingStreamLocked(streamId) >= bytes
    }

    private fun consumeLocked(streamId: Int, bytes: Int) {
        streamInFlight[streamId] = (streamInFlight[streamId] ?: 0) + bytes
        sessionInFlight += bytes
    }
}
