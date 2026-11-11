package com.folderspan.service.operation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import com.folderspan.service.session.DeviceSessionCredit
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.session.DeviceSessionTransferBudget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class OperationTransferWaitingTest {
    @Test fun realCreditWaitClearsWhenTheReceiverGrantsCredit() = runTest {
        val credit = DeviceSessionCredit(DeviceSessionWindowPlan(1, 1, 1))
        credit.tryConsume(1, 1)
        val events = mutableListOf<Pair<TransferWaitReason, Boolean>>()
        val waiter = launch(OperationTransferWaitObserver { reason, active -> events += reason to active }) {
            credit.awaitAndConsume(2, 1)
        }
        runCurrent()
        assertEquals(listOf(TransferWaitReason.Receiver to true), events)
        credit.grant(1, 1)
        waiter.join()
        assertEquals(listOf(TransferWaitReason.Receiver to true, TransferWaitReason.Receiver to false), events)
    }

    @Test fun realBudgetWaitShowsBusyThenBackoffAndClearsAfterRecovery() = runTest {
        val budget = DeviceSessionTransferBudget(1, nowMillis = { 10_000 })
        var status = HttpTransferStatus(busy = true, sampledAtMillis = 10_000)
        budget.bind(1) { status }
        val events = mutableListOf<Pair<TransferWaitReason, Boolean>>()
        var dispatched = false
        try {
            val waiter = launch(OperationTransferWaitObserver { reason, active -> events += reason to active }) {
                budget.withPermit { dispatched = true }
            }
            runCurrent()
            assertEquals(listOf(TransferWaitReason.DeviceBusy to true), events)
            status = status.copy(retryAfterMillis = 100)
            testScheduler.advanceTimeBy(100)
            runCurrent()
            assertEquals(TransferWaitReason.Backoff to true, events.last())
            assertEquals(false, dispatched)
            status = status.copy(busy = false, retryAfterMillis = 0)
            advanceUntilIdle()
            waiter.join()
            assertEquals(true, dispatched)
            assertEquals(TransferWaitReason.Backoff to false, events.last())
        } finally { budget.close() }
    }

    @Test fun overlappingWaitsRemainIndependentAndCloseOnlyOnce() = runTest {
        val counts = mutableMapOf<TransferWaitReason, Int>()
        withContext(OperationTransferWaitObserver { reason, active ->
            counts[reason] = counts.getOrElse(reason) { 0 } + if (active) 1 else -1
        }) {
            val first = observeOperationTransferWait(TransferWaitReason.Receiver)
            val second = async { observeOperationTransferWait(TransferWaitReason.Receiver) }.await()
            assertEquals(2, counts[TransferWaitReason.Receiver])
            first(); first()
            assertEquals(1, counts[TransferWaitReason.Receiver])
            second()
            assertEquals(0, counts[TransferWaitReason.Receiver])
        }
    }

    @Test fun canceledWaitClearsItsObservationWithoutMarkingFailure() = runTest {
        val events = mutableListOf<Pair<TransferWaitReason, Boolean>>()
        val entered = CompletableDeferred<Unit>()
        val feedback = OperationRequestFeedback { testScheduler.currentTime }
        val waiter = launch(feedback + OperationTransferWaitObserver { reason, active -> events += reason to active }) {
            val close = observeOperationTransferWait(TransferWaitReason.Backoff)
            try { entered.complete(Unit); awaitCancellation() } finally { close() }
        }
        entered.await()
        waiter.cancelAndJoin()
        assertEquals(listOf(TransferWaitReason.Backoff to true, TransferWaitReason.Backoff to false), events)
        assertEquals(false, feedback.failed)
    }
}
