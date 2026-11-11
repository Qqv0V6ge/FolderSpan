@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.service.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPlatformConditionsTest {
    @Test fun unknownOrCellularConditionsNeverAuthorizeWifiOrChargingRequirements() {
        val both = setOf(SyncRunCondition.Wifi, SyncRunCondition.Charging)
        assertEquals(both, SyncPlatformConditions(true).unmetConditions(true, true))
        assertEquals(setOf(SyncRunCondition.Wifi), SyncPlatformConditions(true, SyncNetworkType.Cellular, true).unmetConditions(true, true))
        assertEquals(setOf(SyncRunCondition.Charging), SyncPlatformConditions(true, SyncNetworkType.Wifi, false).unmetConditions(true, true))
        assertTrue(SyncPlatformConditions(true, SyncNetworkType.Ethernet, true).unmetConditions(true, true).isEmpty())
        assertTrue(SyncPlatformConditions(true, SyncNetworkType.Wifi, true).unmetConditions(true, true).isEmpty())
        assertTrue(SyncPlatformConditions(false).unmetConditions(true, true).isEmpty())
    }

    @Test fun networkClassificationIncludesWiredAndDoesNotTreatMixedCellularVpnAsWifiOnly() {
        assertEquals(SyncNetworkType.Unknown, classifySyncNetwork(null))
        assertEquals(SyncNetworkType.Offline, classifySyncNetwork(false, wifi = true))
        assertEquals(SyncNetworkType.Ethernet, classifySyncNetwork(true, ethernet = true))
        assertEquals(SyncNetworkType.Wifi, classifySyncNetwork(true, wifi = true))
        assertEquals(SyncNetworkType.Cellular, classifySyncNetwork(true, wifi = true, cellular = true))
        assertEquals(SyncNetworkType.Other, classifySyncNetwork(true))
    }

    @Test fun fakePlatformRetainsSupportWhileUnknownAndReleasesItsListenerExactlyOnce() = runTest {
        var listener: ((SyncPlatformConditions) -> Unit)? = null
        var released = 0
        val observations = callbackFlow {
            listener = { trySend(it) }
            awaitClose { listener = null; released++ }
        }
        val monitor = SyncConditionsMonitor(backgroundScope, true, observations)
        assertEquals(setOf(SyncRunCondition.Wifi), monitor.state.value.unmetConditions(true, false))
        runCurrent()
        val late = checkNotNull(listener)
        late(SyncPlatformConditions(false, SyncNetworkType.Wifi, true, 4))
        runCurrent()
        assertTrue(monitor.state.value.supportsConditions)
        assertTrue(monitor.state.value.unmetConditions(true, true).isEmpty())
        monitor.close(); runCurrent(); monitor.close(); runCurrent()
        assertEquals(1, released)
        assertEquals(null, listener)
        late(SyncPlatformConditions(true, SyncNetworkType.Cellular, false, 5))
        runCurrent()
        assertEquals(4, monitor.state.value.networkRevision)
    }

    @Test fun ownerCancellationAlsoReleasesThePlatformAndObserverFailureReturnsToUnknown() = runTest {
        val owner = Job()
        var released = false
        val monitor = SyncConditionsMonitor(CoroutineScope(coroutineContext + owner), true, callbackFlow {
            awaitClose { released = true }
        })
        runCurrent()
        assertFalse(released)
        owner.cancel(); runCurrent()
        assertTrue(released)
        monitor.close()
        val failed = SyncConditionsMonitor(backgroundScope, true, flow {
            emit(SyncPlatformConditions(true, SyncNetworkType.Wifi, true))
            error("platform unavailable")
        })
        runCurrent()
        assertEquals(SyncNetworkType.Unknown, failed.state.value.network)
        assertEquals(null, failed.state.value.charging)
        assertEquals(2, failed.state.value.unmetConditions(true, true).size)
        failed.close()
    }
}
