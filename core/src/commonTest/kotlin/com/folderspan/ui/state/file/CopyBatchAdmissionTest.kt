package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CopyBatchAdmissionTest {
    @Test
    fun allTargetsAreValidatedBeforeAnyTaskIsAdmitted() = runTest {
        val draft = draft()
        var enqueued = 0
        val coordinator = CopyBatchAdmission("runtime", { _, targets -> targets.map { target ->
            PreparedCopyDestination(target, emptyList()) {
                check(target.name != "/two") { "Permission revoked" }
            }
        } }, { enqueued++; listOf(1) })
        val result = coordinator.submit(draft)
        assertEquals(0, enqueued)
        assertEquals(2, result.remaining.size)
        assertTrue(result.accepted.isEmpty())
        assertEquals("Permission revoked", result.error)
        assertFalse(draft.sourcesLocked.value)
        draft.replaceSources(emptyList())
    }

    @Test
    fun doubleClicksWaitForTheSameRequestWithoutCreatingMoreTasks() = runTest {
        val draft = draft()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = CopyBatchAdmission("runtime", { _, targets ->
            started.complete(Unit)
            release.await()
            targets.map { PreparedCopyDestination(it, emptyList()) {} }
        }, { listOf((++calls).toLong()) })
        val first = async { coordinator.submit(draft) }
        started.await()
        assertTrue(draft.sourcesLocked.value)
        assertFailsWith<IllegalStateException> { draft.replaceSources(emptyList()) }
        val second = async { coordinator.submit(draft) }
        release.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()
        assertTrue(firstResult.fullyAccepted)
        assertEquals(firstResult, secondResult)
        assertEquals(2, calls)
    }

    @Test
    fun partialAdmissionKeepsReceiptsAndOnlyRetriesUnacceptedTargets() = runTest {
        val draft = draft()
        var failSecond = true
        val calls = mutableListOf<String>()
        val prepared = mutableListOf<List<String>>()
        val coordinator = CopyBatchAdmission("runtime", { _, targets ->
            prepared += targets.map { it.name }
            targets.map { PreparedCopyDestination(it, emptyList()) {} }
        }, { plan ->
            calls += plan.destination.name
            check(!(failSecond && plan.destination.name == "/two")) { "Registration failed" }
            listOf(if (plan.destination.name == "/one") 1L else 2L)
        })
        val first = coordinator.submit(draft)
        assertEquals(listOf(1L), first.accepted.single().taskKeys)
        assertEquals(listOf("/two"), first.remaining.map { it.name })
        assertNotNull(first.error)
        assertTrue(draft.sourcesLocked.value)
        assertFailsWith<IllegalStateException> { draft.replaceSources(emptyList()) }
        failSecond = false
        val second = coordinator.submit(draft)
        assertTrue(second.fullyAccepted)
        assertEquals(listOf("/one", "/two", "/two"), calls)
        assertEquals(listOf(listOf("/one", "/two"), listOf("/two")), prepared)
        assertEquals(listOf(1L, 2L), second.accepted.flatMap { it.taskKeys })
    }

    @Test
    fun differentWindowsNeverMergeEvenWhenTheirSourcesAndTargetsMatch() = runTest {
        var calls = 0L
        val coordinator = CopyBatchAdmission("runtime", { _, targets ->
            targets.map { PreparedCopyDestination(it, emptyList()) {} }
        }, { listOf(++calls) })
        val first = coordinator.submit(draft())
        val second = coordinator.submit(draft())
        assertEquals(listOf(1L, 2L), first.accepted.flatMap { it.taskKeys })
        assertEquals(listOf(3L, 4L), second.accepted.flatMap { it.taskKeys })
    }

    @Test
    fun aSelectedTargetLosingAccessDoesNotStartOtherTargets() = runTest {
        val draft = draft()
        draft.updateAvailability(draft.selected.value.last().directory, "Disconnected")
        var prepared = false
        val coordinator = CopyBatchAdmission("runtime", { _, _ -> prepared = true; emptyList() }, { listOf(1) })
        val result = coordinator.submit(draft)
        assertFalse(prepared)
        assertEquals(2, result.remaining.size)
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun anEmptySourceListCannotCreateTasksAndRemainsEditable() = runTest {
        val draft = draft()
        val original = draft.sources
        draft.replaceSources(emptyList())
        var enqueued = 0
        val coordinator = CopyBatchAdmission("runtime", { _, targets ->
            targets.map { PreparedCopyDestination(it, emptyList()) {} }
        }, { enqueued++; listOf(1L) })
        val result = coordinator.submit(draft)
        assertTrue(result.accepted.isEmpty())
        assertNotNull(result.error)
        assertEquals(0, enqueued)
        assertFalse(draft.sourcesLocked.value)
        draft.replaceSources(original)
        assertEquals(original, draft.sources)
    }

    @Test
    fun cancellingAnUnacceptedSubmissionUnlocksTheSourceList() = runTest {
        val draft = draft()
        val started = CompletableDeferred<Unit>()
        val coordinator = CopyBatchAdmission("runtime", { _, _ ->
            started.complete(Unit)
            CompletableDeferred<List<PreparedCopyDestination>>().await()
        }, { listOf(1L) })
        val submission = async { coordinator.submit(draft) }
        started.await()
        assertTrue(draft.sourcesLocked.value)
        submission.cancelAndJoin()
        assertFalse(draft.sourcesLocked.value)
        draft.replaceSources(emptyList())
    }

    private fun draft(): CopyDestinationState {
        val endpoint = CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Local), "local", "/")
        val state = CopyDestinationState("runtime", listOf(CopyFileSnapshot(endpoint,
            FileSimpleInfo.pathFileSimpleInfo("/source.txt").copy(isDirectory = false))))
        listOf("/one", "/two").forEach { path ->
            state.select(CopyDestination(CopyFileSnapshot(endpoint, FileSimpleInfo.pathFileSimpleInfo(path)), path, "Local"))
        }
        return state
    }
}
