package com.folderspan.data.main.network

import strings.AppStrings

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.network.StorageReference
import com.folderspan.service.network.StorageProvider
import com.folderspan.service.network.StorageAdvancedFeature
import com.folderspan.service.network.StorageFeatureAvailability
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class NetworkCopyToTest : ChineseLocalizationTest() {
    @Test
    fun sameNetworkFileCopyUsesServerSideCopyWithoutDownloadOrUpload() = runBlocking {
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient()
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )
        localTaskState.addOrUpdate(task)
        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "source.bin",
            isDirectory = false,
            path = "/source/source.bin",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 512L,
        )
        val dest = src.withCopy(
            path = "/target/source.bin",
        )

        val result = network.copyTo(task, src, dest)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))
        assertEquals(
            listOf("/source/source.bin" to "/target/source.bin"),
            fakeClient.copyRequests,
        )
        assertEquals(0, fakeClient.downloadCalls)
        assertEquals(0, fakeClient.uploadCalls)
    }

    @Test
    fun sameNetworkNativeCopyReturnsUnsupportedToCoordinatorWithoutInternalFallback() = runBlocking {
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient(
            copyResult = Result.failure(NetworkUnsupportedException(AppStrings.ui_test_network_copy_to_the_server_does_not_support_copying))
        )
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )
        localTaskState.addOrUpdate(task)
        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "source.bin",
            isDirectory = false,
            path = "/source/source.bin",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 512L,
        )
        val dest = src.withCopy(path = "/target/source.bin")

        val result = network.copyTo(task, src, dest)

        assertTrue(result.isFailure)
        assertEquals(1, fakeClient.copyRequests.size)
        assertEquals(0, fakeClient.downloadCalls)
        assertEquals(0, fakeClient.uploadCalls)
    }

    @Test
    fun localDirectoryUploadCreatesAllFoldersBeforeUploadingFiles() = runBlocking {
        val sourceDir = createTempDirectory(prefix = "network-copy-order-").toFile()
        val nestedDir = File(sourceDir, "nested")
        nestedDir.mkdirs()
        val childFile = File(nestedDir, "file.txt")
        childFile.writeText("hello")

        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient()
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Local
        )
        localTaskState.addOrUpdate(task)

        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = sourceDir.name,
            isDirectory = true,
            path = sourceDir.absolutePath,
            protocol = FileProtocol.Local,
            size = 0L
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 0L
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isSuccess)
            assertEquals(
                listOf("/remote-root", "/remote-root/nested", "/remote-root/nested/file.txt"),
                fakeClient.recordedPaths
            )
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun successfulLocalDirectoryUploadDoesNotRetainPerFileProgressResults() = runBlocking {
        val sourceDir = createTempDirectory(prefix = "network-copy-progress-").toFile()
        val childFile = File(sourceDir, "file.txt")
        childFile.writeText("hello")

        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient(
            uploadProgressSteps = listOf(2L, 5L)
        )
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Local
        )
        localTaskState.addOrUpdate(task)

        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = sourceDir.name,
            isDirectory = true,
            path = sourceDir.absolutePath,
            protocol = FileProtocol.Local,
            size = 0L
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 0L
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isSuccess)
            val storedTask = localTaskState.tasks.first { it.key == task.key }
            assertTrue(storedTask.result.isEmpty())
            assertNull(storedTask.transientResultPath())
            assertNull(storedTask.transientResultMessage())
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun localDirectoryUploadRecordsSymbolicLinkFailureAndContinuesRegularFiles() = runBlocking {
        val parent = createTempDirectory(prefix = "network-copy-link-entry-").toFile()
        val sourceDir = File(parent, "source").apply { mkdirs() }
        val regularFile = File(sourceDir, "regular.txt").apply { writeText("regular") }
        val externalFile = File(parent, "external.txt").apply { writeText("external") }
        val link = File(sourceDir, "alias.txt").toPath()
        runCatching { Files.createSymbolicLink(link, externalFile.toPath()) }.getOrNull()
            ?: run {
                parent.deleteRecursively()
                return@runBlocking
            }

        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient()
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Local,
        )
        localTaskState.addOrUpdate(task)
        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = sourceDir.name,
            isDirectory = true,
            path = sourceDir.absolutePath,
            protocol = FileProtocol.Local,
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isFailure)
            assertEquals(listOf(regularFile.absolutePath), fakeClient.uploadedLocalPaths)
            assertFalse(fakeClient.recordedPaths.contains("/remote-root/alias.txt"))
            val storedTask = localTaskState.tasks.first { it.key == task.key }
            assertTrue(
                localTaskState.getFailedRetryEntries(storedTask)
                    .first { it.resultPath == "/remote-root/alias.txt" }
                    .failureMessage
                    .contains(AppStrings.ui_test_network_copy_to_this_is_skipped)
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun localDirectoryUploadReturnsFailureAndRecordsChildError() = runBlocking {
        val sourceDir = createTempDirectory(prefix = "network-copy-upload-").toFile()
        val childFile = File(sourceDir, "file.txt")
        childFile.writeText("hello")

        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient(
            uploadResult = Result.failure(Exception(AppStrings.ui_test_network_copy_to_s3_query_failed_http_403))
        )
        val network = buildTestNetwork(fakeClient, localTaskState)

        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Local
        )
        localTaskState.addOrUpdate(task)

        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = sourceDir.name,
            isDirectory = true,
            path = sourceDir.absolutePath,
            protocol = FileProtocol.Local,
            size = 0L
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 0L
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull())
            assertTrue(result.exceptionOrNull()!!.message!!.contains(AppStrings.ui_test_network_copy_to_batch_upload_failed))
            assertEquals(listOf("/remote-root", "/remote-root/file.txt"), fakeClient.recordedPaths)

            val storedTask = localTaskState.tasks.first { it.key == task.key }
            assertEquals(
                AppStrings.ui_test_network_copy_to_s3_query_failed_http_403,
                localTaskState.getFailedRetryEntries(storedTask)
                    .first { it.resultPath == "/remote-root/file.txt" }
                    .failureMessage
            )
            assertTrue(storedTask.result["/remote-root"]!!.contains(AppStrings.ui_test_network_copy_to_batch_upload_failed))
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun localToNetworkCopyRejectsProtectedApplicationData() = runBlocking {
        val protectedPath = resolveDesktopApplicationDataDirectory().resolve("folderspan.db").toString()
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient()
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Local,
        )
        localTaskState.addOrUpdate(task)
        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "folderspan.db",
            isDirectory = false,
            path = protectedPath,
            protocol = FileProtocol.Local,
            size = 1L,
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "folderspan.db",
            isDirectory = false,
            path = "/upload/db",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 0L,
        )

        val result = network.copyTo(task, src, dest)

        assertTrue(result.isFailure)
        assertIs<AuthorityException>(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("application_private_data"))
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains(protectedPath))
        assertEquals(0, fakeClient.uploadCalls)
        assertTrue(fakeClient.uploadedLocalPaths.isEmpty())
    }

    @Test
    fun localToNetworkCopyRejectsLeafSymbolicLinkWithoutFollowingIt() = runBlocking {
        val directory = Files.createTempDirectory("network-copy-link")
        val secret = directory.resolve("secret.txt")
        val link = directory.resolve("alias")
        Files.writeString(secret, "secret")
        val created = runCatching { Files.createSymbolicLink(link, secret) }.getOrNull()
            ?: return@runBlocking
        try {
            val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
            val fakeClient = FakeNetworkClient()
            val network = buildTestNetwork(fakeClient, localTaskState)
            val task = Task(
                taskType = TaskType.Copy,
                status = StatusEnum.LOADING,
                protocol = FileProtocol.Local,
            )
            localTaskState.addOrUpdate(task)
            val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
                name = "alias",
                isDirectory = false,
                path = created.toString(),
                protocol = FileProtocol.Local,
                size = 6L,
            )
            val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
                name = "alias",
                isDirectory = false,
                path = "/upload/alias",
                protocol = FileProtocol.Network,
                protocolId = network.protocolId,
                size = 0L,
            )

            val result = network.copyTo(task, src, dest)

            assertTrue(result.isFailure)
            assertEquals(0, fakeClient.uploadCalls)
            assertTrue(fakeClient.uploadedLocalPaths.isEmpty())
        } finally {
            Files.deleteIfExists(link)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun networkDirectoryDownloadReturnsFailureAndSummaryWhenChildTransferFails() = runBlocking {
        val targetRoot = createTempDirectory(prefix = "network-copy-download-").toFile()
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient(
            listedEntries = mapOf(
                "/remote-root" to listOf(
                    NetworkFileEntry(
                        name = "file.txt",
                        path = "/remote-root/file.txt",
                        isDirectory = false,
                        size = 5L,
                        reference = StorageReference(id = "stable-file-id", parentId = "root-id"),
                    )
                )
            ),
            downloadResults = mapOf(
                "/remote-root/file.txt" to Result.failure(Exception(AppStrings.ui_test_network_copy_to_the_network_connection_has_been_interrupted))
            )
        )
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )
        localTaskState.addOrUpdate(task)

        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 1L,
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = targetRoot.name,
            isDirectory = true,
            path = targetRoot.absolutePath,
            protocol = FileProtocol.Local,
            size = 0L,
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isFailure)
            val storedTask = localTaskState.tasks.first { it.key == task.key }
            val childTargetPath = File(targetRoot, "file.txt").absolutePath
            assertEquals(
                AppStrings.ui_test_network_copy_to_the_network_connection_has_been_interrupted,
                localTaskState.getFailedRetryEntries(storedTask)
                    .first { it.resultPath == childTargetPath }
                    .failureMessage
            )
            assertEquals(
                "stable-file-id",
                localTaskState.getFailedRetryEntries(storedTask)
                    .first { it.resultPath == childTargetPath }.srcStorageReference?.id,
            )
            assertTrue(storedTask.result[targetRoot.absolutePath]!!.contains(AppStrings.ui_test_network_copy_to_batch_download_failed))
            assertFalse(File(childTargetPath).exists())
        } finally {
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun networkDirectoryDownloadRejectsParentDirectoryEntriesBeforeWriting() = runBlocking {
        val parent = createTempDirectory(prefix = "network-copy-zipslip-parent-").toFile()
        val targetRoot = File(parent, "dest")
        targetRoot.mkdirs()
        val outside = File(parent, "secret.txt")
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val fakeClient = FakeNetworkClient(
            listedEntries = mapOf(
                "/remote-root" to listOf(
                    NetworkFileEntry(
                        name = "..",
                        path = "/remote-root/../secret.txt",
                        isDirectory = false,
                        size = 5L,
                    )
                )
            ),
        )
        val network = buildTestNetwork(fakeClient, localTaskState)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
        )
        localTaskState.addOrUpdate(task)
        val src = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = "remote-root",
            isDirectory = true,
            path = "/remote-root",
            protocol = FileProtocol.Network,
            protocolId = network.protocolId,
            size = 1L,
        )
        val dest = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            name = targetRoot.name,
            isDirectory = true,
            path = targetRoot.absolutePath,
            protocol = FileProtocol.Local,
            size = 0L,
        )

        try {
            val result = network.copyTo(task, src, dest)

            assertTrue(result.isFailure)
            assertEquals(0, fakeClient.downloadCalls)
            assertFalse(outside.exists())
            assertTrue(targetRoot.listFiles().orEmpty().isEmpty())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun duplicateCloudNamesFailBeforeCreatingTheDownloadDirectory() = runBlocking {
        val parent = createTempDirectory(prefix = "network-copy-duplicate-").toFile()
        val target = File(parent, "destination")
        val state = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val provider = FakeNetworkClient(
            listedEntries = mapOf(
                "/cloud" to listOf("first-id", "second-id").map { id ->
                    NetworkFileEntry(
                        name = "same.txt",
                        path = "/cloud/same.txt",
                        isDirectory = false,
                        size = 5L,
                        reference = StorageReference(id = id, parentId = "root-id"),
                    )
                },
            ),
        )
        val network = buildTestNetwork(provider, state)
        val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING, protocol = FileProtocol.Network, protocolId = network.protocolId)
        state.addOrUpdate(task)
        val source = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            path = "/cloud", isDirectory = true, protocol = FileProtocol.Network, protocolId = network.protocolId,
            storageReference = StorageReference(id = "root-id"),
        )
        val destination = FileSimpleInfo.nullFileSimpleInfo().withCopy(
            path = target.absolutePath, isDirectory = true, protocol = FileProtocol.Local,
        )
        try {
            val result = network.copyTo(task, source, destination)
            assertTrue(result.isFailure)
            assertEquals(0, provider.downloadCalls)
            assertFalse(target.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun unsupportedCloudSessionsRetryTheWholeFileWithoutCreatingUploadCheckpoints() = runBlocking {
        for (protocol in listOf(NetworkProtocol.Nextcloud, NetworkProtocol.Seafile)) {
            val directory = createTempDirectory(prefix = "network-file-retry-").toFile()
            val payload = ByteArray(65_537) { (it % 251).toByte() }
            val sourceFile = File(directory, "source.bin").apply { writeBytes(payload) }
            val store = TestTaskRuntimePersistenceStore()
            val state = TaskState(runtimeStore = store)
            val attempts = mutableListOf<ByteArray>()
            val provider = FakeNetworkClient(uploadHandler = { path, _, size ->
                assertEquals(payload.size.toLong(), size)
                attempts += File(path).readBytes()
                if (attempts.size == 1) Result.failure(IOException("interrupted whole-file upload"))
                else Result.success(true)
            })
            val network = buildTestNetwork(provider, state, protocol)
            val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING, protocol = FileProtocol.Local)
            state.addOrUpdate(task)
            val source = FileSimpleInfo.nullFileSimpleInfo().withCopy(
                path = sourceFile.absolutePath, name = sourceFile.name,
                protocol = FileProtocol.Local, size = sourceFile.length(),
            )
            val target = source.withCopy(path = "/target.bin", protocol = FileProtocol.Network, protocolId = network.protocolId)
            try {
                assertEquals(StorageFeatureAvailability.UNSUPPORTED,
                    provider.advancedCapabilities(StoragePath(target.path)).getOrThrow()
                        .availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
                assertNull(network.uploadTaskFile(task, source, target, {},
                    readRange = { _, _ -> error("File-level fallback must not read resumable chunks") }))
                assertTrue(network.copyTo(task, source, target).isFailure)
                assertTrue(store.transferCheckpoints.isEmpty())
                assertTrue(network.copyTo(task, source, target).getOrThrow())
                assertEquals(2, attempts.size)
                attempts.forEach { assertContentEquals(payload, it) }
                assertTrue(store.transferCheckpoints.isEmpty())
                assertTrue(sourceFile.exists())
                assertContentEquals(payload, sourceFile.readBytes())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun buildTestNetwork(
        fakeClient: StorageProvider,
        localTaskState: TaskState,
        protocol: NetworkProtocol = NetworkProtocol.S3,
    ): Network {
        return object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = protocol.name,
            host = "http://127.0.0.1:9000",
            username = "minioadmin",
            password = "minioadmin"
        ) {
            override val client: StorageProvider = fakeClient
            override val taskState: TaskState = localTaskState
        }
    }

    private class FakeNetworkClient(
        private val copyResult: Result<Boolean> = Result.success(true),
        private val uploadResult: Result<Boolean> = Result.success(true),
        private val listedEntries: Map<String, List<NetworkFileEntry>> = emptyMap(),
        private val downloadResults: Map<String, Result<Boolean>> = emptyMap(),
        private val uploadProgressSteps: List<Long> = emptyList(),
        private val uploadHandler: ((String, String, Long) -> Result<Boolean>)? = null,
    ) : StorageProvider {
        val recordedPaths = mutableListOf<String>()
        val uploadedLocalPaths = mutableListOf<String>()
        val streamedUploads = mutableListOf<Pair<String, ByteArray>>()
        val copyRequests = mutableListOf<Pair<String, String>>()
        var downloadCalls = 0
            private set
        var uploadCalls = 0
            private set
        var streamUploadCalls = 0
            private set

        override suspend fun list(
            path: String,
            requestId: String?,
            batchId: String?
        ): Result<List<NetworkFileEntry>> = Result.success(listedEntries[path].orEmpty())

        override suspend fun download(
            remotePath: String,
            localPath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit
        ): Result<Boolean> {
            downloadCalls++
            return downloadResults[remotePath] ?: Result.success(true)
        }

        override suspend fun upload(
            localPath: String,
            remotePath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit
        ): Result<Boolean> {
            uploadCalls++
            uploadedLocalPaths += localPath
            recordedPaths += remotePath
            uploadProgressSteps.forEach { doneBytes ->
                onProgress(doneBytes, size)
            }
            return uploadHandler?.invoke(localPath, remotePath, size) ?: uploadResult
        }

        override suspend fun uploadFromSource(
            remotePath: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
            readChunk: suspend () -> ByteArray?,
        ): Result<Boolean> {
            streamUploadCalls++
            recordedPaths += remotePath
            val payload = ArrayList<Byte>()
            var doneBytes = 0L
            while (true) {
                val chunk = readChunk() ?: break
                payload += chunk.toList()
                doneBytes += chunk.size
                onProgress(doneBytes, if (size >= 0L) size else doneBytes)
            }
            streamedUploads += remotePath to payload.toByteArray()
            return uploadResult
        }

        override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
            copyRequests += sourcePath to targetPath
            return copyResult
        }

        override suspend fun rename(path: String, newPath: String): Result<Boolean> = Result.success(true)

        override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = Result.success(true)

        override suspend fun createFolder(path: String): Result<Boolean> {
            recordedPaths += path
            return Result.success(true)
        }

        override suspend fun createFile(path: String): Result<Boolean> = Result.success(true)
    }
}
