package com.folderspan.ui.state.file

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.StatusEnum
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CopyDestinationLocalJvmTest {
    @Test
    fun filesEmptyFilesAndDirectoriesReachTwoOrdinaryTaskDestinationsAfterThePageCloses() = runBlocking {
        fixture { state, tasks, root ->
            val input = Files.createDirectory(root.resolve("input"))
            val source = Files.writeString(input.resolve("file.txt"), "copy snapshot")
            val empty = Files.createFile(input.resolve("empty.txt"))
            val tree = Files.createDirectory(input.resolve("tree"))
            Files.writeString(tree.resolve("child.txt"), "child")
            val sources = listOf(source, empty, tree).map { FileUtils.getFile(FileAccessPermission.Allowed, it.toString()).getOrThrow() }
            val request = state.createCopyDestinationRequest(sources)
            val destinations = listOf(Files.createDirectory(root.resolve("one")), Files.createDirectory(root.resolve("two")))
            destinations.forEach { select(request, it) }
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            assertEquals(2, result.accepted.size)
            assertEquals(6, result.accepted.sumOf { it.taskKeys.size })
            request.close()
            val keys = result.accepted.flatMap { it.taskKeys }
            withTimeout(20_000) {
                while (keys.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20)
            }
            keys.forEach { key ->
                tasks.getTask(key)?.let { task -> assertEquals(StatusEnum.SUCCESS, task.status, task.result.toString()) }
            }
            destinations.forEach { target ->
                assertEquals("copy snapshot", Files.readString(target.resolve("file.txt")))
                assertEquals(0L, Files.size(target.resolve("empty.txt")))
                assertEquals("child", Files.readString(target.resolve("tree/child.txt")))
            }
        }
    }

    @Test
    fun aSourceChangeBeforeSubmissionLeavesEveryTargetAndTaskQueueUntouched() = runBlocking {
        fixture { state, tasks, root ->
            val source = Files.writeString(root.resolve("input.txt"), "before")
            val request = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, source.toString()).getOrThrow()))
            val target = Files.createDirectory(root.resolve("target"))
            select(request, target)
            Files.writeString(source, "changed source")
            val result = request.submit()
            assertFalse(result.fullyAccepted)
            assertNotNull(result.error)
            assertTrue(tasks.tasks.isEmpty())
            assertFalse(Files.exists(target.resolve("input.txt")))
            request.close()
        }
    }

    @Test
    fun temporarySourceLeaseSurvivesClosingThePageAndClearingTheShareList() = runBlocking {
        fixture { state, tasks, root ->
            val source = Files.writeString(root.resolve("temporary.txt"), "temporary content")
            val destinations = listOf(Files.createDirectory(root.resolve("one")), Files.createDirectory(root.resolve("two")))
            val releases = AtomicInteger()
            val earlyRelease = AtomicBoolean()
            ShareListDropResourceRegistry.register(listOf(source.toString())) {
                earlyRelease.set(destinations.any { !Files.exists(it.resolve("temporary.txt")) })
                releases.incrementAndGet()
                Files.deleteIfExists(source)
            }
            val request = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, source.toString()).getOrThrow()))
            destinations.forEach { select(request, it) }
            ShareListDropResourceRegistry.releaseAll()
            assertEquals(0, releases.get())
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close()
            withTimeout(20_000) {
                while (result.accepted.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20)
                while (releases.get() == 0) delay(20)
            }
            assertEquals(1, releases.get())
            assertFalse(earlyRelease.get())
            assertFalse(Files.exists(source))
            destinations.forEach { assertEquals("temporary content", Files.readString(it.resolve("temporary.txt"))) }
        }
    }

    @Test
    fun anotherRequestKeepsItsOwnSourcesAndSelection() = runBlocking {
        fixture { state, tasks, root ->
            val source = Files.writeString(root.resolve("input.txt"), "original")
            val request = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, source.toString()).getOrThrow()))
            select(request, Files.createDirectory(root.resolve("one")))
            val second = state.createCopyDestinationRequest(request.state.sources.map { it.file })
            assertTrue(second.state.selected.value.isEmpty())
            assertEquals(request.state.sources.single().file, second.state.sources.single().file)
            assertTrue(request.state.requestId != second.state.requestId)
            request.close()
            select(second, Files.createDirectory(root.resolve("two")))
            val result = second.submit()
            assertTrue(result.fullyAccepted, result.error)
            second.close()
            withTimeout(20_000) { while (result.accepted.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20) }
            assertEquals("original", Files.readString(root.resolve("two/input.txt")))
        }
    }

    @Test
    fun cancellingConflictConfirmationStartsNoTargetsAndKeepsTheDraft() = runBlocking {
        fixture { state, tasks, root -> coroutineScope {
            val source = Files.writeString(root.resolve("input.txt"), "new content")
            val one = Files.createDirectory(root.resolve("one"))
            val two = Files.createDirectory(root.resolve("two"))
            Files.writeString(one.resolve("input.txt"), "existing content")
            val request = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, source.toString()).getOrThrow()))
            select(request, one); select(request, two)
            val submission = async { runCatching { request.submit() } }
            withTimeout(10_000) { while (!request.operationState.isWarningOperationDialog.value) delay(10) }
            request.operationState.files.clear()
            request.operationState.updateWarningOperationDialog(false)
            assertTrue(submission.await().exceptionOrNull() is CancellationException)
            assertTrue(tasks.tasks.isEmpty())
            assertEquals(2, request.state.selected.value.size)
            assertEquals("existing content", Files.readString(one.resolve("input.txt")))
            assertFalse(Files.exists(two.resolve("input.txt")))
            request.close()
        } }
    }

    @Test
    fun canonicalSourceAliasesCannotBeCopiedBackIntoTheirOwnParent() = runBlocking {
        fixture { state, tasks, root ->
            val sourceDirectory = Files.createDirectory(root.resolve("source"))
            Files.writeString(sourceDirectory.resolve("input.txt"), "original")
            val alias = Files.createSymbolicLink(root.resolve("alias"), sourceDirectory)
            val source = FileUtils.getFile(FileAccessPermission.Allowed, alias.resolve("input.txt").toString()).getOrThrow()
            val request = state.createCopyDestinationRequest(listOf(source))
            select(request, sourceDirectory)
            val result = request.submit()
            assertFalse(result.fullyAccepted)
            assertTrue(tasks.tasks.isEmpty())
            assertEquals("original", Files.readString(sourceDirectory.resolve("input.txt")))
            request.close()
        }
    }

    @Test
    fun changingRuntimeSessionRejectsOldDraftsWithoutRetargeting() = runBlocking {
        fixture { state, tasks, root ->
            val token = WebUiAttachment.sessionToken
            val source = FileUtils.getFile(FileAccessPermission.Allowed, Files.writeString(root.resolve("input.txt"), "original").toString()).getOrThrow()
            val request = state.createCopyDestinationRequest(listOf(source))
            select(request, Files.createDirectory(root.resolve("target")))
            try {
                WebUiAttachment.sessionToken = "another-runtime-session"
                val result = request.submit()
                assertFalse(result.fullyAccepted)
                assertTrue(tasks.tasks.isEmpty())
                val current = state.createCopyDestinationRequest(listOf(source))
                assertTrue(request.state.runtimeId != current.state.runtimeId)
                current.close()
            } finally { WebUiAttachment.sessionToken = token; request.close() }
        }
    }

    @Test
    fun twoConcurrentRequestsSubmitOnlyTheirOwnSourcesAndDestinations() = runBlocking {
        fixture { state, tasks, root -> coroutineScope {
            val a = Files.writeString(root.resolve("a.txt"), "window A")
            val b = Files.writeString(root.resolve("b.txt"), "window B")
            val first = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, a.toString()).getOrThrow()))
            val second = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, b.toString()).getOrThrow()))
            select(first, Files.createDirectory(root.resolve("one")))
            select(second, Files.createDirectory(root.resolve("two")))
            val pendingA = async { first.submit() }
            val pendingB = async { second.submit() }
            val accepted = listOf(pendingA.await(), pendingB.await())
            accepted.forEach { assertTrue(it.fullyAccepted, it.error) }
            first.close(); second.close()
            withTimeout(20_000) { while (accepted.flatMap { it.accepted }.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20) }
            assertEquals("window A", Files.readString(root.resolve("one/a.txt")))
            assertEquals("window B", Files.readString(root.resolve("two/b.txt")))
            assertFalse(Files.exists(root.resolve("one/b.txt")))
            assertFalse(Files.exists(root.resolve("two/a.txt")))
        } }
    }

    @Test
    fun systemShareAliasesCannotReplaceTheirOwnPhysicalSource() = runBlocking {
        fixture { state, tasks, root ->
            val directory = Files.createDirectory(root.resolve("source"))
            val original = Files.writeString(directory.resolve("input.txt"), "keep original")
            val alias = Files.createSymbolicLink(root.resolve("alias"), directory)
            val file = FileUtils.getFile(FileAccessPermission.Allowed, alias.resolve("input.txt").toString()).getOrThrow()
                .copy(protocol = FileProtocol.Share, protocolId = SYSTEM_SHARE_DESK_ID)
            val request = state.createCopyDestinationRequest(listOf(file))
            val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Local }.endpoint
            val target = FileUtils.getFile(FileAccessPermission.Allowed, directory.toString()).getOrThrow()
            request.state.select(CopyDestination(CopyFileSnapshot(endpoint, target), target.name, "Local"))
            val result = request.submit()
            assertFalse(result.fullyAccepted)
            assertTrue(tasks.tasks.isEmpty())
            assertTrue(result.error?.contains(AppStrings.ui_drop_copy_self_path) == true, result.error)
            assertEquals("keep original", Files.readString(original))
            request.close()
        }
    }

    @Test
    fun editedSourcesAreDeduplicatedAndOnlyTheRemainingItemsAreCopied() = runBlocking {
        fixture { state, tasks, root ->
            val original = Files.writeString(root.resolve("original.txt"), "original")
            val added = Files.writeString(root.resolve("added.txt"), "added")
            val tree = Files.createDirectory(root.resolve("source-tree"))
            Files.writeString(tree.resolve("child.txt"), "child")
            suspend fun info(path: Path) = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
            val request = state.createCopyDestinationRequest(listOf(info(original)))
            val endpoint = request.state.sources.single().endpoint
            val target = Files.createDirectory(root.resolve("target"))
            select(request, target)
            ShareListDropResourceRegistry.register(listOf(added.toString())) { Files.deleteIfExists(added) }
            request.addSources(endpoint, listOf(info(added), info(tree), info(added)))
            ShareListDropResourceRegistry.releaseAll()
            assertTrue(Files.exists(added))
            assertEquals(3, request.state.sources.size)
            request.removeSource(request.state.sources.first())
            assertTrue(Files.exists(original))
            assertEquals(2, request.state.sources.size)
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            assertFailsWith<IllegalStateException> { request.addSources(endpoint, listOf(info(original))) }
            assertFailsWith<IllegalStateException> { request.removeSource(request.state.sources.first()) }
            request.close()
            withTimeout(20_000) {
                while (result.accepted.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20)
            }
            assertEquals("added", Files.readString(target.resolve("added.txt")))
            assertEquals("child", Files.readString(target.resolve("source-tree/child.txt")))
            assertFalse(Files.exists(target.resolve("original.txt")))
        }
    }

    @Test
    fun invalidSourceBatchesAndSelfCopiesLeaveTheDraftUntouchedAndEmptyDraftsCanBeRefilled() = runBlocking {
        fixture { state, tasks, root ->
            val original = Files.writeString(root.resolve("original.txt"), "original")
            val added = Files.writeString(root.resolve("added.txt"), "added")
            suspend fun info(path: Path) = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
            val request = state.createCopyDestinationRequest(listOf(info(original)))
            val endpoint = request.state.sources.single().endpoint
            val target = Files.createDirectory(root.resolve("target"))
            select(request, target)
            val before = request.state.sources
            assertFailsWith<IllegalStateException> {
                request.addSources(endpoint.copy(connectionBinding = "replaced-connection"), listOf(info(added)))
            }
            assertEquals(before, request.state.sources)
            assertFailsWith<Exception> { request.addSources(endpoint, listOf(info(added), info(added).copy(path = root.resolve("missing.txt").toString()))) }
            assertEquals(before, request.state.sources)
            assertFailsWith<IllegalStateException> { request.addSources(endpoint, listOf(info(root))) }
            assertEquals(before, request.state.sources)
            request.removeSource(before.single())
            assertFalse(request.submit().fullyAccepted)
            assertTrue(tasks.tasks.isEmpty())
            request.addSources(endpoint, listOf(info(added)))
            assertEquals(1, request.state.sources.size)
            assertTrue(request.submit().fullyAccepted)
            request.close()
        }
    }

    @Test
    fun changingSourcesAfterFailedPreflightDropsTheOldValidationCache() = runBlocking {
        fixture { state, tasks, root ->
            val original = Files.writeString(root.resolve("original.txt"), "original")
            val added = Files.writeString(root.resolve("added.txt"), "added")
            suspend fun info(path: Path) = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
            val request = state.createCopyDestinationRequest(listOf(info(original)))
            val endpoint = request.state.sources.single().endpoint
            val target = Files.createDirectory(root.resolve("target"))
            select(request, target)
            Files.delete(target)
            assertFalse(request.submit().fullyAccepted)
            assertFalse(request.state.sourcesLocked.value)
            request.removeSource(request.state.sources.single())
            Files.delete(original)
            request.addSources(endpoint, listOf(info(added)))
            Files.createDirectory(target)
            request.state.updateAvailability(request.state.selected.value.single().directory, null)
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close()
            withTimeout(20_000) {
                while (result.accepted.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status == StatusEnum.LOADING }) delay(20)
            }
            assertEquals("added", Files.readString(target.resolve("added.txt")))
        }
    }

    private suspend fun select(request: CopyDestinationRequest, path: Path) {
        val endpoint = request.state.sources.first().endpoint
        val directory = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
        assertEquals(CopyDestinationSelectionResult.Added,
            request.state.select(CopyDestination(CopyFileSnapshot(endpoint, directory), directory.name, "Local")))
    }

    private suspend fun fixture(block: suspend (FileState, TaskState, Path) -> Unit) {
        val root = Files.createTempDirectory("copy-destination-test")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = createMcpHttpTestDatabase(driver)
        val tasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(root.resolve("cleanup").toString()))
        stopKoin()
        val application = startKoin { modules(module {
            single { database }
            single { tasks }
            single { NetworkState(database) }
            single { DeviceState() }
            single { MainState() }
            single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try { block(application.koin.get(), tasks, root) }
        finally {
            tasks.stop()
            ShareListDropResourceRegistry.releaseAll()
            stopKoin()
            driver.close()
            root.toFile().deleteRecursively()
        }
    }
}
