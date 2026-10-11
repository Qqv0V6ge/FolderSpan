package com.folderspan.service.path

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DynamicDirectoryListLimiterTest {
    @Test
    fun limiterHonorsRuntimeMaxWhileAcquiring() = runSuspendTest {
        val limiter = DynamicDirectoryListLimiter(
            initialConcurrency = 4,
            maxConcurrency = 8,
            runtimeMaxConcurrencyProvider = { 2 },
            acquirePollMillis = 1,
        )
        val mutex = Mutex()
        var activeRequests = 0
        var maxActiveRequests = 0

        coroutineScope {
            List(6) {
                async {
                    limiter.withPermit {
                        mutex.withLock {
                            activeRequests++
                            maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                        }
                        delay(20.milliseconds)
                        mutex.withLock { activeRequests-- }
                    }
                }
            }.awaitAll()
        }

        assertEquals(2, maxActiveRequests)
        assertEquals(2, limiter.snapshot().currentLimit)
    }

    @Test
    fun limiterRaisesLimitAfterFastListings() = runSuspendTest {
        val limiter = DynamicDirectoryListLimiter(
            minConcurrency = 1,
            initialConcurrency = 1,
            maxConcurrency = 4,
            runtimeMaxConcurrencyProvider = { 4 },
            fastListingMillis = 1_000,
            slowListingMillis = 10_000,
            acquirePollMillis = 1,
        )

        repeat(3) {
            limiter.withPermit { }
        }

        assertTrue(limiter.snapshot().currentLimit > 1)
    }

    @Test
    fun limiterReducesLimitAfterSlowListingAndFailure() = runSuspendTest {
        val limiter = DynamicDirectoryListLimiter(
            minConcurrency = 1,
            initialConcurrency = 4,
            maxConcurrency = 8,
            runtimeMaxConcurrencyProvider = { 8 },
            fastListingMillis = 1,
            slowListingMillis = 5,
            acquirePollMillis = 1,
        )

        limiter.withPermit {
            delay(20.milliseconds)
        }

        assertEquals(3, limiter.snapshot().currentLimit)
        assertFailsWith<IllegalStateException> {
            limiter.withPermit {
                throw IllegalStateException("boom")
            }
        }
        assertEquals(1, limiter.snapshot().currentLimit)
    }

    @Test
    fun limiterUsesReducedCurrentLimitWhenAcquiring() = runSuspendTest {
        val limiter = DynamicDirectoryListLimiter(
            minConcurrency = 1,
            initialConcurrency = 4,
            maxConcurrency = 4,
            runtimeMaxConcurrencyProvider = { 4 },
            fastListingMillis = 0,
            slowListingMillis = 10_000,
            acquirePollMillis = 1,
        )
        repeat(2) {
            assertFailsWith<IllegalStateException> {
                limiter.withPermit {
                    throw IllegalStateException("boom")
                }
            }
        }
        assertEquals(1, limiter.snapshot().currentLimit)

        val mutex = Mutex()
        var activeRequests = 0
        var maxActiveRequests = 0
        coroutineScope {
            List(4) {
                async {
                    limiter.withPermit {
                        mutex.withLock {
                            activeRequests++
                            maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                        }
                        delay(20.milliseconds)
                        mutex.withLock { activeRequests-- }
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, maxActiveRequests)
    }

    @Test
    fun limiterReleasesPermitWhenRequestIsCancelled() = runSuspendTest {
        coroutineScope {
            val limiter = DynamicDirectoryListLimiter(
                minConcurrency = 1,
                initialConcurrency = 1,
                maxConcurrency = 1,
                runtimeMaxConcurrencyProvider = { 1 },
                acquirePollMillis = 1,
            )
            val started = CompletableDeferred<Unit>()
            val job = launch {
                limiter.withPermit {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE.milliseconds)
                }
            }

            started.await()
            assertEquals(1, limiter.snapshot().activeRequests)
            job.cancelAndJoin()
            assertEquals(0, limiter.snapshot().activeRequests)
        }
    }
}
