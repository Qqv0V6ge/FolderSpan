package com.folderspan.data.main.network

import strings.AppStrings

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.network.NetworkClient
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.runBlocking
import java.io.File
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

    private fun buildTestNetwork(
        fakeClient: NetworkClient,
        localTaskState: TaskState,
    ): Network {
        return object : Network(
            name = "S3",
            pathSeparator = "/",
            protocol = NetworkProtocol.S3.name,
            host = "http://127.0.0.1:9000",
            username = "minioadmin",
            password = "minioadmin"
        ) {
            override val client: NetworkClient = fakeClient
            override val taskState: TaskState = localTaskState
        }
    }

    private class FakeNetworkClient(
        private val copyResult: Result<Boolean> = Result.success(true),
        private val uploadResult: Result<Boolean> = Result.success(true),
        private val listedEntries: Map<String, List<NetworkFileEntry>> = emptyMap(),
        private val downloadResults: Map<String, Result<Boolean>> = emptyMap(),
        private val uploadProgressSteps: List<Long> = emptyList(),
    ) : NetworkClient {
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
            return uploadResult
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
