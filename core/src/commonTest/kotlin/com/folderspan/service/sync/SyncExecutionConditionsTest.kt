@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.service.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncExecutionConditionsTest {
    @Test fun conditionLossWaitsForCurrentFileAndItsResultButRejectsNewPaths() = runTest {
        var missing = emptySet<SyncRunCondition>()
        val stop = CompletableDeferred<Set<SyncRunCondition>>()
        val gate = SyncExecutionConditions(backgroundScope, { missing }, { stop.complete(it) })
        val transfer = CompletableDeferred<Unit>()
        val record = CompletableDeferred<Unit>()
        var recorded = false
        val current = launch(gate) { withSyncConditionPath {
            beforeSyncConditionFile(); transfer.await(); record.await(); recorded = true
        } }
        runCurrent()
        missing = setOf(SyncRunCondition.Wifi); gate.check()
        var nextStarted = false
        val next = launch(gate) { withSyncConditionPath { nextStarted = true } }
        transfer.complete(Unit); runCurrent()
        assertFalse(stop.isCompleted); assertFalse(nextStarted)
        record.complete(Unit); runCurrent()
        assertTrue(recorded); assertEquals(missing, stop.await())
        current.join(); next.cancelAndJoin(); gate.close()
    }

    @Test fun nextFileOfTheSamePathCannotExtendGraceAndUnrelatedJobsKeepRunning() = runTest {
        var missing = emptySet<SyncRunCondition>()
        val owner = Job()
        var reason = emptySet<SyncRunCondition>()
        val gate = SyncExecutionConditions(backgroundScope, { missing }, { reason = it; owner.cancel() })
        val first = CompletableDeferred<Unit>()
        var nextStarted = false
        var unrelatedFinished = false
        val unrelated = launch { unrelatedFinished = true }
        val current = launch(owner + gate) { withSyncConditionPath {
            first.await(); beforeSyncConditionFile(); nextStarted = true
        } }
        runCurrent()
        missing = setOf(SyncRunCondition.Charging); gate.check()
        first.complete(Unit); runCurrent()
        assertTrue(current.isCancelled); assertFalse(nextStarted)
        assertEquals(missing, reason); unrelated.join(); assertTrue(unrelatedFinished)
        gate.close()
    }

    @Test fun sixtySecondDeadlineIsNotResetByRepeatedOrChangedConditions() = runTest {
        var missing = emptySet<SyncRunCondition>()
        val owner = Job()
        var stoppedAt = -1L
        val gate = SyncExecutionConditions(backgroundScope, { missing }, {
            stoppedAt = testScheduler.currentTime; owner.cancel()
        })
        val current = launch(owner + gate) { withSyncConditionPath { CompletableDeferred<Unit>().await() } }
        runCurrent()
        missing = setOf(SyncRunCondition.Wifi); gate.check()
        advanceTimeBy(30_000)
        missing = setOf(SyncRunCondition.Charging); gate.check()
        advanceTimeBy(29_999); runCurrent(); assertEquals(-1, stoppedAt)
        advanceTimeBy(1); runCurrent()
        assertEquals(60_000, stoppedAt); assertTrue(current.isCancelled)
        gate.close()
    }

    @Test fun synchronousConditionRecheckPreventsDispatchBeforeTheWatcherRuns() = runTest {
        var missing = emptySet<SyncRunCondition>()
        val owner = Job()
        val gate = SyncExecutionConditions(backgroundScope, { missing }, { owner.cancel() })
        var nextStarted = false
        val current = launch(owner + gate) { withSyncConditionPath {
            missing = setOf(SyncRunCondition.Wifi)
            beforeSyncConditionFile(); nextStarted = true
        } }
        runCurrent(); assertTrue(current.isCancelled); assertFalse(nextStarted)
        gate.close()
    }

    @Test fun nestedSerialWorkStaysInsideItsParentResultBoundaryAndNoGateLeavesOrdinaryCopiesAlone() = runTest {
        var missing = emptySet<SyncRunCondition>()
        var stopped = false
        val gate = SyncExecutionConditions(backgroundScope, { missing }, { stopped = true })
        withContext(gate) { withSyncConditionPath {
            withSyncConditionPath { beforeSyncConditionFile() }
            missing = setOf(SyncRunCondition.Wifi); gate.check()
            assertFalse(stopped)
        } }
        assertTrue(stopped); gate.close()
        var ran = false
        withSyncConditionPath { beforeSyncConditionFile(); ran = true }
        assertTrue(ran)
    }
}
