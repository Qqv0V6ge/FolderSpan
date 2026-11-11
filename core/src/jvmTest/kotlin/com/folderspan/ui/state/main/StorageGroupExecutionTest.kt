package com.folderspan.ui.state.main

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.extensions.fileOperationKey
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.russhwolf.settings.MapSettings
import io.ktor.client.request.get
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.service.network.*
import com.folderspan.ui.state.file.FileRecentState
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StorageGroupExecutionTest {
    private class Provider : StorageProvider by UnsupportedNetworkClient("test") {
        override val capabilities = StorageCapabilities.ReadWrite
        var writable = true
        var offline = false
        var failUploads = false
        var uploadFailure = "injected full destination"
        var onUpload: suspend () -> Unit = {}
        var onList: suspend () -> Unit = {}
        var listError: String? = null
        var uploadErrorFor: (String) -> String? = { null }
        var modifiedTimeOffset: Long? = null
        val modifiedTimeCalls = AtomicInteger()
        private val modifiedTimes = ConcurrentHashMap<String, Long>()
        val contents: MutableMap<String, ByteArray> = ConcurrentHashMap()
        private val uploadCount = AtomicInteger()
        val uploads get() = uploadCount.get()
        override suspend fun effectiveCapabilities() =
            if (offline) Result.failure(IllegalStateException("offline"))
            else Result.success(capabilities.copy(write = writable))
        override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> {
            onList()
            listError?.let { return Result.failure(IllegalStateException(it)) }
            if (offline) return Result.failure(IllegalStateException("offline"))
            return Result.success(contents.filterKeys { it.substringBeforeLast('/', "/").ifEmpty { "/" } == path }.map { (name, bytes) ->
                NetworkFileEntry(name.substringAfterLast('/'), name, false, bytes.size.toLong(), updatedDate = modifiedTimes[name] ?: 1,
                    isSymbolicLinkKnown = true)
            })
        }
        override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) =
            list(path.path, requestId, batchId)
        override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit) =
            upload(localPath, remotePath.path, size, onProgress)
        override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit,
            readChunk: suspend () -> ByteArray?) = uploadFromSource(remotePath.path, size, onProgress, readChunk)
        override suspend fun createFolder(path: StoragePath) = createFolder(path.path)
        override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> =
            if (path.path == "/backup") Result.success(NetworkFileEntry("backup", "/backup", true, isSymbolicLinkKnown = true))
            else contents[path.path]?.let { Result.success(NetworkFileEntry(path.path.substringAfterLast('/'),
                path.path, false, it.size.toLong(), updatedDate = 1, isSymbolicLinkKnown = true)) }
                ?: Result.failure(StorageEntryNotFoundException())
        override suspend fun createFolder(path: String) = Result.success(true)
        override suspend fun setModifiedTime(path: String, millis: Long): Result<Boolean> {
            val offset = modifiedTimeOffset ?: return Result.success(false)
            modifiedTimeCalls.incrementAndGet()
            modifiedTimes[path] = millis + offset
            return Result.success(true)
        }
        override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
            contents.remove(path)
            return Result.success(true)
        }
        override suspend fun delete(path: StoragePath, isDirectory: Boolean) = delete(path.path, isDirectory)
        override suspend fun download(remotePath: StoragePath, localPath: String, size: Long,
            onProgress: (Long, Long) -> Unit) = download(remotePath.path, localPath, size, onProgress)
        override suspend fun download(remotePath: String, localPath: String, size: Long,
            onProgress: (Long, Long) -> Unit): Result<Boolean> {
            val bytes = contents[remotePath] ?: return Result.failure(StorageEntryNotFoundException())
            Files.write(Path.of(localPath), bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return Result.success(true)
        }
        override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
            uploadCount.incrementAndGet()
            onUpload()
            uploadErrorFor(remotePath)?.let {
                contents[remotePath] = byteArrayOf(1)
                return Result.failure(IllegalStateException(it))
            }
            if (failUploads) return Result.failure(IllegalStateException(uploadFailure))
            contents[remotePath] = Files.readAllBytes(Path.of(localPath))
            onProgress(size, size)
            return Result.success(true)
        }
        override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit,
            readChunk: suspend () -> ByteArray?): Result<Boolean> {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) bytes.write(readChunk() ?: break)
            uploadCount.incrementAndGet()
            onUpload()
            uploadErrorFor(remotePath)?.let {
                contents[remotePath] = byteArrayOf(1)
                return Result.failure(IllegalStateException(it))
            }
            if (failUploads) return Result.failure(IllegalStateException(uploadFailure))
            contents[remotePath] = bytes.toByteArray()
            onProgress(size, size)
            return Result.success(true)
        }
    }

    private class Store : SyncTaskStore {
        val tasks = linkedMapOf<Long, SyncTask>()
        val runs = linkedMapOf<Pair<Long, Long>, SyncRunRecord>()
        val failed = linkedMapOf<Pair<Long, Long>, List<SyncFailureItem>>()
        override fun loadTasks() = tasks.values.toList()
        override fun saveTask(task: SyncTask) = task.copy(id = task.id.takeIf { it > 0 } ?: (tasks.size + 1).toLong()).also { tasks[it.id] = it }
        override fun deleteTask(taskId: Long) { tasks.remove(taskId) }
        override fun appendRun(record: SyncRunRecord) { runs[record.taskId to record.runId] = record }
        override fun loadRuns(taskId: Long) = runs.values.filter { it.taskId == taskId }
        override fun deleteRuns(taskId: Long) { runs.keys.removeAll { it.first == taskId } }
        override fun saveFailedItems(taskId: Long, runId: Long, items: List<SyncFailureItem>): Int {
            failed[taskId to runId] = items.take(10_000)
            return (items.size - 10_000).coerceAtLeast(0)
        }
        override fun loadFailedItems(taskId: Long, runId: Long) = failed[taskId to runId]
    }

    private suspend fun fixture(block: suspend (SyncState, NetworkState, Store, List<Provider>, Path) -> Unit) {
        val root = Files.createTempDirectory("storage-group-test")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = createMcpHttpTestDatabase(driver)
        val networkState = NetworkState(database)
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(root.resolve("cleanup").toString()))
        val store = Store()
        val state = SyncState(store)
        val providers = listOf(Provider(), Provider())
        stopKoin()
        startKoin { modules(module {
            single { database }
            single { networkState }
            single { taskState }
            single { DeviceState() }
            single { MainState() }
            single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try {
            org.koin.mp.KoinPlatformTools.defaultContext().get().get<FileState>()
            providers.forEachIndexed { index, provider ->
                val network = object : Network("target-$index", "/", "FTP", "test-$index.invalid", "", "fixture-password") {
                    override val client = provider
                    override val taskState = taskState
                }
                networkState.entries.add(NetworkEntry(index + 1L, network, true))
            }
            Files.writeString(root.resolve("a.txt"), "immutable original")
            Files.writeString(root.resolve("b.txt"), "second")
            block(state, networkState, store, providers, root)
        } finally {
            state.stop()
            taskState.stop()
            stopKoin()
            driver.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun create(state: SyncState, networks: NetworkState, root: Path, destinationPath: String = "/backup"): SyncTask {
        val destinations = networks.entries.map { it.storageDestination(destinationPath) }
        return state.createOrUpdateTask(destinations.first().applyTo(SyncTask(
            0, "backup", enabled = false, sourceType = SyncEndpointType.Local, sourcePath = root.toString(),
            targetType = SyncEndpointType.Network, targetPath = "/backup", destinations = destinations, backup = true,
            filterRaw = "cleanup/*", retryCount = 0,
        ))).getOrThrow()
    }

    @Test fun skippedGroupFilesDoNotRequireSourceBytesOrCreateDigests() = runBlocking {
        fixture { state, networks, _, providers, root ->
            for (provider in providers) {
                provider.contents["/backup/a.txt"] = "keep target a".encodeToByteArray()
                provider.contents["/backup/b.txt"] = "keep target b".encodeToByteArray()
            }
            val original = create(state, networks, root)
            val task = state.createOrUpdateTask(original.copy(conflictPolicy = SyncConflictPolicy.Skip)).getOrThrow()
            providers[0].onList = { Files.deleteIfExists(root.resolve("a.txt")) }
            state.runTaskInternal(task, "manual")
            val result = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, result.status, result.toString())
            assertEquals(2, result.sourceManifest.size)
            assertTrue(result.sourceDigests.isEmpty())
            assertTrue(providers.all { it.uploads == 0 })
            for (provider in providers) {
                assertEquals("keep target a", provider.contents["/backup/a.txt"]?.decodeToString())
                assertEquals("keep target b", provider.contents["/backup/b.txt"]?.decodeToString())
            }
        }
    }

    @Test fun independentResultsRetryOnlyFailuresAndKeepOriginalRunConfiguration() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[1].failUploads = true
            val task = create(state, networks, root)
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.PartialSuccess, first.status, first.targets.toString())
            assertEquals(listOf(2, 2), providers.map { it.uploads })
            assertEquals("immutable original", providers[0].contents["/backup/a.txt"]?.decodeToString())
            assertEquals(2, first.sourceManifest.size)
            assertEquals(2, first.sourceDigests.size)
            providers[1].failUploads = false
            state.tasks[0] = task.copy(destinations = listOf(task.destinations[0].copy(path = "/elsewhere")), policyVersion = 9)
            state.runTaskInternal(first.policySnapshot!!, "retry", storageRetry = first,
                selectedTargetKeys = setOf(first.targets[1].operationTaskKey))
            val retried = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, retried.status, retried.targets.toString())
            assertEquals(listOf(2, 4), providers.map { it.uploads })
            assertTrue(providers.all { "/elsewhere/a.txt" !in it.contents })
            assertTrue(Files.exists(root.resolve("a.txt")))
        }
    }

    @Test fun knownFailedUploadsRespectConfiguredAutomaticRetry() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[1].onUpload = { providers[1].failUploads = providers[1].uploads == 1 }
            val task = create(state, networks, root).copy(retryCount = 1)
            state.runTaskInternal(task, "manual")
            val record = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, record.status, record.targets.toString())
            assertEquals(listOf(2, 3), providers.map { it.uploads })
        }
    }

    @Test fun ordinarySyncBoundsParallelCopiesAndIsolatesFailedFile() = runBlocking {
        fixture { state, networks, _, providers, root ->
            Files.delete(root.resolve("a.txt"))
            Files.delete(root.resolve("b.txt"))
            repeat(10) { Files.writeString(root.resolve("$it.txt"), "payload") }
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            providers[0].onUpload = {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                try { delay(30) } finally { active.decrementAndGet() }
            }
            providers[0].uploadErrorFor = { if (it.endsWith("/3.txt")) "injected failure" else null }
            val task = state.createOrUpdateTask(SyncTask(0, "parallel", sourceType = SyncEndpointType.Local,
                sourcePath = root.toString(), targetType = SyncEndpointType.Network,
                targetRef = networks.entries[0].network.buildProtocolId(), targetPath = "/backup",
                filterRaw = "cleanup/*", parallelism = 3, retryCount = 1)).getOrThrow()
            state.runTaskInternal(task, "manual")
            val record = state.recordsFor(task.id).first()
            assertEquals(9, record.copiedCount, record.toString())
            assertEquals(1, record.failureCount)
            assertEquals(11, providers[0].uploads)
            assertTrue(maximum.get() in 1..3)
            assertFalse("/backup/3.txt" in providers[0].contents)
            assertEquals(listOf("3.txt"), state.failedItemsFor(task.id, record.runId)?.map { it.relativePath })
        }
    }

    @Test fun modificationTimeReadBackMismatchDisablesFurtherWritesForThatRun() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[0].modifiedTimeOffset = 10_000
            val task = state.createOrUpdateTask(SyncTask(0, "timestamp", sourceType = SyncEndpointType.Local,
                sourcePath = root.toString(), targetType = SyncEndpointType.Network,
                targetRef = networks.entries[0].network.buildProtocolId(), targetPath = "/backup",
                filterRaw = "cleanup/*", parallelism = 2)).getOrThrow()
            state.runTaskInternal(task, "manual")
            val record = state.recordsFor(task.id).first()
            assertEquals(2, record.copiedCount, record.toString())
            assertEquals(0, record.failureCount)
            assertEquals(1, providers[0].modifiedTimeCalls.get())
        }
    }

    @Test fun groupModificationTimeMismatchDisablesWritesAcrossFilesForEachTarget() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers.forEach { it.modifiedTimeOffset = 10_000 }
            val task = create(state, networks, root)
            state.runTaskInternal(task, "manual")
            val record = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, record.status, record.targets.toString())
            assertEquals(listOf(2, 2), providers.map { it.uploads })
            assertEquals(listOf(1, 1), providers.map { it.modifiedTimeCalls.get() })
        }
    }

    @Test fun unavailableSourceConnectionFailsBeforeWritingAndRedactsCredentials() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val target = Files.createDirectory(root.resolve("target"))
            val task = state.createOrUpdateTask(SyncTask(0, "connect-source", sourceType = SyncEndpointType.Network,
                sourceRef = networks.entries[0].network.buildProtocolId(), sourcePath = "/",
                targetType = SyncEndpointType.Local, targetPath = target.toString())).getOrThrow()
            providers[0].listError = "Cannot connect https://alice:secret@host/; password=hunter2; token=private"
            state.runTaskInternal(task, "manual")
            val record = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Failure, record.status)
            assertFalse(record.message.contains("hunter2"))
            assertFalse(record.message.contains("secret"))
            assertFalse(record.message.contains("private"))
            assertTrue(Files.list(target).use { it.findAny().isEmpty })
            assertEquals(0, providers[0].uploads)
        }
    }

    @Test fun networkSourceConnectsBeforeScanningAndTransferring() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val target = Files.createDirectory(root.resolve("target"))
            val task = state.createOrUpdateTask(SyncTask(0, "connect-source", sourceType = SyncEndpointType.Network,
                sourceRef = networks.entries[0].network.buildProtocolId(), sourcePath = "/",
                targetType = SyncEndpointType.Local, targetPath = target.toString())).getOrThrow()
            providers[0].contents["/source.txt"] = "connected source".encodeToByteArray()
            val connectedDuringList = mutableListOf<Boolean>()
            providers[0].onList = { connectedDuringList.add(networks.connectedEntries.any { it.id == networks.entries[0].id }) }
            state.runTaskInternal(task, "manual")
            assertEquals(SyncRunStatus.Success, state.recordsFor(task.id).first().status)
            assertFalse(connectedDuringList.first())
            assertTrue(connectedDuringList.drop(1).all { it })
            assertEquals("connected source", Files.readString(target.resolve("source.txt")))
        }
    }

    @Test fun sourceConnectionTimesOutAfterThirtySecondsWithoutTargetWrites() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val target = Files.createDirectory(root.resolve("target"))
            val task = state.createOrUpdateTask(SyncTask(0, "connect-timeout", sourceType = SyncEndpointType.Network,
                sourceRef = networks.entries[0].network.buildProtocolId(), sourcePath = "/",
                targetType = SyncEndpointType.Local, targetPath = target.toString())).getOrThrow()
            providers[0].onList = { awaitCancellation() }
            val started = System.nanoTime()
            withTimeout(35_000) { state.runTaskInternal(task, "manual") }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed in 29_500..34_999)
            assertEquals(SyncRunStatus.Failure, state.recordsFor(task.id).first().status)
            assertTrue(Files.list(target).use { it.findAny().isEmpty })
        }
    }

    @Test fun twelveThousandFailuresKeepExactCountsAndRetryFallsBackToWholeTask() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val source = Files.createDirectory(root.resolve("bulk"))
            repeat(12_000) { Files.writeString(source.resolve("$it.txt"), "x") }
            providers[0].uploadErrorFor = { "injected failure" }
            val task = state.createOrUpdateTask(SyncTask(0, "bulk failures", sourceType = SyncEndpointType.Local,
                sourcePath = source.toString(), targetType = SyncEndpointType.Network,
                targetRef = networks.entries[0].network.buildProtocolId(), targetPath = "/backup",
                parallelism = 8, retryCount = 0)).getOrThrow()
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Failure, first.status)
            assertEquals(12_000, first.failureCount)
            assertEquals(0, first.copiedCount)
            assertEquals(2_000, first.unsavedFailureCount)
            assertEquals(10_000, state.failedItemsFor(task.id, first.runId)?.size)
            assertTrue(first.items.isEmpty())
            Files.list(source).use { files -> files.forEach(Files::delete) }
            Files.writeString(source.resolve("new-after-failure.txt"), "new")
            providers[0].uploadErrorFor = { null }
            state.retryFailedItems(task.id, first.runId)
            withTimeout(15_000) {
                while (state.recordsFor(task.id).none { it.retryOfRunId == first.runId }) delay(10)
            }
            val retried = state.recordsFor(task.id).first { it.retryOfRunId == first.runId }
            assertEquals("retry", retried.trigger)
            assertEquals(SyncRunStatus.Success, retried.status, retried.toString())
            assertEquals(1, retried.copiedCount)
            assertEquals("new", providers[0].contents["/backup/new-after-failure.txt"]?.decodeToString())
            assertTrue(retried.message.contains(strings.AppStrings.sync_retry_whole_task))
        }
    }

    @Test fun uncertainResultsWithoutTheirOriginalCheckpointAreNotResubmitted() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[1].failUploads = true
            providers[1].uploadFailure = "Storage upload result uncertain"
            val task = create(state, networks, root).copy(retryCount = 3)
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            assertTrue(first.targets[1].uncertain)
            providers[1].failUploads = false
            state.runTaskInternal(task, "retry", storageRetry = first,
                selectedTargetKeys = setOf(first.targets[1].operationTaskKey))
            assertEquals(listOf(2, 2), providers.map { it.uploads })
            val retried = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.PartialSuccess, retried.status)
            assertEquals(SyncRunStatus.Success, retried.targets[0].status)
            assertTrue(retried.targets[1].uncertain)
            assertTrue(Files.exists(root.resolve("a.txt")))
        }
    }

    @Test fun recommendationsChooseOnlyEligibleCandidatesSavedInTheRule() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val task = create(state, networks, root).copy(selectRecommended = true,
                preferredEntryIds = listOf(networks.entries[0].id))
            providers[0].offline = true
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, first.status, first.targets.toString())
            assertEquals(listOf(task.destinations[1]), first.targets.map { it.destination })
            assertEquals(listOf(0, 2), providers.map { it.uploads })
            state.runTaskInternal(task.copy(destinations = listOf(task.destinations[0])), "schedule")
            assertEquals(SyncRunStatus.Failure, state.recordsFor(task.id).first().status)
            assertEquals(listOf(0, 2), providers.map { it.uploads })
        }
    }

    @Test fun changedSourceCannotBeRetriedAsTheOriginalVersion() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[1].failUploads = true
            val task = create(state, networks, root)
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            Files.writeString(root.resolve("a.txt"), "different content")
            providers[1].failUploads = false
            state.runTaskInternal(task, "retry", storageRetry = first,
                selectedTargetKeys = setOf(first.targets[1].operationTaskKey))
            val retried = state.recordsFor(task.id).first()
            assertNotEquals(SyncRunStatus.Success, retried.status)
            assertEquals("immutable original", providers[0].contents["/backup/a.txt"]?.decodeToString())
            assertNull(providers[1].contents["/backup/a.txt"])
        }
    }

    @Test fun manualAndScheduledRunsShareOneLockAndAcceptedTargetsSurviveEditing() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            providers[0].onUpload = { entered.complete(Unit); release.await() }
            val task = create(state, networks, root)
            val running = async(Dispatchers.Default) { state.runTaskInternal(task, "manual") }
            withTimeout(15_000) { entered.await() }
            state.tasks[0] = task.copy(destinations = listOf(task.destinations[0]), policyVersion = 2)
            state.runTaskInternal(task, "schedule")
            assertNull(state.runNowIfIdle(task.id))
            release.complete(Unit)
            running.await()
            assertEquals(2, state.recordsFor(task.id).first().targets.size)
            assertEquals(listOf(2, 2), providers.map { it.uploads })
            assertEquals(1, state.recordsFor(task.id).size)
        }
    }

    @Test fun retryingOneFailedFileRetainsTheOtherFailures() = runBlocking {
        fixture { state, networks, _, providers, root ->
            providers[1].failUploads = true
            val task = create(state, networks, root)
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            val target = first.targets[1]
            val sourceKey = target.items.first { !it.success && it.source != null }.source!!.fileOperationKey()
            providers[1].failUploads = false
            state.runTaskInternal(task, "retry", storageRetry = first,
                selectedTargetKeys = setOf(target.operationTaskKey),
                selectedFiles = mapOf(target.operationTaskKey to setOf(sourceKey)))
            val retried = state.recordsFor(task.id).first()
            assertEquals(listOf(2, 3), providers.map { it.uploads })
            assertEquals(1, retried.targets[1].failureCount)
            assertEquals(SyncRunStatus.PartialSuccess, retried.status)
        }
    }

    @Test fun cancellationKeepsTheCompletedDestinationAndSource() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var count = 0
            providers[1].onUpload = { if (++count == 2) { entered.complete(Unit); release.await() } }
            val task = create(state, networks, root)
            val running = async(Dispatchers.Default) { state.runTaskInternal(task, "manual") }
            withTimeout(15_000) { entered.await() }
            state.controlStorageRun(task.id, StorageGroupControl.Cancel)
            release.complete(Unit)
            running.await()
            val result = state.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, result.targets[0].status)
            assertEquals(SyncRunStatus.Canceled, result.targets[1].status)
            assertEquals(SyncRunStatus.PartialSuccess, result.status)
            assertEquals("immutable original", providers[0].contents["/backup/a.txt"]?.decodeToString())
            assertEquals("second", providers[0].contents["/backup/b.txt"]?.decodeToString())
            assertTrue(Files.exists(root.resolve("a.txt")))
        }
    }

    @Test fun deletingAnActiveGroupWaitsForWritersAndThenRemovesTheRule() = runBlocking {
        fixture { state, networks, store, providers, root ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var count = 0
            providers[1].onUpload = { if (++count == 2) { entered.complete(Unit); release.await() } }
            val task = create(state, networks, root)
            val running = async(Dispatchers.Default) { state.runTaskInternal(task, "manual") }
            withTimeout(15_000) { entered.await() }
            state.deleteTask(task.id)
            assertNotNull(state.getTask(task.id))
            release.complete(Unit)
            withTimeout(15_000) {
                running.await()
                while (state.getTask(task.id) != null || task.id in store.tasks) delay(10)
            }
            assertTrue(state.activeStorageGroups.isEmpty())
            assertEquals("immutable original", providers[0].contents["/backup/a.txt"]?.decodeToString())
            assertEquals("second", providers[0].contents["/backup/b.txt"]?.decodeToString())
            assertTrue(Files.exists(root.resolve("a.txt")))
        }
    }

    @Test fun pausingAndResumingTheGroupDoesNotRepeatCompletedTargets() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var count = 0
            providers[1].onUpload = { if (++count == 2) { entered.complete(Unit); release.await() } }
            val task = create(state, networks, root)
            val running = async(Dispatchers.Default) { state.runTaskInternal(task, "manual") }
            withTimeout(15_000) { entered.await() }
            state.controlStorageRun(task.id, StorageGroupControl.Pause)
            release.complete(Unit)
            delay(100)
            assertFalse(running.isCompleted)
            state.controlStorageRun(task.id, StorageGroupControl.Resume)
            withTimeout(15_000) { running.await() }
            assertEquals(SyncRunStatus.Success, state.recordsFor(task.id).first().status)
            assertEquals(listOf(2, 2), providers.map { it.uploads })
        }
    }

    @Test fun restartRetainsSuccessfulTargetsAndDeletionOfSourceDoesNotDeleteBackups() = runBlocking {
        fixture { state, networks, store, providers, root ->
            val task = create(state, networks, root)
            state.runTaskInternal(task, "manual")
            val first = state.recordsFor(task.id).first()
            Files.delete(root.resolve("a.txt"))
            state.runTaskInternal(task, "schedule")
            assertTrue(providers.all { "/backup/a.txt" in it.contents })
            val interrupted = first.copy(runId = first.runId + 1000, targets = listOf(
                first.targets[0], first.targets[1].copy(status = SyncRunStatus.Running)))
            store.appendRun(interrupted)
            state.start()
            val restored = state.recordsFor(task.id).first { it.runId == interrupted.runId }
            assertEquals(SyncRunStatus.Success, restored.targets[0].status)
            assertEquals(SyncRunStatus.Failure, restored.targets[1].status)
            assertEquals(interrupted.sourceDigests, restored.sourceDigests)
        }
    }

    @Test fun stagingFailureDoesNotStartDestinationWrites() = runBlocking {
        fixture { state, networks, _, providers, root ->
            val task = create(state, networks, root)
            val source = FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("a.txt").toString()).getOrThrow()
            assertFails {
                state.stageStorageSource(task, source, com.folderspan.data.main.Local(), root.toString(), state.createOperationTask(task))
            }
            assertEquals(listOf(0, 0), providers.map { it.uploads })
            assertEquals("immutable original", Files.readString(root.resolve("a.txt")))
        }
    }

    @Test fun ordinaryUploadsCannotUsePrivateTaskCopiesAsArbitrarySources() = runBlocking {
        fixture { _, networks, _, providers, _ ->
            val path = StorageContentPlatform.createStage(567899, "security-test").getOrThrow()
            try {
                FileUtils.writeBytes(FileAccessPermission.Allowed, path, 3, byteArrayOf(1, 2, 3), 0).getOrThrow()
                assertTrue(networks.entries[0].network.uploadFileFromLocal(path, "/backup/private", 3).isFailure)
                assertEquals(listOf(0, 0), providers.map { it.uploads })
            } finally {
                StorageContentPlatform.cleanup(567899).getOrThrow()
            }
        }
    }

    @Test fun podmanNextcloudAndSeafileRunOneBackupWithIndependentFailures() = runBlocking {
        val url = System.getenv("FOLDERSPAN_SMART_CLOUD_TEST_URL").orEmpty()
        assumeTrue("Requires this task's disposable Podman cloud service", url.isNotBlank())
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val store = StorageAccountStore(MapSettings(), { error("Fixture tokens do not refresh") })
        val manager = StorageAuthorizationManager(http, store)
        val folder = "/smart-" + UUID.randomUUID()
        val clients = mutableListOf<StorageProvider>()
        val output = Files.createTempDirectory("smart-received")
        try {
            val flow = manager.begin(StorageAuthorizationRequest("Nextcloud", url + "/nextcloud"))
            http.get(flow.loginUrl)
            val nextcloud = checkNotNull(manager.poll(flow.handle).account)
            val seafile = manager.installSeafile(SeafileAuthorizationRequest(url + "/seafile", "fixture-account-token", false))
            val library = manager.libraries(seafile.id).single()
            val configs = listOf(
                NetworkConnectionConfig("Nextcloud", url + "/nextcloud", "", "",
                    extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = nextcloud.id))),
                NetworkConnectionConfig("Seafile", url + "/seafile", "", "",
                    extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = seafile.id, libraryId = library.id))),
            )
            clients += NextcloudNetworkClient(configs[0], { store.credential(configs[0]) }, { StorageCapabilities.ReadWrite }, http)
            clients += SeafileNetworkClient(configs[1], StorageHttpApi({ store.credential(configs[1]).accessToken }, http))
            clients.forEach { assertTrue(it.createFolder(folder).getOrThrow()) }
            fixture { state, networks, _, _, root ->
                networks.entries.clear()
                configs.forEachIndexed { index, config ->
                    val provider = clients[index]
                    val network = object : Network(config.protocol, "/", config.protocol, config.host, "", "", extras = config.extras) {
                        override val client = provider
                    }
                    networks.entries.add(NetworkEntry(501L + index, network, true))
                }
                val task = create(state, networks, root, folder)
                state.runTaskInternal(task, "manual")
                assertEquals(SyncRunStatus.Success, state.recordsFor(task.id).first().status,
                    state.recordsFor(task.id).first().targets.toString())
                clients.forEachIndexed { index, client ->
                    val destination = output.resolve("a-" + index)
                    assertTrue(client.download(folder + "/a.txt", destination.toString(), 18).getOrThrow())
                    assertEquals("immutable original", Files.readString(destination))
                }
                Files.delete(root.resolve("a.txt"))
                state.runTaskInternal(task, "schedule")
                assertTrue(clients.all { client -> client.list(folder).getOrThrow().any { it.name == "a.txt" } })
                val removed = store.remove(seafile.id)
                try {
                    state.runTaskInternal(task, "schedule")
                    val partial = state.recordsFor(task.id).first()
                    assertEquals(SyncRunStatus.PartialSuccess, partial.status)
                    assertEquals(SyncRunStatus.Success, partial.targets[0].status)
                    assertEquals(SyncRunStatus.Failure, partial.targets[1].status)
                } finally { store.install(removed) }
                Files.write(root.resolve("too-large.bin"), ByteArray(3 * 1024 * 1024))
                state.runTaskInternal(task, "schedule")
                val limited = state.recordsFor(task.id).first()
                assertTrue(limited.targets.all { it.failureCount > 0 })
                assertTrue(Files.exists(root.resolve("b.txt")))
            }
        } finally {
            clients.forEach { client -> try { client.delete(folder, true) } finally { client.close() } }
            output.toFile().deleteRecursively()
            http.close()
        }
    }

    @Test fun officialDropboxEncryptedBackupSharesSourceWithNextcloudAndKeepsCompletedTargetOnRetry() = runBlocking {
        val grantFile = System.getenv("FOLDERSPAN_SMART_DROPBOX_GRANT_FILE").orEmpty()
        val url = System.getenv("FOLDERSPAN_SMART_CLOUD_TEST_URL").orEmpty()
        assumeTrue("Requires an owned official Dropbox grant and the disposable Podman service",
            grantFile.isNotBlank() && url.isNotBlank())
        val grant = try {
            val path = Path.of(grantFile)
            check(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(path) < 256 * 1024)
            check(Files.getPosixFilePermissions(path).all { it.name.startsWith("OWNER_") })
            Json.parseToJsonElement(Files.readString(path)).jsonObject.also {
                check(it["status"]?.jsonPrimitive?.content == "authorized")
                check(it["protocol"]?.jsonPrimitive?.content == "Dropbox")
                check(it.getValue("expiresAt").jsonPrimitive.long > System.currentTimeMillis() + 120_000)
            }
        } catch (_: Throwable) { throw AssertionError("Official grant must be current and private") }
        val accountId = UUID.randomUUID().toString()
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val accountStore = StorageAccountStore(MapSettings(), { error("Fixture does not refresh") })
        val manager = StorageAuthorizationManager(http, accountStore)
        val folder = "/codex-smart-" + UUID.randomUUID()
        val owned = mutableListOf<Pair<StorageProvider, StoragePath>>()
        val clients = mutableListOf<StorageProvider>()
        val output = Files.createTempDirectory("smart-official-output")
        var encryptedConfig: NetworkConnectionConfig? = null
        var accountInstalled = false
        var failure: AssertionError? = null
        var phase = "setup"
        try {
            StorageAccounts.store.install(StorageCredential(accountId, StorageAccountKind.Dropbox,
                grant.getValue("providerAccountId").jsonPrimitive.content, "Disposable backup test",
                accessToken = grant.getValue("accessToken").jsonPrimitive.content,
                expiresAt = grant.getValue("expiresAt").jsonPrimitive.long))
            accountInstalled = true
            val flow = manager.begin(StorageAuthorizationRequest("Nextcloud", url + "/nextcloud"))
            http.get(flow.loginUrl)
            val nextcloud = checkNotNull(manager.poll(flow.handle).account)
            val nextBase = NetworkConnectionConfig("Nextcloud", url + "/nextcloud", "", "",
                extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = nextcloud.id)))
            val dropboxBase = NetworkConnectionConfig("Dropbox", "", "", "",
                extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = accountId,
                    connectionId = UUID.randomUUID().toString())))
            val bases = listOf(
                NextcloudNetworkClient(nextBase, { accountStore.credential(nextBase) }, { StorageCapabilities.ReadWrite }, http),
                NetworkClientFactory.create(dropboxBase),
            )
            val configs = listOf(nextBase, dropboxBase).mapIndexed { index, base ->
                val provider = bases[index]
                assertTrue(provider.createFolder(folder).getOrThrow())
                owned += provider to StoragePath(folder)
                val directory = provider.stat(StoragePath(folder)).getOrThrow()
                owned[index] = provider to StoragePath(folder, directory.reference)
                base.copy(extras = base.extras.copy(cloud = base.extras.cloud.copy(
                    rootId = if (index == 0) folder else checkNotNull(directory.reference).id)))
            }
            clients += NextcloudNetworkClient(configs[0], { accountStore.credential(configs[0]) },
                { StorageCapabilities.ReadWrite }, http)
            clients += NetworkClientFactory.create(configs[1])
            clients.forEach { assertTrue(it.createFolder("/backup").getOrThrow()) }
            encryptedConfig = configs[1]
            phase = "create the encrypted destination"
            StorageContentPlatform.create(configs[1]).getOrThrow()
            val password = "disposable backup verification".toCharArray()
            val recovery = output.resolve("recovery-v2.bin")
            Files.createFile(recovery)
            Files.setPosixFilePermissions(recovery, setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            phase = "export encryption recovery material"
            Files.write(recovery, StorageContentPlatform.exportRecovery(configs[1], password).getOrThrow())
            phase = "enable encryption from verified recovery"
            StorageContentPlatform.verifyBackupAndEnable(configs[1], Files.readAllBytes(recovery), password).getOrThrow()
            password.fill('\u0000')
            fixture { state, networks, _, _, root ->
                networks.entries.clear()
                configs.forEachIndexed { index, config ->
                    val provider = clients[index]
                    val network = object : Network(config.protocol, "/", config.protocol, config.host, "", "", extras = config.extras) {
                        override val client = provider
                    }
                    networks.entries.add(NetworkEntry(701L + index, network, true))
                }
                val task = create(state, networks, root)
                phase = "run the multi-provider backup"
                state.runTaskInternal(task, "manual")
                assertEquals(SyncRunStatus.Success, state.recordsFor(task.id).first().status)
                phase = "verify decrypted destination bytes"
                networks.entries.forEachIndexed { index, entry ->
                    phase = "verify decrypted destination bytes for target $index"
                    val location = entry.network.getFile("/backup/a.txt").getOrThrow()
                    val source = entry.network.getFile(location).getOrThrow()
                    val received = output.resolve("received-" + index)
                    assertTrue(entry.network.downloadFileToLocal(source, received.toString()) { _, _ -> }.getOrThrow())
                    assertEquals("immutable original", Files.readString(received))
                }
                val cipher = clients[1].stat(StoragePath("/backup/a.txt")).getOrThrow()
                assertTrue(cipher.size > 18)
                val raw = output.resolve("stored-ciphertext")
                assertTrue(clients[1].download("/backup/a.txt", raw.toString(), cipher.size).getOrThrow())
                assertFalse(Files.readAllBytes(raw).contentEquals("immutable original".encodeToByteArray()))
                Files.delete(root.resolve("a.txt"))
                val offline = networks.entries.removeAt(0)
                phase = "recover only the failed destination"
                state.runTaskInternal(task, "schedule")
                val partial = state.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.PartialSuccess, partial.status)
                assertEquals(SyncRunStatus.Success, partial.targets[1].status)
                networks.entries.add(0, offline)
                state.runTaskInternal(task, "retry", storageRetry = partial,
                    selectedTargetKeys = setOf(partial.targets[0].operationTaskKey))
                val retried = state.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Success, retried.status)
                assertEquals(partial.targets[1], retried.targets[1])
                assertNotNull(clients[1].stat(StoragePath("/backup/a.txt")).getOrThrow().reference)
                assertTrue(Files.exists(root.resolve("b.txt")))
            }
        } catch (caught: Throwable) {
            val location = caught.stackTrace.firstOrNull { it.className.startsWith("com.folderspan") }
            val redacted = AssertionError("Official backup: " + phase + " failed (" + caught.javaClass.simpleName + ") at " + location)
            failure = redacted
            throw redacted
        } finally {
            var clean = true
            withContext(NonCancellable) {
                encryptedConfig?.let { config ->
                    if (runCatching { StorageContentPlatform.setEnabled(config, false).getOrThrow() }.isFailure) clean = false
                    if (runCatching { StorageContentPlatform.lock(config).getOrThrow() }.isFailure) clean = false
                }
                clients.forEach { if (runCatching { it.close() }.isFailure) clean = false }
                owned.forEach { (provider, path) ->
                    if (runCatching { assertTrue(provider.delete(path, true).getOrThrow()) }.isFailure) clean = false
                    if (runCatching { provider.close() }.isFailure) clean = false
                }
                if (accountInstalled && runCatching { StorageAccounts.store.remove(accountId) }.isFailure) clean = false
                if (!output.toFile().deleteRecursively()) clean = false
                http.close()
            }
            if (!clean) {
                val cleanup = AssertionError("Official backup test resource cleanup was not confirmed")
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    @Test fun destinationsAreRevalidatedAfterPermissionsOrConnectionsChange() = runBlocking {
        fixture { _, networks, _, providers, _ ->
            val destination = networks.entries[0].storageDestination("/backup")
            assertTrue(networks.validateStorageDestination(destination).isSuccess)
            providers[0].writable = false
            assertTrue(networks.validateStorageDestination(destination).isFailure)
            networks.entries.removeAt(0)
            assertTrue(networks.validateStorageDestination(destination).isFailure)
            val session = networks.entries[0].copy(id = -1, isPersisted = false)
            networks.entries[0] = session
            assertTrue(networks.validateStorageDestination(session.storageDestination("/backup")).isSuccess)
        }
    }
}
