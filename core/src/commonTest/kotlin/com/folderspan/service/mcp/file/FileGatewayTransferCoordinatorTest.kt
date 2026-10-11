package com.folderspan.service.mcp.file

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.automation.McpDeviceShareDto
import com.folderspan.service.mcp.automation.McpFileFacade
import com.folderspan.service.mcp.automation.McpFileSharing
import com.folderspan.service.mcp.automation.McpLinkShareDto
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskFailureResultStore
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileGatewayTransferCoordinatorTest {
    @Test
    fun everyDirectedEndpointCombinationCopiesThroughBoundedChunks() = runTest {
        val endpoints = listOf(
            FileEndpointRef(FileProtocol.Local),
            FileEndpointRef(FileProtocol.Share, "share"),
            FileEndpointRef(FileProtocol.Device, "device"),
            FileEndpointRef(FileProtocol.Network, "network"),
        )
        endpoints.forEach { sourceEndpoint ->
            endpoints.forEach { targetEndpoint ->
                val source = MemoryGateway(sourceEndpoint).apply { putFile("/payload.bin", ByteArray(19) { it.toByte() }) }
                val target = if (sourceEndpoint == targetEndpoint) source else MemoryGateway(targetEndpoint)
                target.putDirectory("/dest")
                val resolver = resolverFor(source, target)
                val report = FileGatewayTransferCoordinator(resolver, chunkBytes = 4).copy(
                    sources = listOf(FileLocator(sourceEndpoint, "/payload.bin")),
                    targetDirectory = FileLocator(targetEndpoint, "/dest"),
                )

                assertTrue(report.succeeded, "$sourceEndpoint -> $targetEndpoint: $report")
                assertEquals(19, target.fileBytes("/dest/payload.bin")?.size)
                assertTrue(target.maxWriteBytes <= 4)
            }
        }
    }

    @Test
    fun conflictPoliciesArePredictable() = runTest {
        suspend fun execute(policy: FileConflictPolicy): Pair<FileTransferReport, MemoryGateway> {
            val source = MemoryGateway(FileEndpointRef(FileProtocol.Local)).apply { putFile("/a.txt", "new".encodeToByteArray()) }
            val target = MemoryGateway(FileEndpointRef(FileProtocol.Device, "target")).apply {
                putFile("/a.txt", "old".encodeToByteArray())
            }
            val report = FileGatewayTransferCoordinator(resolverFor(source, target)).copy(
                listOf(FileLocator(source.endpoint, "/a.txt")),
                FileLocator(target.endpoint, "/"),
                policy,
            )
            return report to target
        }

        assertEquals(FileTransferItemStatus.Failed, execute(FileConflictPolicy.Error).first.items.single().status)
        assertEquals(FileTransferItemStatus.Skipped, execute(FileConflictPolicy.Skip).first.items.single().status)
        assertEquals("new", execute(FileConflictPolicy.Overwrite).second.fileBytes("/a.txt")?.decodeToString())
        val renamed = execute(FileConflictPolicy.Rename).second
        assertEquals("old", renamed.fileBytes("/a.txt")?.decodeToString())
        assertEquals("new", renamed.fileBytes("/a (1).txt")?.decodeToString())
    }

    @Test
    fun moveNeverDeletesBeforeCopyAndDeleteRetryDoesNotCopyAgain() = runTest {
        val source = MemoryGateway(FileEndpointRef(FileProtocol.Device, "source")).apply {
            putFile("/safe.txt", "safe".encodeToByteArray())
            putFile("/retry.txt", "retry".encodeToByteArray())
        }
        val failingTarget = MemoryGateway(FileEndpointRef(FileProtocol.Share, "failing")).apply {
            failWrites = true
        }
        val failedCopy = FileGatewayTransferCoordinator(resolverFor(source, failingTarget)).move(
            listOf(FileLocator(source.endpoint, "/safe.txt")),
            FileLocator(failingTarget.endpoint, "/"),
        )
        assertEquals(FileTransferItemStatus.Failed, failedCopy.items.single().status)
        assertTrue(source.fileBytes("/safe.txt") != null)
        assertEquals(0, source.deleteCalls)

        val target = MemoryGateway(FileEndpointRef(FileProtocol.Network, "target"))
        source.failDeletes = true
        val coordinator = FileGatewayTransferCoordinator(resolverFor(source, target), chunkBytes = 2)
        val first = coordinator.move(
            listOf(FileLocator(source.endpoint, "/retry.txt")),
            FileLocator(target.endpoint, "/"),
        )
        assertEquals(FileTransferItemStatus.SourceDeleteFailed, first.items.single().status)
        val readsAfterCopy = source.readCalls
        assertEquals("retry", target.fileBytes("/retry.txt")?.decodeToString())

        source.failDeletes = false
        val retried = coordinator.retryDeleteSources(first)
        assertTrue(retried.succeeded)
        assertEquals(readsAfterCopy, source.readCalls, "delete-source retry must not repeat copy")
        assertFalse(source.fileBytes("/retry.txt") != null)
    }

    @Test
    fun deleteReturnsTaskIdBeforeBackgroundDeletionCompletes() = runTest {
        val source = MemoryGateway(FileEndpointRef(FileProtocol.Share, "share")).apply {
            putFile("/delete.txt", "delete".encodeToByteArray())
            deleteGate = CompletableDeferred()
        }
        val resolver = resolverFor(source)
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val submitter = FileGatewayTaskSubmitter(
            taskState = taskState,
            coordinator = FileGatewayTransferCoordinator(resolver),
            scope = backgroundScope,
        )

        val taskId = submitter.submitDelete(listOf(FileLocator(source.endpoint, "/delete.txt")))
        val submitted = assertNotNull(taskState.getTask(taskId))
        assertEquals(TaskType.Delete, submitted.taskType)
        assertTrue(source.fileBytes("/delete.txt") != null)

        withTimeout(3_000) {
            while (source.deleteCalls == 0) yield()
        }
        assertTrue(source.fileBytes("/delete.txt") != null)
        source.deleteGate?.complete(Unit)
        withTimeout(3_000) {
            while (taskState.getTask(taskId)?.status != StatusEnum.SUCCESS) yield()
        }
        assertFalse(source.fileBytes("/delete.txt") != null)
    }

    @Test
    fun taskSubmitterRejectsProtectedLocalPathBeforeTaskCreation() = runTest {
        val share = MemoryGateway(FileEndpointRef(FileProtocol.Share, "share"))
        val resolver = resolverFor(share)
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
        val submitter = FileGatewayTaskSubmitter(
            taskState = taskState,
            coordinator = FileGatewayTransferCoordinator(resolver),
            scope = backgroundScope,
        )

        val separator = PathUtils.getPathSeparator()
        val protectedPath = PathUtils.getCachePath().trimEnd('/', '\\') +
            separator + "mcp-file-staging" + separator + "secret.tmp"
        val error = assertFailsWith<FileEndpointException> {
            submitter.submitDelete(
                listOf(FileLocator(FileEndpointRef(FileProtocol.Local), protectedPath))
            )
        }

        assertEquals(FileEndpointErrorCode.PermissionDenied, error.code)
        assertTrue(taskState.tasks.isEmpty())
    }

    @Test
    fun fileFacadeDelegatesLinkAndDeviceSharing() = runTest {
        val gateway = MemoryGateway(FileEndpointRef(FileProtocol.Share, "share"))
        val resolver = resolverFor(gateway)
        val sharing = RecordingFileSharing()
        val facade = McpFileFacade(
            resolver = resolver,
            reader = FileContentReader(resolver),
            submitter = FileGatewayTaskSubmitter(
                taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore()),
                coordinator = FileGatewayTransferCoordinator(resolver),
                scope = backgroundScope,
            ),
            sharing = sharing,
        )
        val locator = FileLocator(gateway.endpoint, "/report.txt")

        val link = facade.shareLink(listOf(locator), allowHidden = true, allowUpload = true)
        val device = facade.shareDevice(listOf(locator), deviceId = "device-a", allowHidden = false)

        assertEquals(listOf("https://192.168.1.2/share"), link.urls)
        assertTrue(link.allowHidden && link.allowUpload)
        assertEquals("device-a", device.deviceId)
        assertEquals("accepted", device.status)
        assertEquals(listOf("link", "device"), sharing.calls)
    }

    private fun resolverFor(vararg gateways: MemoryGateway): FileEndpointResolver {
        val resolver = FileEndpointResolver()
        gateways.groupBy { it.endpoint.protocol }.forEach { (protocol, candidates) ->
            resolver.register(protocol) { sourceId -> candidates.firstOrNull { it.endpoint.sourceId == sourceId } }
        }
        return resolver
    }
}

