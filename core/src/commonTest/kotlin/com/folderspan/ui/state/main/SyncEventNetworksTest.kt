package com.folderspan.ui.state.main

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEventNetworksTest {
    private val task = SyncTask(id = 1, name = "Group", sourceType = SyncEndpointType.Network,
        sourceRef = "source", sourcePath = "/source", targetType = SyncEndpointType.Network,
        targetRef = "first", targetPath = "/first", destinations = listOf(
            StorageDestination(ref = "first", path = "/first"), StorageDestination(ref = "second", path = "/second")))

    @Test fun fixedTargetsRequireTheSourceAndEveryTargetToBeAvailable() = runSuspendTest {
        val calls = mutableListOf<String>()
        assertFalse(syncEventNetworksAvailable(task) { calls += it; it != "second" })
        assertEquals(listOf("source", "first", "second"), calls)
        assertTrue(syncEventNetworksAvailable(task) { true })
    }

    @Test fun unavailableSourceCannotBeReplacedByAnAvailableRecommendedTarget() = runSuspendTest {
        val calls = mutableListOf<String>()
        assertFalse(syncEventNetworksAvailable(task.copy(selectRecommended = true)) { calls += it; it != "source" })
        assertEquals(listOf("source"), calls)
    }

    @Test fun recommendedModeAcceptsOneAvailableCandidateAndRejectsAnUnavailableSet() = runSuspendTest {
        val calls = mutableListOf<String>()
        assertTrue(syncEventNetworksAvailable(task.copy(selectRecommended = true)) { calls += it; it != "first" })
        assertEquals(listOf("source", "first", "second"), calls)
        assertFalse(syncEventNetworksAvailable(task.copy(selectRecommended = true)) { it == "source" })
    }

    @Test fun sharedNetworkRootsAreProbedOnlyOnce() = runSuspendTest {
        var calls = 0
        val duplicate = task.copy(sourceRef = "same", destinations = listOf(
            StorageDestination(ref = "same", path = "/first"), StorageDestination(ref = "same", path = "/second")))
        assertTrue(syncEventNetworksAvailable(duplicate) { calls++; true })
        assertEquals(1, calls)
    }

    @Test fun localAndDeviceEndpointsDoNotBecomeNetworkProbes() = runSuspendTest {
        val local = task.copy(sourceType = SyncEndpointType.Local, targetType = SyncEndpointType.Device, destinations = emptyList())
        assertTrue(syncEventNetworksAvailable(local) { error("unrelated endpoint") })
        assertTrue(local.eventEndpointRefs(SyncEndpointType.Network).isEmpty())
    }
}
