package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileTaskDirectoryQueueExecutorTest {
    @Test
    fun directoryCreateBatchRunsConcurrentlyAndContinuesAfterRetryableFailure() = runSuspendTest {
        val entries = listOf(
            queuedDirectory("dir-a", "/target/a", 0),
            queuedDirectory("dir-b", "/target/b", 1),
            queuedDirectory("dir-bad", "/target/bad", 2),
            queuedDirectory("dir-c", "/target/c", 3),
        )
        val stateMutex = Mutex()
        var activeCreates = 0
        var maxActiveCreates = 0
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()

        val result = executeDirectoryCreateQueueAdaptiveBatch(
            queuedEntries = entries,
            operationConfig = OperationParallelismConfig(
                initialParallelism = 4,
                maxParallelism = 4,
                queueCapacity = 8,
                hardMaxParallelism = 4,
            ),
            dynamicMaxParallelismProvider = { 4 },
            createDirectory = { entry ->
                stateMutex.withLock {
                    activeCreates++
                    maxActiveCreates = maxOf(maxActiveCreates, activeCreates)
                }
                delay(100.milliseconds)
                stateMutex.withLock {
                    activeCreates--
                }
                if (entry.dest.path.endsWith("bad")) {
                    Result.failure(IllegalStateException("cannot create ${entry.dest.path}"))
                } else {
                    Result.success(true)
                }
            },
            onSuccess = { queuedEntry, _ ->
                stateMutex.withLock {
                    successes += queuedEntry.entry.entryId
                }
            },
            onRetryableFailure = { queuedEntry, _, message ->
                stateMutex.withLock {
                    failures += queuedEntry.entry.entryId to message
                }
            },
        )

        assertTrue(result.getOrThrow())
        assertTrue(maxActiveCreates > 1, "expected directory creation to run concurrently")
        assertEquals(setOf("dir-a", "dir-b", "dir-c"), successes.toSet())
        assertEquals(listOf("dir-bad"), failures.map { item -> item.first })
        assertTrue(failures.single().second.contains("cannot create /target/bad"))
    }

    @Test
    fun directoryCreateBatchReportsActiveParallelism() = runSuspendTest {
        val entries = listOf(
            queuedDirectory("dir-a", "/target/a", 0),
            queuedDirectory("dir-b", "/target/b", 1),
            queuedDirectory("dir-c", "/target/c", 2),
        )
        val stateMutex = Mutex()
        val activeParallelism = mutableListOf<Int>()

        executeDirectoryCreateQueueAdaptiveBatch(
            queuedEntries = entries,
            operationConfig = OperationParallelismConfig(
                initialParallelism = 3,
                maxParallelism = 3,
                queueCapacity = 6,
                hardMaxParallelism = 3,
            ),
            dynamicMaxParallelismProvider = { 3 },
            createDirectory = {
                delay(100.milliseconds)
                Result.success(true)
            },
            onActiveParallelismChanged = { activeCount ->
                stateMutex.withLock {
                    activeParallelism += activeCount
                }
            },
            onSuccess = { _, _ -> },
            onRetryableFailure = { _, _, _ -> },
        )

        assertTrue(activeParallelism.any { count -> count > 1 }, "expected active directory parallelism to be reported")
        assertEquals(0, activeParallelism.last())
    }

    @Test
    fun deviceDirectoryCreateUsesBoundedConcurrentResponsiveBatches() = runSuspendTest {
        val entries = (0 until 769).map { index ->
            queuedDirectory("dir-$index", "/target/dir-$index", index)
        }
        val stateMutex = Mutex()
        val requestSizes = mutableListOf<Int>()
        var activeRequests = 0
        var maxActiveRequests = 0
        var completedRequests = 0
        val successes = mutableListOf<String>()

        val result = executeDirectoryCreateQueueAdaptiveBatch(
            queuedEntries = entries,
            operationConfig = OperationParallelismConfig(
                initialParallelism = 8,
                maxParallelism = 24,
                queueCapacity = 96,
                hardMaxParallelism = 24,
            ),
            dynamicMaxParallelismProvider = { 24 },
            createDirectory = { Result.failure(AssertionError(AppStrings.ui_test_file_task_directory_queue_executor_should_not_create_in_batches)) },
            createDirectoryBatch = { batchEntries ->
                stateMutex.withLock {
                    requestSizes += batchEntries.size
                    activeRequests++
                    maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                }
                delay(100.milliseconds)
                stateMutex.withLock {
                    activeRequests--
                }
                Result.success(List(batchEntries.size) { Result.success(true) })
            },
            onSuccess = { queuedEntry, _ ->
                stateMutex.withLock {
                    successes += queuedEntry.entry.entryId
                }
            },
            onBatchCompleted = {
                stateMutex.withLock {
                    completedRequests++
                }
            },
            onRetryableFailure = { _, _, _ -> },
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf(1) + List(48) { 16 }, requestSizes.sorted())
        assertEquals(8, maxActiveRequests)
        assertEquals(requestSizes.size, completedRequests)
        assertEquals(entries.map { item -> item.entry.entryId }.toSet(), successes.toSet())
    }

    @Test
    fun directoryCreateBatchCreatesParentDirectoriesBeforeChildren() = runSuspendTest {
        val entries = listOf(
            queuedDirectory("codex", "/target/.codex", 0),
            queuedDirectory("browser", "/target/.codex/browser", 1),
            queuedDirectory("attachments", "/target/.codex/attachments", 2),
            queuedDirectory("tmp", "/target/.codex/.tmp", 3),
        )
        val stateMutex = Mutex()
        val createdDirectories = mutableSetOf("/target")
        val failures = mutableListOf<Pair<String, String>>()

        val result = executeDirectoryCreateQueueAdaptiveBatch(
            queuedEntries = entries,
            operationConfig = OperationParallelismConfig(
                initialParallelism = 4,
                maxParallelism = 4,
                queueCapacity = 8,
                hardMaxParallelism = 4,
            ),
            dynamicMaxParallelismProvider = { 4 },
            createDirectory = { entry ->
                val path = entry.dest.path
                if (path == "/target/.codex") {
                    delay(100.milliseconds)
                }
                val parent = path.substringBeforeLast("/")
                stateMutex.withLock {
                    if (parent !in createdDirectories) {
                        Result.failure(IllegalStateException("missing parent $parent"))
                    } else {
                        createdDirectories += path
                        Result.success(true)
                    }
                }
            },
            onSuccess = { _, _ -> },
            onRetryableFailure = { queuedEntry, _, message ->
                stateMutex.withLock {
                    failures += queuedEntry.entry.entryId to message
                }
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(emptyList(), failures)
        assertTrue(createdDirectories.containsAll(entries.map { item -> item.entry.dest.path }))
    }

    @Test
    fun directoryCreateBatchCanStopBeforeAcknowledgingOrdinaryFailure() = runSuspendTest {
        val failures = mutableListOf<String>()

        val result = executeDirectoryCreateQueueAdaptiveBatch(
            queuedEntries = listOf(queuedDirectory("dir-bad", "/target/bad", 0)),
            operationConfig = OperationParallelismConfig(
                initialParallelism = 1,
                maxParallelism = 1,
                queueCapacity = 1,
                hardMaxParallelism = 1,
            ),
            dynamicMaxParallelismProvider = { 1 },
            createDirectory = { Result.failure(IllegalStateException("cannot create")) },
            onSuccess = { _, _ -> },
            onRetryableFailure = { queuedEntry, _, _ ->
                failures += queuedEntry.entry.entryId
            },
            shouldContinueAfterFailure = { false },
        )

        assertTrue(result.isFailure)
        assertFalse(failures.isNotEmpty())
    }

    private fun queuedDirectory(
        entryId: String,
        destPath: String,
        order: Int,
    ): TaskRuntimeQueuedEntry {
        return TaskRuntimeQueuedEntry(
            fileName = "000001.pb64l",
            entry = TaskRuntimeQueueEntry(
                entryId = entryId,
                stage = TaskRuntimeStage.COPY,
                kind = TaskRuntimeEntryKind.DIRECTORY_CREATE,
                src = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = destPath.replace("/target", "/source"),
                ),
                dest = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = destPath,
                ),
                isDirectory = true,
                order = order,
            ),
        )
    }
}