private class MemoryGateway(
    override val endpoint: FileEndpointRef,
) : FileEndpointGateway {
    override val pathSeparator: String = "/"
    override val permissions = FileEndpointPermissions(true, true, true, true, true)
    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf("/")
    var failWrites = false
    var failDeletes = false
    var deleteGate: CompletableDeferred<Unit>? = null
    var readCalls = 0
    var deleteCalls = 0
    var maxWriteBytes = 0

    fun putFile(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    fun putDirectory(path: String) {
        directories += path
    }

    fun fileBytes(path: String): ByteArray? = files[path]

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> = runCatching {
        if (path !in directories) error("not found")
        val prefix = path.trimEnd('/') + "/"
        val children = buildList {
            directories.filter { it != path && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .forEach { add(entry(it, true, 0L)) }
            files.filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .forEach { (child, bytes) -> add(entry(child, false, bytes.size.toLong())) }
        }
        children.sortedBy { it.name }
    }

    override suspend fun info(path: String): Result<FileEndpointEntry> = runCatching {
        when {
            path in directories -> entry(path, true, 0L)
            path in files -> entry(path, false, files.getValue(path).size.toLong())
            else -> error("not found")
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> = runCatching {
        readCalls++
        val bytes = files.getValue(path)
        val end = minOf(bytes.size, offset.toInt() + length)
        FileRangeResult(bytes.copyOfRange(offset.toInt(), end), bytes.size.toLong(), offset)
    }

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> = Result.failure(UnsupportedOperationException())

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = runCatching {
        if (!directories.add(path)) error("exists")
        entry(path, true, 0L)
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = runCatching {
        if (path in files || path in directories) error("exists")
        files[path] = byteArrayOf()
        entry(path, false, 0L)
    }

    override suspend fun delete(path: String): Result<Boolean> = runCatching {
        deleteCalls++
        deleteGate?.await()
        if (failDeletes) error("delete failed")
        files.remove(path) != null || directories.remove(path)
    }

    override suspend fun writeRange(path: String, fileSize: Long, offset: Long, bytes: ByteArray): Result<Boolean> = runCatching {
        if (failWrites) error("write failed")
        maxWriteBytes = maxOf(maxWriteBytes, bytes.size)
        val target = files.getOrPut(path) { ByteArray(fileSize.toInt()) }
        bytes.copyInto(target, offset.toInt())
        true
    }

    private fun entry(path: String, directory: Boolean, size: Long): FileEndpointEntry = FileEndpointEntry(
        name = if (path == "/") "/" else path.substringAfterLast('/'),
        path = path,
        isDirectory = directory,
        mimeType = "",
        size = size,
        createdAt = 0L,
        updatedAt = 0L,
        endpoint = endpoint,
        permissions = permissions,
        isHidden = false,
        isSymbolicLink = false,
        isSymbolicLinkKnown = true,
    )
}

private class RecordingFileSharing : McpFileSharing {
    val calls = mutableListOf<String>()

    override suspend fun shareLink(
        locators: List<FileLocator>,
        allowHidden: Boolean,
        allowUpload: Boolean,
    ): McpLinkShareDto {
        calls += "link"
        return McpLinkShareDto(
            urls = listOf("https://192.168.1.2/share"),
            expiresAt = 1L,
            allowHidden = allowHidden,
            allowUpload = allowUpload,
            fileCount = locators.size,
            tlsFingerprintSha256 = "fingerprint",
        )
    }

    override suspend fun shareDevice(
        locators: List<FileLocator>,
        deviceId: String,
        allowHidden: Boolean,
    ): McpDeviceShareDto {
        calls += "device"
        return McpDeviceShareDto(
            operationId = "share-1",
            deviceId = deviceId,
            status = "accepted",
            fileCount = locators.size,
        )
    }
}
