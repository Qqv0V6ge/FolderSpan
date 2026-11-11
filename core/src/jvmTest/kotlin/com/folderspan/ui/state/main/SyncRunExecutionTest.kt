package com.folderspan.ui.state.main

import java.util.concurrent.atomic.AtomicInteger
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.session.DeviceSessionTransferBudget
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRunExecutionTest {
    @Test fun cancelledCallerCannotEnterAnImmediatelyAvailableQueuePermit() = runBlocking {
        coroutineScope {
            val queue = SyncRunQueue(this)
            var ran = false
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                queue.withPermit { ran = true }
            }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            assertFalse(ran)
        }
    }

    @Test fun continuousBusyFeedbackStillReachesTheByteStallTimeout() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val budget = DeviceSessionTransferBudget(4)
        val updates = AtomicInteger()
        var dispatched = false
        budget.bind(4) {
            updates.incrementAndGet()
            HttpTransferStatus(busy = true, retryAfterMillis = 5_000,
                sampledAtMillis = System.currentTimeMillis())
        }
        try {
            val result = withTimeout(2_000) {
                copySyncFileWithRetry(scope, 0, 50, { null }, {}, {}, {}, pollMillis = 2) {
                    budget.withPermit { dispatched = true; Result.success(true) }
                }
            }
            assertEquals("Sync transfer stalled", result.exceptionOrNull()?.message)
            assertTrue(updates.get() > 1)
            assertFalse(dispatched)
            DeviceSessionTransferBudget(1).withPermit {}
        } finally { budget.close(); owner.cancelAndJoin() }
    }

    @Test
    fun queueLimitsAllRunsAndCancellationRemovesWaitingWork() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val queue = SyncRunQueue(scope)
        val active = AtomicInteger()
        val entered = AtomicInteger()
        val finished = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            for (id in 1L..2L) assertTrue(queue.enqueue(id, {}) {
                queue.withPermit {
                    active.incrementAndGet()
                    entered.incrementAndGet()
                    release.await()
                    active.decrementAndGet()
                }
            })
            withTimeout(2_000) { while (entered.get() != 2) delay(1) }
            assertEquals(2, active.get())
            assertFalse(queue.enqueue(1, {}) { error("duplicate ran") })
            assertTrue(queue.enqueue(3, {}) { queue.withPermit { error("cancelled queue item ran") } })
            queue.cancel(3)
            assertTrue(queue.enqueue(4, {}) { queue.withPermit { finished.complete(Unit) } })
            assertFalse(finished.isCompleted)
            release.complete(Unit)
            withTimeout(2_000) { finished.await() }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun failedCopyCleansNewTargetAndRetriesOnlyUntilSuccess() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        var attempts = 0
        var cleanups = 0
        try {
            val result = copySyncFileWithRetry(scope, 2, 0, { null }, {}, {}, { cleanups++ }, pollMillis = 1) {
                attempts++
                if (attempts == 1) Result.failure(IllegalStateException("temporary")) else Result.success(true)
            }
            assertTrue(result.getOrThrow())
            assertEquals(2, attempts)
            assertEquals(1, cleanups)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun stalledCopyStopsBeforeRetryAndCleansFinalFailure() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val attempts = AtomicInteger()
        var stopped = 0
        var cleaned = 0
        try {
            val result = copySyncFileWithRetry(scope, 1, 15, { null }, {}, { stopped++ }, { cleaned++ }, pollMillis = 1) {
                attempts.incrementAndGet()
                awaitCancellation()
            }
            assertTrue(result.isFailure)
            assertEquals(2, attempts.get())
            assertEquals(2, stopped)
            assertEquals(2, cleaned)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun cancellingCopyDoesNotRetryAndStopsAttempt() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val cleaned = AtomicInteger()
        try {
            val run = launch {
                copySyncFileWithRetry(scope, 5, 0, { null }, {}, {}, { cleaned.incrementAndGet() }, pollMillis = 1) {
                    attempts.incrementAndGet()
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            run.cancelAndJoin()
            assertEquals(1, attempts.get())
            assertEquals(1, cleaned.get())
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun oldAttemptThatCannotStopPreventsRetryAndCleanupRaces() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        var attempts = 0
        var cleanup = 0
        try {
            val result = copySyncFileWithRetry(scope, 3, 10, { null }, {}, {}, { cleanup++ },
                pollMillis = 1, stopWaitMillis = 10) {
                attempts++
                withContext(NonCancellable) { delay(150) }
                Result.success(true)
            }
            assertTrue(result.isFailure)
            assertEquals(1, attempts)
            assertEquals(0, cleanup)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun pausedTimeDoesNotConsumeTransferStallTimeout() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val resume = CompletableDeferred<Unit>()
        var clock = 0L
        var checks = 0
        var stops = 0
        try {
            val result = copySyncFileWithRetry(scope, 0, 100, { null }, {
                if (++checks == 2) {
                    clock += 10_000
                    resume.complete(Unit)
                }
            }, { stops++ }, {}, now = { clock }, pollMillis = 1) {
                resume.await()
                Result.success(true)
            }
            assertTrue(result.getOrThrow())
            assertEquals(0, stops)
        } finally { job.cancelAndJoin() }
    }
}
