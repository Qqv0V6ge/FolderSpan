@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.service.session

import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.operation.HttpTransferStatus
import com.folderspan.service.operation.OperationRequestFeedback
import com.folderspan.service.operation.reportOperationBytes
import com.folderspan.service.operation.withOperationTransferControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSessionTransferBudgetTest {
    private fun status(now: Long, parallelism: Int = 4) = HttpTransferStatus(
        sampledAtMillis = now, recommendedParallelRequests = parallelism, maxParallelRequests = 4)

    @Test fun loweringTheRecommendationIsImmediateAndRecoveryIsGradual() = runTest {
        var now = 10_000L
        var sample = status(now)
        val budget = DeviceSessionTransferBudget(4) { now }
        budget.bind(4) { sample }
        repeat(12) { budget.withPermit {} }
        assertEquals(4, budget.parallelismLimit())
        sample = status(now, 1)
        budget.withPermit {}
        assertEquals(1, budget.parallelismLimit())
        sample = status(now, 4)
        budget.withPermit {}
        assertTrue(budget.parallelismLimit() in 1..2)
        repeat(12) { budget.withPermit {} }
        assertEquals(4, budget.parallelismLimit())
        now += 5_000
        budget.withPermit {}
        assertEquals(1, budget.parallelismLimit(), "Reading an expired snapshot cannot keep its four-file window")
    }

    @Test fun threeTasksShareOneBudgetAndALowerLimitDoesNotCancelExistingFiles() = runTest {
        val now = 10_000L
        var sample = status(now)
        val budget = DeviceSessionTransferBudget(4) { now }
        budget.bind(4) { sample }
        repeat(12) { budget.withPermit {} }
        var active = 0
        var peak = 0
        val jobs = List(3) {
            async {
                List(2) { async {
                    repeat(3) {
                        budget.withPermit {
                            active++
                            peak = maxOf(peak, active)
                            assertTrue(active <= 4)
                            delay(10)
                            active--
                        }
                    }
                } }.awaitAll()
            }
        }
        jobs.awaitAll()
        assertEquals(4, peak)
        val entered = MutableStateFlow(0)
        val release = CompletableDeferred<Unit>()
        val running = List(4) { async { budget.withPermit { entered.value++; release.await() } } }
        entered.first { it == 4 }
        sample = status(now, 1)
        var nextStarted = false
        val next = async { budget.withPermit { nextStarted = true } }
        runCurrent()
        assertFalse(nextStarted)
        assertEquals(4, entered.value)
        release.complete(Unit)
        (running + next).awaitAll()
        assertTrue(nextStarted)
        assertEquals(1, budget.parallelismLimit())
    }

    @Test fun busyDeviceDoesNotBlockAnotherDeviceAndCloseReleasesItsWaiters() = runTest {
        val blocked = DeviceSessionTransferBudget(4) { 10_000 }
        blocked.bind(4) { status(10_000).copy(busy = true, retryAfterMillis = 5_000) }
        val other = DeviceSessionTransferBudget(1)
        var dispatched = false
        val waiting = async { runCatching { blocked.withPermit { dispatched = true } } }
        runCurrent()
        other.withPermit { assertFalse(dispatched) }
        blocked.close()
        advanceTimeBy(10)
        runCurrent()
        assertIs<DeviceSessionClosedException>(waiting.await().exceptionOrNull())
        assertFalse(dispatched)
        other.withPermit {}
    }

    @Test fun pauseAndCancellationInterruptBackoffWithoutWaitingForTheRemoteDeadline() = runTest {
        val state = MutableStateFlow("paused")
        val budget = DeviceSessionTransferBudget(1) { 10_000 }
        budget.bind(1) { status(10_000).copy(busy = true, retryAfterMillis = 5_000) }
        var dispatched = false
        val waiting = async {
            withOperationTransferControl({
                if (state.first { it != "paused" } == "cancelled") throw CancellationException("user")
            }) { budget.withPermit { dispatched = true } }
        }
        runCurrent()
        advanceTimeBy(200)
        assertFalse(dispatched)
        state.value = "running"
        runCurrent()
        assertFalse(dispatched)
        state.value = "cancelled"
        advanceTimeBy(50)
        runCurrent()
        assertTrue(waiting.isCancelled)
        assertFalse(dispatched)
        budget.close()
    }

    @Test fun aPausedTaskDoesNotStartEvenWhenItsDeviceHasCapacity() = runTest {
        val resume = CompletableDeferred<Unit>()
        val budget = DeviceSessionTransferBudget(1)
        var dispatched = false
        val waiting = async {
            withOperationTransferControl({ resume.await() }) { budget.withPermit { dispatched = true } }
        }
        advanceTimeBy(500)
        assertFalse(dispatched)
        resume.complete(Unit)
        waiting.await()
        assertTrue(dispatched)
    }

    @Test fun oppositeDirectionCopiesAcquireBothEndpointsInTheSameOrderAndNestedStreamsReusePermits() = runTest {
        fun client(): DeviceSessionClients {
            val channel = object : DeviceSessionByteChannel {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("no I/O")
                override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { error("no I/O") }
                override suspend fun flush() = Unit
                override fun close() = Unit
            }
            val plan = DeviceSessionWindowPlan(65_536, 65_536, 1)
            val peer = DeviceSessionPeer(DeviceSessionTransport(channel, plan, plan, true), plan)
            return createDeviceSessionClients(peer, status(0, 1), FolderSpanArchiveStreamCapabilities.unsupported())
        }
        val left = client()
        val right = client()
        var active = 0
        val jobs = List(12) { index -> async {
            val source = if (index % 2 == 0) left else right
            val target = if (index % 2 == 0) right else left
            withDeviceTransferBudgets(source, target) {
                withDeviceTransferBudgets(target, source) {
                    assertEquals(1, ++active)
                    delay(10)
                    active--
                }
            }
        } }
        jobs.awaitAll()
        assertEquals(0, active)
        left.transferBudget.close(); right.transferBudget.close()
    }

    @Test fun sessionPermitsDoNotHideRealBytesFromTheOuterFileFeedback() = runTest {
        var now = 0L
        val feedback = OperationRequestFeedback { now }
        val budget = DeviceSessionTransferBudget(1)
        withContext(feedback) {
            budget.withPermit {
                repeat(20) { now += 250; reportOperationBytes(65_536) }
            }
        }
        assertEquals(250L, feedback.latencyMillis())
    }
}
