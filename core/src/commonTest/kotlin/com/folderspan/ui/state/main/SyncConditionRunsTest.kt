@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.ui.state.main

import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.service.sync.SyncPlatformConditions
import com.folderspan.service.sync.SyncRunCondition
import com.folderspan.service.sync.beforeSyncConditionFile
import com.folderspan.service.sync.withSyncConditionPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncConditionRunsTest {
    private class Fixture(val test: TestScope) {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Cellular, false))
        val tasks = mutableMapOf<Long, SyncTask>()
        val queue = SyncRunQueue(test.backgroundScope)
        var dispatched = 0
        var interruptions = 0
        val userCanceled = mutableSetOf<Long>()
        val runs = SyncConditionRuns(test.backgroundScope, { platform }, tasks::get, { it.ownerDeviceId == "local" },
            { false }, { task, block -> queue.enqueue(task.id, { dispatched++ }) { queue.withPermit(block) } },
            { interruptions++ }, {}, { it in userCanceled })
        fun task(id: Long = 1) = SyncTask(id, "Condition $id", sourceType = SyncEndpointType.Local,
            sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target",
            ownerDeviceId = "local", requireWifi = true, nextRunAt = 123).also { tasks[id] = it }
        fun ready() { platform.value = platform.value.copy(network = SyncNetworkType.Wifi, charging = true) }
    }

    private fun withFixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(Fixture(this)) } finally { Dispatchers.resetMain() }
    }

    @Test fun waitingCoalescesBeforeQueueAndLeavesPermitsForUnrelatedRuns() = withFixture { f ->
        val task = f.task()
        var ran = 0
        repeat(4) { assertTrue(f.runs.offer(task) { ran++ }) }
        runCurrent()
        assertEquals(0, f.dispatched); assertEquals(0, ran)
        assertEquals(setOf(SyncRunCondition.Wifi), f.runs.waitingConditions[task.id])
        repeat(2) { id -> f.runs.offer(f.task(id + 2L).copy(requireWifi = false)) { ran++ } }
        runCurrent(); assertEquals(2, ran)
        f.ready(); runCurrent()
        assertEquals(3, ran); assertFalse(task.id in f.runs.waitingConditions)
        assertEquals(123, f.tasks.getValue(task.id).nextRunAt)
    }

    @Test fun userCancelChangedDefinitionAndForeignOwnershipInvalidateDeferredRequests() = withFixture { f ->
        val canceled = f.task(1); val changed = f.task(2); val foreign = f.task(3)
        var ran = 0
        for (task in listOf(canceled, changed, foreign)) f.runs.offer(task) { ran++ }
        assertTrue(f.runs.cancel(canceled.id))
        f.tasks[changed.id] = changed.copy(requireCharging = true)
        f.tasks[foreign.id] = foreign.copy(ownerDeviceId = "other")
        f.ready(); runCurrent(); f.runs.drain(); runCurrent()
        assertEquals(0, ran); assertTrue(f.runs.waitingConditions.isEmpty())
    }

    @Test fun ignoreConditionsBelongsToOneRequestAndDoesNotInterruptThatRun() = withFixture { f ->
        val task = f.task()
        var ran = false
        f.runs.offer(task, ignore = true) { f.runs.execute(task, {}) {
            withSyncConditionPath { beforeSyncConditionFile(); ran = true }
        } }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertTrue(ran); assertEquals(0, f.interruptions)
        f.runs.offer(task) { error("The next request must wait") }; runCurrent()
        assertEquals(setOf(SyncRunCondition.Wifi), f.runs.waitingConditions[task.id])
    }

    @Test fun completedFileStopsImmediatelyAndRestartsOnceWhenConditionsReturn() = withFixture { f ->
        val task = f.task()
        f.ready()
        val finishFile = CompletableDeferred<Unit>()
        var attempts = 0
        var recorded = 0
        lateinit var run: suspend () -> Unit
        run = { f.runs.execute(task, run) {
            attempts++
            withSyncConditionPath { beforeSyncConditionFile(); finishFile.await(); recorded++ }
        } }
        f.runs.offer(task, run = run); runCurrent()
        f.platform.value = f.platform.value.copy(network = SyncNetworkType.Cellular); runCurrent()
        assertEquals(0, f.interruptions)
        finishFile.complete(Unit); runCurrent()
        assertEquals(1, recorded); assertEquals(1, f.interruptions)
        assertEquals(setOf(SyncRunCondition.Wifi), f.runs.waitingConditions[task.id])
        f.ready(); runCurrent(); f.runs.drain(); runCurrent()
        assertEquals(2, attempts); assertEquals(2, recorded); assertEquals(1, f.interruptions)
        assertTrue(f.runs.waitingConditions.isEmpty())
    }

    @Test fun timeoutOnlyCancelsItsRunAndUserCancellationDoesNotRequeueIt() = withFixture { f ->
        val task = f.task()
        f.ready()
        f.runs.offer(task) { f.runs.execute(task, {}) { withSyncConditionPath { awaitCancellation() } } }
        val other = CompletableDeferred<Unit>()
        f.runs.offer(f.task(2).copy(requireWifi = false)) { other.await() }
        runCurrent()
        f.platform.value = f.platform.value.copy(network = SyncNetworkType.Cellular); runCurrent()
        advanceTimeBy(59_999); runCurrent(); assertEquals(0, f.interruptions)
        advanceTimeBy(1); runCurrent(); assertEquals(1, f.interruptions)
        assertFalse(other.isCancelled)
        f.runs.cancel(task.id); f.ready(); runCurrent()
        assertEquals(2, f.dispatched)
        other.complete(Unit)
    }


    @Test fun conditionsAreRecheckedAfterQueueingWithoutHoldingAPermitWhileWaiting() = withFixture { f ->
        f.ready()
        val release = CompletableDeferred<Unit>()
        repeat(2) { id -> f.runs.offer(f.task(id + 2L).copy(requireWifi = false)) { release.await() } }
        runCurrent()
        val task = f.task()
        var ran = false
        f.runs.offer(task) { f.runs.execute(task, {}) { ran = true } }
        runCurrent()
        f.platform.value = f.platform.value.copy(network = SyncNetworkType.Cellular)
        release.complete(Unit); runCurrent()
        assertFalse(ran)
        assertEquals(setOf(SyncRunCondition.Wifi), f.runs.waitingConditions[task.id])
        var unrelated = false
        f.runs.offer(f.task(4).copy(requireWifi = false)) { unrelated = true }; runCurrent()
        assertTrue(unrelated)
        f.ready(); runCurrent()
        assertTrue(ran)
    }

    @Test fun aUserCanceledOperationCannotBeRestartedByConcurrentConditionLoss() = withFixture { f ->
        f.ready()
        val task = f.task()
        val finish = CompletableDeferred<Unit>()
        f.runs.offer(task) { f.runs.execute(task, {}) { withSyncConditionPath { finish.await() } } }
        runCurrent()
        f.userCanceled += task.id
        f.platform.value = f.platform.value.copy(network = SyncNetworkType.Cellular); runCurrent()
        finish.complete(Unit); runCurrent()
        assertEquals(1, f.interruptions)
        assertFalse(task.id in f.runs.waitingConditions)
        f.ready(); runCurrent(); f.runs.drain()
        assertEquals(1, f.dispatched)
    }

    @Test fun desktopConditionsRemainInactive() = withFixture { f ->
        f.platform.value = f.platform.value.copy(supportsConditions = false)
        var ran = false
        f.runs.offer(f.task()) { ran = true }; runCurrent()
        assertTrue(ran); assertTrue(f.runs.waitingConditions.isEmpty())
    }
}
