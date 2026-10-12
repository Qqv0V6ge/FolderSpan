package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.automation.McpAutomationException
import com.folderspan.service.mcp.automation.McpTaskFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TaskCleanupTest {
    private fun fixture(block: suspend (Path, TaskState, TempTaskCleanupStore, Task) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("folderspan-cleanup-test").toRealPath()
        try {
            val store = TempTaskCleanupStore(root.resolve("journal").toString())
            val task = Task(TaskType.Copy, 71001L, StatusEnum.FAILURE)
            val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), store)
            state.addOrUpdate(task)
            block(root, state, store, task)
        } finally { root.toFile().deleteRecursively() }
    }

    private suspend fun partial(root: Path, state: TaskState, task: Task, id: String = "file"): TaskArtifact {
        state.withTaskArtifact(task, id, TaskRuntimeEndpointRef(path = root.resolve("$id.txt").toString())) { path ->
            Files.writeString(Path.of(path), "partial")
            Result.failure(IllegalStateException("injected transfer failure"))
        }
        return state.loadTaskArtifacts(task.key)!!.first { it.entryId == id }
    }

    @Test fun partialIsRemovedAfterRestartAndExistingTargetIsPreserved() = fixture { root, state, store, task ->
        val original = root.resolve("file.txt")
        Files.writeString(original, "original")
        val artifact = partial(root, state, task)
        val runtime = TestTaskRuntimePersistenceStore().also { it.saveTaskSnapshot(task) }
        val restored = TaskState(TestTaskFailureResultStore(), runtime, store)
        assertTrue(restored.deleteTerminalTask(task.key).succeeded)
        assertFalse(Files.exists(Path.of(artifact.endpoint.path)))
        assertEquals("original", Files.readString(original))
        assertNull(store.load(task.key))
    }

    @Test fun completeAndZeroByteFilesAreRetained() = fixture { root, state, _, task ->
        state.withTaskArtifact(task, "complete", TaskRuntimeEndpointRef(path = root.resolve("complete.txt").toString())) {
            Files.writeString(Path.of(it), "complete"); Result.success(true)
        }
        state.withTaskArtifact(task, "empty", TaskRuntimeEndpointRef(path = root.resolve("empty.txt").toString()),
            TaskArtifactPurpose.EMPTY_FILE) { error("exclusive empty creation already performed") }
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("complete", Files.readString(root.resolve("complete.txt")))
        assertEquals(0L, Files.size(root.resolve("empty.txt")))
    }

    @Test fun externalModificationFailsAndDirectDeleteRetainsIt() = fixture { root, state, store, task ->
        val artifact = partial(root, state, task)
        Files.writeString(Path.of(artifact.endpoint.path), "user data")
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertNotNull(state.getTask(task.key))
        assertTrue(state.deleteTaskRecordOnly(task.key))
        assertEquals("user data", Files.readString(Path.of(artifact.endpoint.path)))
        assertNull(store.load(task.key))
    }

    @Test fun retryOnlyHandlesRemainingFilesAndMissingFilesSucceed() = fixture { root, state, store, task ->
        val first = partial(root, state, task, "first")
        val second = partial(root, state, task, "second")
        Files.writeString(Path.of(second.endpoint.path), "changed")
        assertEquals(1, state.deleteTerminalTask(task.key).failures.size)
        assertTrue(store.load(task.key)!!.first { it.entryId == "first" }.cleaned)
        Files.writeString(Path.of(first.endpoint.path), "new unrelated file at old path")
        Files.delete(Path.of(second.endpoint.path))
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("new unrelated file at old path", Files.readString(Path.of(first.endpoint.path)))
    }

    @Test fun symbolicLinkReplacementIsNeverFollowed() = fixture { root, state, _, task ->
        val artifact = partial(root, state, task)
        val source = root.resolve("source.txt")
        Files.writeString(source, "source")
        Files.delete(Path.of(artifact.endpoint.path))
        Files.createSymbolicLink(Path.of(artifact.endpoint.path), source)
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("source", Files.readString(source))
        assertTrue(Files.isSymbolicLink(Path.of(artifact.endpoint.path)))
    }

    @Test fun sameContentReplacementWithPreservedModificationTimeIsRetained() = fixture { root, state, _, task ->
        val artifact = partial(root, state, task)
        val path = Path.of(artifact.endpoint.path)
        val modified = Files.getLastModifiedTime(path)
        Files.delete(path)
        Files.writeString(path, "partial")
        Files.setLastModifiedTime(path, modified)
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("partial", Files.readString(path))
    }

    @Test fun permissionFailureKeepsTheTaskAndCanBeRetried() = fixture { root, state, _, task ->
        if ("posix" !in root.fileSystem.supportedFileAttributeViews()) return@fixture
        val directory = Files.createDirectory(root.resolve("protected"))
        partial(directory, state, task)
        val permissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, emptySet())
            assertFalse(state.deleteTerminalTask(task.key).succeeded)
            assertNotNull(state.getTask(task.key))
        } finally { Files.setPosixFilePermissions(directory, permissions) }
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
    }

    @Test fun inaccessibleJournalIsReportedInsteadOfAssumedLegacy() = fixture { root, state, store, task ->
        if ("posix" !in root.fileSystem.supportedFileAttributeViews()) return@fixture
        store.save(task.key, TaskArtifact("test", TaskRuntimeEndpointRef(path = "/not-owned")))
        val directory = root.resolve("journal")
        val permissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, emptySet())
            assertEquals("journal_unreadable", state.deleteTerminalTask(task.key).failures.single().reason)
            assertNotNull(state.getTask(task.key))
        } finally { Files.setPosixFilePermissions(directory, permissions) }
    }

    @Test fun emptyResultsAreRetainedAndStructureDirectoriesAreNonRecursive() = fixture { root, state, _, task ->
        val empty = root.resolve("empty-directory")
        state.withTaskArtifact(task, "empty-dir", TaskRuntimeEndpointRef(path = empty.toString()),
            TaskArtifactPurpose.EMPTY_DIRECTORY) { Result.success(true) }
        val protected = root.resolve("unknown-source-directory")
        state.withTaskArtifact(task, "protected-dir", TaskRuntimeEndpointRef(path = protected.toString()),
            TaskArtifactPurpose.PROTECTED_DIRECTORY) { Result.success(true) }
        val structure = root.resolve("structure")
        state.withTaskArtifact(task, "structure", TaskRuntimeEndpointRef(path = structure.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertTrue(Files.isDirectory(empty))
        assertTrue(Files.isDirectory(protected))
        assertFalse(Files.exists(structure))
    }

    @Test fun protectedChildPreventsDirectoryDeletion() = fixture { root, state, _, task ->
        val directory = root.resolve("structure")
        state.withTaskArtifact(task, "structure", TaskRuntimeEndpointRef(path = directory.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        Files.writeString(directory.resolve("complete.txt"), "keep")
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("keep", Files.readString(directory.resolve("complete.txt")))
    }

    @Test fun directoryWithTrailingDotUsesTheSameIdentityForCreationAndCleanup() = fixture { root, state, _, task ->
        val directory = root.resolve("directory.")
        state.withTaskArtifact(task, "directory", TaskRuntimeEndpointRef(path = directory.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        assertTrue(Files.isDirectory(directory))
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertFalse(Files.exists(directory))
    }

    @Test fun longDirectoryWithTrailingDotUsesTheSameIdentityForCreationAndCleanup() = fixture { root, state, _, task ->
        var parent = root
        while (parent.toString().length < 260) {
            parent = Files.createDirectory(parent.resolve("nested-long-path"))
        }
        val directory = parent.resolve("directory.")
        state.withTaskArtifact(task, "long-directory", TaskRuntimeEndpointRef(path = directory.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        assertTrue(Files.isDirectory(directory))
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertFalse(Files.exists(directory))
    }

    @Test fun completedChildMakesItsStructureDirectoryAProtectedResult() = fixture { root, state, _, task ->
        val directory = root.resolve("structure")
        state.withTaskArtifact(task, "structure", TaskRuntimeEndpointRef(path = directory.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        state.withTaskArtifact(task, "complete-child", TaskRuntimeEndpointRef(path = directory.resolve("complete.txt").toString())) {
            Files.writeString(Path.of(it), "keep"); Result.success(true)
        }
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("keep", Files.readString(directory.resolve("complete.txt")))
    }

    @Test fun externallyChangedDirectoryPermissionsPreventCleanup() = fixture { root, state, _, task ->
        if ("posix" !in root.fileSystem.supportedFileAttributeViews()) return@fixture
        val directory = root.resolve("structure")
        state.withTaskArtifact(task, "structure", TaskRuntimeEndpointRef(path = directory.toString()),
            TaskArtifactPurpose.STRUCTURE_DIRECTORY) { Result.success(true) }
        val permissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
            assertFalse(state.deleteTerminalTask(task.key).succeeded)
            assertTrue(Files.isDirectory(directory))
        } finally { Files.setPosixFilePermissions(directory, permissions) }
    }

    @Test fun corruptAndUnknownVersionAreNotLegacyEmptyJournals() = fixture { root, state, store, task ->
        store.save(task.key, TaskArtifact("unknown", TaskRuntimeEndpointRef(path = "/unknown"), schemaVersion = 42))
        assertEquals("journal_unreadable", state.deleteTerminalTask(task.key).failures.single().reason)
        val journalFile = Files.list(root.resolve("journal/${task.key}")).use { it.findFirst().get() }
        Files.writeString(journalFile, "corrupt")
        assertNull(state.deleteTerminalTask(task.key).failures.single().path)
        assertNotNull(state.getTask(task.key))
    }

    @Test fun unfinishedCreationReceiptIsKeptWithoutGuessing() = fixture { root, state, store, task ->
        val target = root.resolve("intent-only.txt")
        Files.writeString(target, "possibly completed data")
        store.save(task.key, TaskArtifact("intent", TaskRuntimeEndpointRef(path = target.toString())))
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("possibly completed data", Files.readString(target))
        Files.delete(target)
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertNull(state.getTask(task.key))
    }

    @Test fun documentUriIsNotMistakenForAMissingLocalFile() = fixture { _, state, store, task ->
        store.save(task.key, TaskArtifact("document", TaskRuntimeEndpointRef(path = "content://documents/file/42")))
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertNotNull(state.getTask(task.key))
        assertTrue(state.deleteTaskRecordOnly(task.key))
        assertNull(store.load(task.key))
    }

    @Test fun mcpReturnsStableFailureAndSameIdCanRetryWithoutDialog() = fixture { root, state, _, task ->
        val artifact = partial(root, state, task)
        Files.writeString(Path.of(artifact.endpoint.path), "changed")
        val facade = McpTaskFacade(state)
        val error = assertFailsWith<McpAutomationException> { facade.delete(task.key) }
        assertEquals("task_cleanup_failed", error.code)
        assertTrue(state.cleanupFailures.value.isEmpty())
        Files.delete(Path.of(artifact.endpoint.path))
        assertTrue(facade.delete(task.key))
        state.addOrUpdate(task.copy(key = task.key + 1, status = StatusEnum.LOADING))
        assertEquals("invalid_state", assertFailsWith<McpAutomationException> { facade.delete(task.key + 1) }.code)
    }

    @Test fun cancellationWaitsForLocalTeardownAndLateCheckpointCannotResurrectRuntime() = fixture { root, _, store, task ->
        val runtime = TestTaskRuntimePersistenceStore()
        val state = TaskState(TestTaskFailureResultStore(), runtime, store)
        val active = task.copy(status = StatusEnum.LOADING)
        state.addOrUpdate(active)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        state.registerTaskHandler(active) {
            try {
                state.withTaskArtifact(active, "cancelled", TaskRuntimeEndpointRef(path = root.resolve("cancelled").toString())) {
                    Files.writeString(Path.of(it), "partial")
                    entered.complete(Unit)
                    delay(60_000)
                    Result.success(true)
                }
            } finally { withContext(NonCancellable) { finish.await() } }
        }
        try {
            withTimeout(5_000) { entered.await() }
            state.requestCancel(active)
            val deletion = CoroutineScope(currentCoroutineContext()).async { state.deleteTaskRecordOnly(active.key) }
            // Direct deletion also requires a terminal record; the actual UI offers it after failure.
            state.updateStatus(active, StatusEnum.FAILURE)
            delay(30)
            assertFalse(deletion.isCompleted)
            finish.complete(Unit)
            assertTrue(withTimeout(5_000) { deletion.await() })
            state.saveRuntimeTransferCheckpoint(TaskRuntimeTransferCheckpoint(taskKey = active.key, entryId = "late",
                destPath = "/late", fileSize = 100, chunkSize = 10, createdAt = 1))
            assertTrue(runtime.transferCheckpoints.isEmpty())
            assertNull(store.load(active.key))
        } finally {
            finish.complete(Unit)
            state.requestCancel(active)
        }
    }

    @Test fun journalWriteFailurePreventsCreatingAnyOutput() = fixture { root, _, _, task ->
        val broken = object : TaskCleanupStore {
            override fun load(taskKey: Long): List<TaskArtifact>? = null
            override fun save(taskKey: Long, artifact: TaskArtifact) { error("injected IO failure") }
            override fun remove(taskKey: Long) = Unit
        }
        val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), broken)
        state.addOrUpdate(task)
        assertFailsWith<IllegalStateException> {
            state.withTaskArtifact(task, "file", TaskRuntimeEndpointRef(path = root.resolve("final").toString())) { error("must not write") }
        }
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun batchFailureCloseRetryAndDirectDeleteOnlyAffectRemainingTasks() = fixture { root, state, store, task ->
        val first = partial(root, state, task)
        Files.writeString(Path.of(first.endpoint.path), "externally changed")
        val other = task.copy(key = task.key + 1)
        state.addOrUpdate(other)
        partial(root, state, other, "other")
        state.requestDeleteTasks(listOf(task.key, other.key))
        state.requestDeleteTasks(listOf(task.key)) // rejected while the first request is busy
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        assertNull(state.getTask(other.key))
        assertEquals(listOf(task.key), state.cleanupFailures.value.map { it.taskKey })
        state.dismissTaskCleanupFailure()
        assertTrue(state.cleanupFailures.value.isEmpty())
        assertNotNull(state.getTask(task.key))
        assertNotNull(store.load(task.key))
        state.requestDeleteTasks(listOf(task.key))
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        state.retryTaskCleanup()
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        assertEquals(1, state.cleanupFailures.value.size) // still failed, updated popup result
        state.directlyDeleteFailedTaskRecords()
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        assertTrue(state.cleanupFailures.value.isEmpty())
        assertNull(state.getTask(task.key))
        assertEquals("externally changed", Files.readString(Path.of(first.endpoint.path)))
    }

    @Test fun offlineRemoteIsRetainedAndRecordOnlyDeleteNeedsNoRemoteConnection() = fixture { _, state, store, task ->
        store.save(task.key, TaskArtifact("remote", TaskRuntimeEndpointRef(FileProtocol.Device, "offline-device", "/partial")))
        assertFalse(state.deleteTerminalTask(task.key).succeeded)
        assertTrue(withTimeout(1_000) { state.deleteTaskRecordOnly(task.key) })
        assertNull(state.getTask(task.key))
        assertNull(store.load(task.key))
    }

    @Test fun uiRetrySuccessRemovesTheTaskAndClearsFailureChoices() = fixture { root, state, _, task ->
        val artifact = partial(root, state, task)
        Files.writeString(Path.of(artifact.endpoint.path), "changed")
        state.requestDeleteTasks(listOf(task.key))
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        assertEquals(1, state.cleanupFailures.value.size)
        Files.delete(Path.of(artifact.endpoint.path))
        state.retryTaskCleanup()
        withTimeout(5_000) { while (state.deletionBusy.value) delay(10) }
        assertNull(state.getTask(task.key))
        assertTrue(state.cleanupFailures.value.isEmpty())
    }

    @Test fun failedRecordRemovalKeepsAVisibleTask() = fixture { _, _, _, task ->
        val store = object : TaskCleanupStore {
            override fun load(taskKey: Long): List<TaskArtifact>? = null
            override fun save(taskKey: Long, artifact: TaskArtifact) = Unit
            override fun remove(taskKey: Long) { error("injected removal IO failure") }
        }
        val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), store)
        state.addOrUpdate(task)
        assertFailsWith<IllegalStateException> { state.deleteTaskRecordOnly(task.key) }
        assertNotNull(state.getTask(task.key))
    }

    @Test fun deliveredFileWithInterruptedCompletionReceiptIsNeverDeleted() = fixture { root, state, store, task ->
        val target = root.resolve("delivered.txt")
        val temporary = root.resolve("delivery.part")
        val identity = LocalTaskArtifacts.create(temporary.toString())
        Files.writeString(temporary, "complete")
        store.save(task.key, TaskArtifact("delivery", TaskRuntimeEndpointRef(path = temporary.toString()),
            finalPath = target.toString(), identity = identity.identity, fingerprint = identity.fingerprint))
        LocalTaskArtifacts.deliver(temporary.toString(), target.toString(), identity.identity)
        // The journal still says incomplete, simulating process exit before receipt persistence.
        assertTrue(state.deleteTerminalTask(task.key).succeeded)
        assertEquals("complete", Files.readString(target))
    }

    @Test fun credentialsAndSignedQueriesAreExcludedFromTheCleanupJournal() = fixture { _, state, store, task ->
        val path = "https://user:password@example.com/file?token=secret"
        state.saveTaskArtifact(task.key, TaskArtifact("copy:$path", TaskRuntimeEndpointRef(FileProtocol.Network, "network", path)))
        val record = store.load(task.key)!!.single()
        assertEquals("https://example.com/file", record.endpoint.path)
        assertFalse("password" in record.entryId)
        assertFalse("secret" in record.toString())
        assertNotNull(state.findTaskArtifact(task.key, "copy:$path"))
    }

    @Test fun deletionReportsStopTimeoutAndDirectDeletionWaitsOnlyForLocalTeardown() = fixture { _, _, store, task ->
        val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), store)
        val active = task.copy(status = StatusEnum.LOADING)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        state.addOrUpdate(active)
        state.registerTaskHandler(active) {
            entered.complete(Unit)
            try { delay(60_000) } finally { withContext(NonCancellable) { release.await() } }
        }
        entered.await()
        state.requestCancel(active)
        state.updateStatus(active, StatusEnum.FAILURE)
        try {
            val result = withTimeout(7_000) { state.deleteTerminalTask(active.key) }
            assertEquals("writer_not_stopped", result.failures.single().reason)
            assertNotNull(state.getTask(active.key))
        } finally { release.complete(Unit) }
        assertTrue(withTimeout(1_000) { state.deleteTaskRecordOnly(active.key) })
    }
}
