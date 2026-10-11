package com.folderspan.service.operation

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.file.buildCopyQueueEntriesForOperation
import com.folderspan.ui.state.file.buildDeleteQueueEntriesFromCopyPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

class OperationParallelTraversalTest {

    @Test
    fun traversalListsChildDirectoriesConcurrentlyAndDeduplicatesPaths() = runSuspendTest {
        val activeMutex = Mutex()
        var activeRequests = 0
        var maxActiveRequests = 0
        val listedPaths = mutableListOf<String>()
        val tree = mapOf(
            "/root" to listOf(
                directory("/root/a"),
                directory("/root/b"),
                directory("/root/a/"),
                file("/root/file.txt"),
            ),
            "/root/a" to listOf(file("/root/a/one.txt")),
            "/root/b" to listOf(file("/root/b/two.txt")),
        )

        val entries = collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 2, maxParallelism = 2, queueCapacity = 4),
            pathSeparator = "/",
            listChildren = { directory ->
                activeMutex.withLock {
                    activeRequests++
                    maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                    listedPaths += directory.path
                }
                if (directory.path != "/root") {
                    delay(50.milliseconds)
                }
                activeMutex.withLock { activeRequests-- }
                Result.success(tree[directory.path.trimEnd('/')] ?: emptyList())
            },
        )

        assertTrue(maxActiveRequests > 1)
        assertEquals(
            setOf("/root", "/root/a", "/root/b"),
            listedPaths.toSet(),
        )
        assertEquals(3, listedPaths.size)
        assertEquals(
            setOf("/root/a", "/root/b", "/root/file.txt", "/root/a/one.txt", "/root/b/two.txt"),
            entries.map { entry -> entry.path }.toSet(),
        )
        assertEquals(5, entries.size)
    }

    @Test
    fun traversalStreamsEntriesWithoutRetainingFullResult() = runSuspendTest {
        val discoveredPaths = mutableListOf<String>()
        val reportedProgress = mutableListOf<TraversalScanProgress>()
        val tree = mapOf(
            "/root" to listOf(directory("/root/a"), directory("/root/a"), file("/root/file.txt")),
            "/root/a" to listOf(file("/root/a/one.txt")),
        )

        val entries = collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
            pathSeparator = "/",
            onScanProgress = { progress -> reportedProgress += progress },
            onEntriesDiscovered = { discovered ->
                discoveredPaths += discovered.map { entry -> entry.path }
            },
            retainDiscoveredEntries = false,
            listChildren = { directory ->
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertTrue(entries.isEmpty())
        assertEquals(
            setOf("/root/a", "/root/file.txt", "/root/a/one.txt"),
            discoveredPaths.toSet(),
        )
        assertEquals(3, reportedProgress.last().discoveredEntries)
    }

    @Test
    fun traversalHonorsExplicitParallelismLimit() = runSuspendTest {
        val activeMutex = Mutex()
        var activeRequests = 0
        var maxActiveRequests = 0
        val tree = mutableMapOf<String, List<FileSimpleInfo>>()
        tree["/root"] = (0 until 8).map { index -> directory("/root/dir-$index") }
        repeat(8) { index -> tree["/root/dir-$index"] = emptyList() }

        collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(
                initialParallelism = 8,
                maxParallelism = 8,
                queueCapacity = 32,
                hardMaxParallelism = 8,
            ),
            pathSeparator = "/",
            maxParallelismLimit = 2,
            listChildren = { directory ->
                activeMutex.withLock {
                    activeRequests++
                    maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                }
                if (directory.path != "/root") delay(40.milliseconds)
                activeMutex.withLock { activeRequests-- }
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertEquals(2, maxActiveRequests)
    }

    @Test
    fun localStreamingTraversalCanUseDepthFirstOrderWithoutPathRetention() = runSuspendTest {
        val listedPaths = mutableListOf<String>()
        val tree = mapOf(
            "/root" to listOf(directory("/root/a"), directory("/root/b")),
            "/root/a" to listOf(directory("/root/a/deep")),
            "/root/a/deep" to emptyList(),
            "/root/b" to emptyList(),
        )

        collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
            pathSeparator = "/",
            retainDiscoveredEntries = false,
            deduplicateDirectories = false,
            depthFirst = true,
            listChildren = { directory ->
                listedPaths += directory.path
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertEquals(listOf("/root", "/root/a", "/root/a/deep", "/root/b"), listedPaths)
    }

    @Test
    fun traversalStopsDispatchingWhenEnsureRunningFails() = runSuspendTest {
        var canceled = false
        val listedPaths = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            collectDirectoryEntriesAdaptive(
                root = directory("/root"),
                config = TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 4, queueCapacity = 8),
                pathSeparator = "/",
                ensureRunning = {
                    if (canceled) throw CancellationException(AppStrings.message_task_cancelled)
                },
                onScanProgress = { _ ->
                    canceled = true
                },
                listChildren = { directory ->
                    listedPaths += directory.path
                    Result.success(
                        listOf(
                            directory("/root/a"),
                            directory("/root/b"),
                            directory("/root/c"),
                        )
                    )
                },
            )
        }

        assertEquals(listOf("/root"), listedPaths)
    }

    @Test
    fun traversalCancelsWhileWaitingForActiveListRequest() = runSuspendTest {
        coroutineScope {
            var canceled = false
            var cancelCallbacks = 0
            val childRequestStarted = CompletableDeferred<Unit>()
            val childRequestCancelled = CompletableDeferred<Unit>()

            val traversal = async {
                collectDirectoryEntriesAdaptive(
                    root = directory("/root"),
                    config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
                    pathSeparator = "/",
                    ensureRunning = {
                        if (canceled) throw CancellationException(AppStrings.message_task_cancelled)
                    },
                    onCancel = {
                        cancelCallbacks++
                    },
                    listChildren = { directory ->
                        if (directory.path == "/root") {
                            Result.success(listOf(directory("/root/child")))
                        } else {
                            childRequestStarted.complete(Unit)
                            try {
                                delay(Long.MAX_VALUE.milliseconds)
                                Result.success(emptyList())
                            } finally {
                                childRequestCancelled.complete(Unit)
                            }
                        }
                    },
                )
            }

            childRequestStarted.await()
            canceled = true

            val thrown = assertFailsWith<CancellationException> {
                withTimeout(2_000.milliseconds)
                {
                    traversal.await()
                }
            }

            assertEquals(AppStrings.message_task_cancelled, thrown.message)
            assertEquals(1, cancelCallbacks)
            withTimeout(2_000.milliseconds) {
                childRequestCancelled.await()
            }
        }
    }

    @Test
    fun traversalFailsFastWithOriginalListError() = runSuspendTest {
        val failure = IllegalStateException("list failed")

        val thrown = assertFailsWith<IllegalStateException> {
            collectDirectoryEntriesAdaptive(
                root = directory("/root"),
                config = TraversalParallelismConfig(initialParallelism = 2, maxParallelism = 2, queueCapacity = 4),
                pathSeparator = "/",
                listChildren = { directory ->
                    when (directory.path) {
                        "/root" -> Result.success(listOf(directory("/root/a")))
                        else -> Result.failure(failure)
                    }
                },
            )
        }

        assertEquals(failure.message, thrown.message)
    }

    @Test
    fun traversalCanRecordDirectoryErrorAndContinueRemainingQueue() = runSuspendTest {
        val reportedErrors = mutableListOf<Pair<String, String?>>()
        val listedPaths = mutableListOf<String>()

        val entries = collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
            pathSeparator = "/",
            continueOnDirectoryError = true,
            onDirectoryError = { directory, error ->
                reportedErrors += directory.path to error.message
            },
            listChildren = { directory ->
                listedPaths += directory.path
                when (directory.path) {
                    "/root" -> Result.success(
                        listOf(
                            directory("/root/unreadable"),
                            directory("/root/readable"),
                        )
                    )
                    "/root/unreadable" -> Result.failure(IllegalStateException(AppStrings.message_task_permission_denied))
                    else -> Result.success(listOf(file("/root/readable/file.txt")))
                }
            },
        )

        assertEquals(
            listOf("/root", "/root/unreadable", "/root/readable"),
            listedPaths,
        )
        assertEquals(listOf<Pair<String, String?>>("/root/unreadable" to AppStrings.message_task_permission_denied), reportedErrors)
        assertEquals(
            setOf("/root/unreadable", "/root/readable", "/root/readable/file.txt"),
            entries.map { entry -> entry.path }.toSet(),
        )
    }

    @Test
    fun tolerantTraversalNeverSwallowsCancellation() = runSuspendTest {
        assertFailsWith<CancellationException> {
            collectDirectoryEntriesAdaptive(
                root = directory("/root"),
                config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
                pathSeparator = "/",
                continueOnDirectoryError = true,
                listChildren = {
                    Result.failure(CancellationException(AppStrings.message_task_cancelled))
                },
            )
        }
    }

    @Test
    fun traversalReportsProgressForEmptyDirectories() = runSuspendTest {
        val reportedProgress = mutableListOf<TraversalScanProgress>()
        val tree = mapOf(
            "/root" to listOf(directory("/root/empty-a"), directory("/root/empty-b")),
            "/root/empty-a" to emptyList(),
            "/root/empty-b" to emptyList(),
        )

        collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 2, maxParallelism = 2, queueCapacity = 4),
            pathSeparator = "/",
            onScanProgress = { progress -> reportedProgress += progress },
            listChildren = { directory ->
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertEquals(3, reportedProgress.last().scannedDirectories)
        assertEquals(2, reportedProgress.last().discoveredEntries)
    }

    @Test
    fun traversalRaisesConcurrencyForFastSuccessfulListings() = runSuspendTest {
        val activeMutex = Mutex()
        var activeRequests = 0
        var maxActiveRequests = 0
        val reportedProgress = mutableListOf<TraversalScanProgress>()
        val tree = mutableMapOf<String, List<FileSimpleInfo>>()
        tree["/root"] = (0 until 12).map { index -> directory("/root/dir-$index") }
        repeat(12) { index ->
            tree["/root/dir-$index"] = listOf(file("/root/dir-$index/file.txt"))
        }

        collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 8, queueCapacity = 32),
            pathSeparator = "/",
            onScanProgress = { progress -> reportedProgress += progress },
            listChildren = { directory ->
                activeMutex.withLock {
                    activeRequests++
                    maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                }
                if (directory.path != "/root") {
                    delay(40.milliseconds)
                }
                activeMutex.withLock { activeRequests-- }
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertTrue(maxActiveRequests > 1)
        assertTrue(reportedProgress.any { progress -> progress.currentParallelism > 1 })
        assertTrue(reportedProgress.all { progress -> progress.maxParallelism == 8 })
    }

    @Test
    fun traversalRefreshesDynamicMaxParallelismDuringScan() = runSuspendTest {
        val activeMutex = Mutex()
        var runtimeMax = 4
        var activeChildRequests = 0
        var maxActiveChildRequests = 0
        val reportedProgress = mutableListOf<TraversalScanProgress>()
        val tree = mutableMapOf<String, List<FileSimpleInfo>>()
        tree["/root"] = (0 until 4).map { index -> directory("/root/dir-$index") }
        repeat(4) { index ->
            tree["/root/dir-$index"] = emptyList()
        }

        collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 4, queueCapacity = 16),
            pathSeparator = "/",
            dynamicMaxParallelismProvider = { runtimeMax },
            onScanProgress = { progress ->
                reportedProgress += progress
                if (progress.scannedDirectories == 1) {
                    runtimeMax = 1
                }
            },
            listChildren = { directory ->
                if (directory.path != "/root") {
                    activeMutex.withLock {
                        activeChildRequests++
                        maxActiveChildRequests = maxOf(maxActiveChildRequests, activeChildRequests)
                    }
                    delay(20.milliseconds)
                    activeMutex.withLock { activeChildRequests-- }
                }
                Result.success(tree[directory.path] ?: emptyList())
            },
        )

        assertEquals(1, maxActiveChildRequests)
        assertTrue(reportedProgress.any { progress -> progress.maxParallelism == 1 })
    }

    @Test
    fun adaptiveParallelismHonorsEndpointAndRuntimeBounds() {
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 8, maxParallelism = 40, queueCapacity = 160, hardMaxParallelism = 64),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Local,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 40,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(
                initialParallelism = 3,
                maxParallelism = 3,
                queueCapacity = 12,
                hardMaxParallelism = 12,
            ),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = 3,
                runtimeRecommendedParallelism = 12,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(
                initialParallelism = 4,
                maxParallelism = 12,
                queueCapacity = 48,
                hardMaxParallelism = 12,
            ),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 32,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 16, queueCapacity = 64),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Network,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 32,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 32, queueCapacity = 128),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Share,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 32,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(
                initialParallelism = 1,
                maxParallelism = 1,
                queueCapacity = 4,
                hardMaxParallelism = 32,
            ),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Share,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 1,
                runtimeBusy = true,
            ),
        )
    }

    @Test
    fun nonDeviceTraversalIgnoresRemoteRecommendedParallelism() {
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 8, maxParallelism = 64, queueCapacity = 256),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Local,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 64,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 32, queueCapacity = 128),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Share,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 32,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            TraversalParallelismConfig(initialParallelism = 4, maxParallelism = 16, queueCapacity = 64),
            resolveTraversalParallelism(
                endpointKind = TraversalEndpointKind.Network,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 16,
                runtimeBusy = false,
            ),
        )
    }

    @Test
    fun adaptiveOperationParallelismHonorsEndpointRuntimeAndDeviceBounds() {
        assertEquals(
            OperationParallelismConfig(
                initialParallelism = 6,
                maxParallelism = 20,
                queueCapacity = 80,
                hardMaxParallelism = 24,
            ),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Local,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 20,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            OperationParallelismConfig(
                initialParallelism = 3,
                maxParallelism = 3,
                queueCapacity = 12,
                hardMaxParallelism = 24,
            ),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = 3,
                runtimeRecommendedParallelism = 16,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            OperationParallelismConfig(
                initialParallelism = 1,
                maxParallelism = 1,
                queueCapacity = 4,
                hardMaxParallelism = 16,
            ),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Share,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 1,
                runtimeBusy = true,
            ),
        )
    }

    @Test
    fun deviceSmallFileOperationsStartWideAndCanScaleToTwentyFour() {
        assertEquals(
            OperationParallelismConfig(
                initialParallelism = 8,
                maxParallelism = 24,
                queueCapacity = 96,
                hardMaxParallelism = 24,
            ),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 24,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            24,
            resolveOperationRuntimeMaxParallelism(
                endpointKind = TraversalEndpointKind.Device,
                remoteRecommendedParallelism = null,
                runtimeRecommendedParallelism = 24,
                runtimeBusy = false,
            ),
        )
    }

    @Test
    fun processItemsAdaptiveReportsActiveParallelism() = runSuspendTest {
        val activeParallelism = mutableListOf<Int>()
        val mutex = Mutex()

        processItemsAdaptive(
            items = listOf(1, 2, 3),
            config = OperationParallelismConfig(
                initialParallelism = 3,
                maxParallelism = 3,
                queueCapacity = 6,
                hardMaxParallelism = 3,
            ),
            onActiveParallelismChanged = { activeCount ->
                mutex.withLock {
                    activeParallelism += activeCount
                }
            },
        ) {
            delay(100.milliseconds)
        }

        assertTrue(activeParallelism.any { count -> count > 1 }, "expected active operation parallelism to be reported")
        assertEquals(0, activeParallelism.last())
    }

    @Test
    fun nonDeviceOperationParallelismIgnoresRemoteRecommendedParallelism() {
        assertEquals(
            OperationParallelismConfig(initialParallelism = 6, maxParallelism = 24, queueCapacity = 96),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Local,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 24,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            OperationParallelismConfig(initialParallelism = 4, maxParallelism = 16, queueCapacity = 64),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Share,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 16,
                runtimeBusy = false,
            ),
        )
        assertEquals(
            OperationParallelismConfig(initialParallelism = 4, maxParallelism = 12, queueCapacity = 48),
            resolveOperationParallelism(
                endpointKind = TraversalEndpointKind.Network,
                remoteRecommendedParallelism = 1,
                runtimeRecommendedParallelism = 12,
                runtimeBusy = false,
            ),
        )
    }

    @Test
    fun traversalEmitsSymbolicLinkAsLeafWithoutListingItsTarget() = runSuspendTest {
        val link = directory("/root/external").withCopy(
            isDirectory = false,
            isSymbolicLink = true,
            isSymbolicLinkKnown = true,
        )
        val listedPaths = mutableListOf<String>()

        val entries = collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
            pathSeparator = "/",
            listChildren = { directory ->
                listedPaths += directory.path
                Result.success(if (directory.path == "/root") listOf(link) else emptyList())
            },
        )

        assertEquals(listOf("/root"), listedPaths)
        assertEquals(listOf(link), entries)
    }

    @Test
    fun remoteTraversalRejectsDirectoryWithUnknownLinkMetadata() = runSuspendTest {
        val error = assertFailsWith<IllegalStateException> {
            collectDirectoryEntriesAdaptive(
                root = directory("/root"),
                config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
                pathSeparator = "/",
                requireKnownSymbolicLinkMetadata = true,
                listChildren = { Result.success(listOf(directory("/root/unknown"))) },
            )
        }

        assertEquals(AppStrings.ui_no_trustworthy_metadata_for_remote_links_please_upgrade_the_remote_and_retry, error.message)
    }

    @Test
    fun copyTraversalReportsAndSkipsSymbolicLinkLeaf() = runSuspendTest {
        val rejectedEntries = mutableListOf<Pair<FileSimpleInfo, Throwable>>()
        val progress = mutableListOf<TraversalScanProgress>()
        val listedPaths = mutableListOf<String>()
        val childDirectory = directory("/root/child")
        val regularFile = file("/root/child/file.txt")
        val symbolicLink = file("/root/link").withCopy(
            isSymbolicLink = true,
            isSymbolicLinkKnown = true,
        )

        val entries = collectDirectoryEntriesAdaptive(
            root = directory("/root"),
            config = TraversalParallelismConfig(initialParallelism = 1, maxParallelism = 1, queueCapacity = 4),
            pathSeparator = "/",
            rejectSymbolicLinkEntries = true,
            onRejectedEntry = { entry, error -> rejectedEntries += entry to error },
            onScanProgress = { item -> progress += item },
            listChildren = { directory ->
                listedPaths += directory.path
                Result.success(
                    if (directory.path == "/root") listOf(symbolicLink, childDirectory) else listOf(regularFile)
                )
            },
        )

        assertEquals(listOf("/root", "/root/child"), listedPaths)
        assertEquals(listOf(childDirectory, regularFile), entries)
        assertEquals(listOf(symbolicLink), rejectedEntries.map { (entry, _) -> entry })
        assertEquals(AppStrings.file_symbolic_link_copy_not_supported, rejectedEntries.single().second.message)
        assertEquals(3, progress.last().discoveredEntries)
    }

    @Test
    fun manifestBuildersKeepDeterministicCopyAndDeleteOrderAfterUnorderedTraversal() {
        val source = directory("/source")
        val destination = directory("/target/source")
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = null,
            children = listOf(
                file("/source/z.txt"),
                directory("/source/nested/deeper"),
                file("/source/nested/deeper/a.txt"),
                directory("/source/nested"),
            ),
        )
        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(plan, com.folderspan.ui.state.main.TaskRuntimeStage.DELETE)

        assertEquals(
            listOf("/source", "/source/nested", "/source/nested/deeper"),
            plan.entries.directories.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf("/source/z.txt", "/source/nested/deeper/a.txt"),
            plan.entries.files.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf("/source/nested/deeper/a.txt", "/source/z.txt"),
            deleteEntries.files.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf("/source/nested/deeper", "/source/nested", "/source"),
            deleteEntries.directories.map { entry -> entry.src.path },
        )
    }

    private fun directory(path: String): FileSimpleInfo {
        return file(path = path, isDirectory = true, size = 0L)
    }

    private fun file(
        path: String,
        isDirectory: Boolean = false,
        size: Long = 1L,
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = if (isDirectory) "" else "text/plain",
            size = size,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
