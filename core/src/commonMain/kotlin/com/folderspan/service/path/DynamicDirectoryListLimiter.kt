package com.folderspan.service.path

import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal interface DirectoryListLimiter {
    suspend fun <T> withPermit(block: suspend () -> T): T
}

internal class FixedDirectoryListLimiter(
    permits: Int,
) : DirectoryListLimiter {
    private val semaphore = Semaphore(permits.coerceAtLeast(1))

    override suspend fun <T> withPermit(block: suspend () -> T): T {
        return semaphore.withPermit { block() }
    }
}

internal data class DirectoryListLimiterSnapshot(
    val activeRequests: Int,
    val currentLimit: Int,
    val maxLimit: Int,
)

internal class DynamicDirectoryListLimiter(
    minConcurrency: Int = DEFAULT_MIN_CONCURRENCY,
    initialConcurrency: Int = DEFAULT_INITIAL_CONCURRENCY,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val runtimeMaxConcurrencyProvider: () -> Int = { defaultRuntimeListMaxConcurrency(DEFAULT_MAX_CONCURRENCY) },
    private val fastListingMillis: Long = DEFAULT_FAST_LISTING_MILLIS,
    private val slowListingMillis: Long = DEFAULT_SLOW_LISTING_MILLIS,
    private val acquirePollMillis: Long = DEFAULT_ACQUIRE_POLL_MILLIS,
) : DirectoryListLimiter {
    private val mutex = Mutex()
    private val minLimit = minConcurrency.coerceAtLeast(1)
    private val maxLimit = maxConcurrency.coerceAtLeast(minLimit)
    private var activeRequests = 0
    private var currentLimit = initialConcurrency.coerceIn(minLimit, maxLimit)
    private var fastSuccesses = 0

    override suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        val startedAt = Clock.System.now().toEpochMilliseconds()
        var success = false
        try {
            val result = block()
            success = true
            return result
        } finally {
            val durationMillis = Clock.System.now().toEpochMilliseconds() - startedAt
            withContext(NonCancellable) {
                release(durationMillis = durationMillis, success = success)
            }
        }
    }

    suspend fun snapshot(): DirectoryListLimiterSnapshot {
        return mutex.withLock {
            clampLimitLocked()
            DirectoryListLimiterSnapshot(
                activeRequests = activeRequests,
                currentLimit = currentLimit,
                maxLimit = runtimeMaxLocked(),
            )
        }
    }

    private suspend fun acquire() {
        while (true) {
            mutex.withLock {
                clampLimitLocked()
                if (activeRequests < currentLimit) {
                    activeRequests++
                    return
                }
            }
            delay(acquirePollMillis.milliseconds)
        }
    }

    private suspend fun release(durationMillis: Long, success: Boolean) {
        mutex.withLock {
            activeRequests = (activeRequests - 1).coerceAtLeast(0)
            val runtimeMax = clampLimitLocked()
            if (!success) {
                fastSuccesses = 0
                currentLimit = maxOf(minLimit, currentLimit / 2).coerceAtMost(runtimeMax)
                return@withLock
            }

            when {
                durationMillis <= fastListingMillis -> {
                    fastSuccesses++
                    if (fastSuccesses >= currentLimit && currentLimit < runtimeMax) {
                        currentLimit++
                        fastSuccesses = 0
                    }
                }

                durationMillis >= slowListingMillis -> {
                    fastSuccesses = 0
                    currentLimit = maxOf(minLimit, currentLimit - 1).coerceAtMost(runtimeMax)
                }

                else -> fastSuccesses = 0
            }
        }
    }

    private fun clampLimitLocked(): Int {
        val runtimeMax = runtimeMaxLocked()
        currentLimit = currentLimit.coerceIn(minLimit, runtimeMax)
        return runtimeMax
    }

    private fun runtimeMaxLocked(): Int {
        return runtimeMaxConcurrencyProvider().coerceIn(minLimit, maxLimit)
    }

    companion object {
        const val DEFAULT_MIN_CONCURRENCY = 1
        const val DEFAULT_INITIAL_CONCURRENCY = 8
        const val DEFAULT_MAX_CONCURRENCY = 24
        const val DEFAULT_FAST_LISTING_MILLIS = 250L
        const val DEFAULT_SLOW_LISTING_MILLIS = 1_500L
        const val DEFAULT_ACQUIRE_POLL_MILLIS = 10L
    }
}

internal fun defaultDirectoryListLimiter(
    initialConcurrency: Int = DynamicDirectoryListLimiter.DEFAULT_INITIAL_CONCURRENCY,
    maxConcurrency: Int = DynamicDirectoryListLimiter.DEFAULT_MAX_CONCURRENCY,
): DynamicDirectoryListLimiter {
    return DynamicDirectoryListLimiter(
        initialConcurrency = initialConcurrency,
        maxConcurrency = maxConcurrency,
        runtimeMaxConcurrencyProvider = { defaultRuntimeListMaxConcurrency(maxConcurrency) },
    )
}

internal fun defaultRuntimeListMaxConcurrency(maxConcurrency: Int): Int {
    val plan = HttpTransferRuntimeTuning.plan(
        maxChunkBytes = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
        maxParallelRequests = maxConcurrency.coerceAtLeast(1),
    )
    val busyBound = if (plan.busy) 2 else maxConcurrency
    return minOf(plan.recommendedParallelRequests, busyBound, maxConcurrency).coerceAtLeast(1)
}
