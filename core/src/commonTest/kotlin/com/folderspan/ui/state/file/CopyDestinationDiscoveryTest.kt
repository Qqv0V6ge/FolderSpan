package com.folderspan.ui.state.file

import com.folderspan.data.file.PathInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CopyDestinationDiscoveryTest {
    @Test
    fun oneTimedOutConnectionDoesNotBlockOtherTargetsAndCanBeRetriedLocally() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        var slowFails = true
        var fastCalls = 0
        val slow = source("slow") {
            if (slowFails) awaitCancellation()
            listOf(destination("slow", "/recovered"))
        }
        val fast = source("fast") { fastCalls++; listOf(destination("fast", "/ready")) }
        discovery.refresh(listOf(slow, fast))
        runCurrent()
        assertEquals("/ready", discovery.loads.value[1].destinations.single().directory.file.path)
        assertTrue(discovery.loads.value[0].loading)
        advanceTimeBy(30_001)
        runCurrent()
        assertFalse(discovery.loads.value[0].loading)
        assertNotNull(discovery.loads.value[0].error)
        slowFails = false
        discovery.retry(slow.endpoint)
        advanceUntilIdle()
        assertEquals("/recovered", discovery.loads.value[0].destinations.single().directory.file.path)
        assertEquals(1, fastCalls)
        discovery.close()
    }

    @Test
    fun aLateNonCancellableRefreshCannotOverwriteTheNewGeneration() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        val release = CompletableDeferred<Unit>()
        discovery.refresh(listOf(source("one") {
            withContext(NonCancellable) { release.await() }
            listOf(destination("one", "/old"))
        }))
        runCurrent()
        discovery.refresh(listOf(source("one") { listOf(destination("one", "/new")) }))
        runCurrent()
        assertEquals("/new", discovery.loads.value.single().destinations.single().directory.file.path)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("/new", discovery.loads.value.single().destinations.single().directory.file.path)
        discovery.close()
    }

    @Test
    fun retriesAlsoDiscardPreviousAttemptsFromTheSameEndpoint() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = source("one") {
            if (++calls == 1) {
                withContext(NonCancellable) { release.await() }
                listOf(destination("one", "/old"))
            } else listOf(destination("one", "/retry"))
        }
        discovery.refresh(listOf(source))
        runCurrent()
        discovery.retry(source.endpoint)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("/retry", discovery.loads.value.single().destinations.single().directory.file.path)
        discovery.close()
    }

    @Test
    fun discoveryBoundsConcurrentConnectionsAndCancelsOnlyItsOwnJobs() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var cancellations = 0
        discovery.refresh((1..10).map { index ->
            source(index.toString()) {
                active++
                maximum = maxOf(maximum, active)
                try { release.await(); emptyList() }
                finally { active--; cancellations++ }
            }
        })
        runCurrent()
        assertEquals(4, active)
        assertEquals(4, maximum)
        discovery.close()
        runCurrent()
        assertEquals(0, active)
        assertEquals(4, cancellations)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun connectionRebindingDuringDiscoveryFailsWithoutReplacingTheCapturedEndpoint() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        var current = true
        val original = source("one", isCurrent = { current }) {
            current = false
            listOf(destination("one", "/wrong-binding"))
        }
        discovery.refresh(listOf(original))
        advanceUntilIdle()
        val result = discovery.loads.value.single()
        assertEquals(original.endpoint, result.endpoint)
        assertTrue(result.destinations.isEmpty())
        assertNotNull(result.error)
        discovery.close()
    }

    @Test
    fun aFailedConnectionDoesNotClearSelectionOrOtherConnectionResults() = runTest {
        val discovery = CopyDestinationDiscovery("runtime", this)
        val target = destination("one", "/selected")
        val local = CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Local), "local", "/")
        val draft = CopyDestinationState("runtime", listOf(CopyFileSnapshot(local,
            FileSimpleInfo.pathFileSimpleInfo("/source").copy(isDirectory = false))))
        draft.select(target)
        discovery.refresh(listOf(source("one") { error("Disconnected") },
            source("two") { listOf(destination("two", "/available")) }))
        advanceUntilIdle()
        assertEquals("Disconnected", discovery.loads.value[0].error)
        assertEquals(1, discovery.loads.value[1].destinations.size)
        assertEquals(listOf(target), draft.selected.value)
        discovery.refresh(emptyList())
        assertTrue(discovery.loads.value.isEmpty())
        assertEquals(1, draft.selected.value.size)
        discovery.close()
    }

    @Test fun capacityIsOptionalAndZeroFreeSpaceIsDifferentFromUnknown() = runTest {
        assertEquals(24L, optionalCopyDestinationFreeSpace { PathInfo("/", 100, 24) })
        assertEquals(0L, optionalCopyDestinationFreeSpace { PathInfo("/", 100, 0) })
        assertNull(optionalCopyDestinationFreeSpace { PathInfo("/", 0, 0) })
        assertNull(optionalCopyDestinationFreeSpace { PathInfo("/", 100, -1) })
        assertNull(optionalCopyDestinationFreeSpace { PathInfo("/", 100, 101) })
        assertNull(optionalCopyDestinationFreeSpace { error("Capacity unsupported") })
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            optionalCopyDestinationFreeSpace { throw kotlinx.coroutines.CancellationException("closed") }
        }
    }

    private fun endpoint(id: String) = CopyEndpointIdentity(
        "runtime", FileEndpointRef(FileProtocol.Network, id), "binding-$id", "/",
    )

    private fun source(
        id: String,
        isCurrent: () -> Boolean = { true },
        load: suspend () -> List<CopyDestination>,
    ) = CopyDestinationSource(endpoint(id), id, isCurrent = isCurrent, load = load)

    private fun destination(id: String, path: String) = CopyDestination(
        CopyFileSnapshot(endpoint(id), FileSimpleInfo.pathFileSimpleInfo(path).copy(
            protocol = FileProtocol.Network, protocolId = id,
        )), path, id,
    )
}
