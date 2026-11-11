@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.ui.state.main

import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.service.sync.SyncPlatformConditions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEventTriggerCoordinatorTest {
    private fun task(id: Long = 1, owner: String = "local") = SyncTask(id = id, name = "Event $id",
        sourceType = SyncEndpointType.Local, sourcePath = "/source", targetType = SyncEndpointType.Network,
        targetRef = "nas", targetPath = "/target", ownerDeviceId = owner, nextRunAt = 123_456_789,
        triggerOnNetworkAvailable = true)

    private class Fixture(val test: TestScope) {
        var tasks = listOf(SyncEventTriggerCoordinatorTest().task())
        var probes = 0
        var ready: suspend (SyncTask) -> Boolean = { true }
        var accepts = true
        val requested = mutableListOf<Long>()
        val coordinator = SyncEventTriggerCoordinator(test.backgroundScope,
            tasks = { tasks }, owned = { it.ownerDeviceId == "local" },
            probeNetwork = { probes++; ready(it) },
            requestRun = { if (accepts) requested += it.id; accepts }, nowMillis = { test.testScheduler.currentTime })
    }

    @Test fun deviceEventsMatchBothSourceAndSavedTargetsAndSkipForeignOrUnrelatedTasks() = runTest {
        val fixture = Fixture(this)
        val selected = task().copy(sourceType = SyncEndpointType.Device, sourceRef = "source-device",
            triggerOnDeviceConnect = true, triggerOnNetworkAvailable = false,
            destinations = listOf(StorageDestination(SyncEndpointType.Device, "target-device", "/copy")))
        fixture.tasks = listOf(selected, selected.copy(id = 2, ownerDeviceId = "other"), selected.copy(id = 3,
            sourceRef = "different", destinations = emptyList(), targetType = SyncEndpointType.Local))
        fixture.coordinator.devicesChanged(setOf("source-device"))
        assertTrue(fixture.requested.isEmpty(), "Initial connected devices only establish a baseline")
        fixture.coordinator.devicesChanged(emptySet())
        fixture.coordinator.devicesChanged(setOf("unrelated"))
        assertTrue(fixture.requested.isEmpty())
        fixture.coordinator.devicesChanged(setOf("unrelated", "target-device"))
        assertEquals(listOf(1L), fixture.requested)
        assertEquals(0, fixture.probes)
    }

    @Test fun frequentReconnectsShareOneTenMinuteIntervalIncludingItsExactBoundary() = runTest {
        val fixture = Fixture(this)
        fixture.tasks = listOf(task().copy(targetType = SyncEndpointType.Device, targetRef = "device",
            triggerOnDeviceConnect = true, triggerOnNetworkAvailable = false, enabled = false))
        fixture.coordinator.devicesChanged(emptySet())
        fixture.coordinator.devicesChanged(setOf("device"))
        repeat(3) {
            fixture.coordinator.devicesChanged(emptySet())
            fixture.coordinator.devicesChanged(setOf("device"))
        }
        advanceTimeBy(599_999)
        fixture.coordinator.devicesChanged(emptySet()); fixture.coordinator.devicesChanged(setOf("device"))
        assertEquals(listOf(1L), fixture.requested)
        advanceTimeBy(1)
        fixture.coordinator.devicesChanged(emptySet()); fixture.coordinator.devicesChanged(setOf("device"))
        assertEquals(listOf(1L, 1L), fixture.requested)
        assertEquals(123_456_789, fixture.tasks.single().nextRunAt)
    }

    @Test fun networkChangesDebounceForFiveSecondsAndLossCancelsPendingWork() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(4_999)
        assertEquals(0, fixture.probes)
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(4_999)
        assertEquals(0, fixture.probes)
        fixture.coordinator.networkChanged(false); runCurrent(); advanceTimeBy(20_000)
        assertEquals(0, fixture.probes)
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, fixture.probes)
        assertEquals(listOf(1L), fixture.requested)
    }

    @Test fun failedProbesCreateNoRunsAndDoNotPreventAnotherNetworkChangeFromTryingAgain() = runTest {
        val fixture = Fixture(this)
        fixture.ready = { error("offline") }
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, fixture.probes)
        assertTrue(fixture.requested.isEmpty())
        assertEquals(SyncRunStatus.Idle, fixture.tasks.single().lastStatus)
        fixture.ready = { true }
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(1L), fixture.requested)
        assertEquals(123_456_789, fixture.tasks.single().nextRunAt)
    }

    @Test fun timedOutProbeStopsWithoutRequestingARun() = runTest {
        val fixture = Fixture(this)
        var stopped = 0
        fixture.ready = { try { awaitCancellation() } finally { stopped++ } }
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(15_000); runCurrent()
        assertEquals(1, fixture.probes)
        assertEquals(1, stopped)
        assertTrue(fixture.requested.isEmpty())
    }

    @Test fun changedDefinitionsAndOwnershipAreRecheckedAfterTheProbe() = runTest {
        for (changeOwner in listOf(false, true)) {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.ready = { entered.complete(Unit); release.await(); true }
            fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
            entered.await()
            fixture.tasks = listOf(if (changeOwner) fixture.tasks.single().copy(ownerDeviceId = "other")
                else fixture.tasks.single().copy(sourcePath = "/new-source"))
            release.complete(Unit); runCurrent()
            assertTrue(fixture.requested.isEmpty())
            fixture.coordinator.resetNetwork()
        }
    }

    @Test fun initialSnapshotsAndChargingEventsDoNotPretendTheNetworkReconnected() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.platformNetworkChanged(SyncPlatformConditions(true))
        fixture.coordinator.platformNetworkChanged(SyncPlatformConditions(true, SyncNetworkType.Wifi))
        advanceTimeBy(6_000); runCurrent()
        assertEquals(0, fixture.probes)
        fixture.coordinator.platformNetworkChanged(SyncPlatformConditions(true, SyncNetworkType.Unknown, networkRevision = 1))
        fixture.coordinator.platformNetworkChanged(SyncPlatformConditions(true, SyncNetworkType.Wifi, networkRevision = 1))
        runCurrent(); advanceTimeBy(2_000)
        fixture.coordinator.platformNetworkChanged(SyncPlatformConditions(true, SyncNetworkType.Wifi, true, 1))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, fixture.probes)
        assertEquals(listOf(1L), fixture.requested)
    }

    @Test fun ignoredRequestsDoNotConsumeTheIntervalAndIneligibleTasksAreNeverProbed() = runTest {
        val fixture = Fixture(this)
        fixture.tasks = listOf(task(), task(2, "other"), task(3).copy(triggerOnNetworkAvailable = false),
            task(4).copy(targetType = SyncEndpointType.Local))
        fixture.accepts = false
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, fixture.probes)
        fixture.accepts = true
        fixture.coordinator.networkChanged(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, fixture.probes)
        assertEquals(listOf(1L), fixture.requested)
    }
}
