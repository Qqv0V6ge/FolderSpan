package com.folderspan.ui.state.file

import androidx.compose.runtime.snapshots.Snapshot
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.*
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SftpDriveExtras
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.security.KeyPairGenerator
import com.folderspan.data.main.share.DeviceBackedShareSession
import com.folderspan.data.main.share.Share
import com.folderspan.service.data.*
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiRuntimeAddress
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.file.DeviceFileService
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.service.network.*
import com.folderspan.service.path.DevicePathClient
import com.folderspan.testing.grantTestAdministratorAccess
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class CopyDestinationMixedJvmTest {
    private class Provider : StorageProvider by UnsupportedNetworkClient("fixture") {
        override val capabilities = StorageCapabilities.ReadWrite
        var failUploads = false
        var beforeRead: suspend () -> Unit = {}
        val contents = ConcurrentHashMap<String, ByteArray>()
        override suspend fun effectiveCapabilities() = Result.success(capabilities)
        override suspend fun list(path: String, requestId: String?, batchId: String?) = Result.success(contents.filterKeys { it.substringBeforeLast('/', "/") == path }.map { (path, bytes) ->
            NetworkFileEntry(path.substringAfterLast('/'), path, false, bytes.size.toLong(), updatedDate = 1, isSymbolicLinkKnown = true)
        })
        override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) = list(path.path, requestId, batchId)
        override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = when {
            path.path == "/" || path.path == "/backup" -> Result.success(NetworkFileEntry(path.path, path.path, true, isSymbolicLinkKnown = true))
            contents.containsKey(path.path) -> Result.success(NetworkFileEntry(path.path.substringAfterLast('/'), path.path, false, contents.getValue(path.path).size.toLong(), updatedDate = 1, isSymbolicLinkKnown = true))
            else -> Result.failure(StorageEntryNotFoundException())
        }
        override suspend fun createFolder(path: String) = Result.success(true)
        override suspend fun createFolder(path: StoragePath) = createFolder(path.path)
        override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> { contents.remove(path); return Result.success(true) }
        override suspend fun delete(path: StoragePath, isDirectory: Boolean) = delete(path.path, isDirectory)
        override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
            if (failUploads) return Result.failure(IllegalStateException("injected write failure"))
            beforeRead()
            contents[remotePath] = Files.readAllBytes(Path.of(localPath))
            onProgress(size, size)
            return Result.success(true)
        }
        override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit) = upload(localPath, remotePath.path, size, onProgress)
        override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> {
            if (failUploads) return Result.failure(IllegalStateException("injected write failure"))
            beforeRead()
            val output = java.io.ByteArrayOutputStream()
            while (true) output.write(readChunk() ?: break)
            contents[remotePath] = output.toByteArray(); onProgress(size, size)
            return Result.success(true)
        }
        override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = uploadFromSource(remotePath.path, size, onProgress, readChunk)
    }

    private class Client(private val service: DeviceFileService, private val token: String, private val supportsWriteCheck: Boolean = true, private val beforeWrite: () -> Unit = {}) : DeviceFileClient {
        override suspend fun checkDirectoryWriteAccess(path: String): Result<Boolean> = if (supportsWriteCheck) service.checkDirectoryWriteAccess(token, path)
            else Result.failure(UnsupportedOperationException("legacy endpoint"))
        override suspend fun renames(renameInfos: List<RenameInfo>) = service.renames(token, RenameRequest(renameInfos))
        override suspend fun createFolders(paths: List<String>) = service.createFolders(token, CreateFolderRequest(paths))
        override suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> { beforeWrite(); return service.createFiles(token, CreateFileRequest(paths)) }
        override suspend fun deletes(paths: List<String>) = service.deletes(token, DeleteRequest(paths))
        override suspend fun writeBytes(fileSize: Long, blockIndex: Long, blockLength: Long, path: String, byteArray: ByteArray, startOffset: Long): Result<Boolean> {
            beforeWrite()
            return service.writeBytes(token, WriteBytesRequest(fileSize, blockIndex, blockLength, path, byteArray, blockStartOffset = startOffset))
        }
        override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long) = service.readBytes(token, ReadBytesRequest(path, startOffset, endOffset))
        override suspend fun getFileByPath(path: String) = service.getFileByPath(token, GetFileByPathRequest(path))
        override suspend fun getFileByPathAndName(path: String, name: String) = getFileByPath("$path/$name")
        override suspend fun getFileInfoByPath(path: String): Result<FileInfo> = unused()
        override suspend fun getFileInfoByPathAndName(path: String, name: String): Result<FileInfo> = unused()
        override suspend fun readFileLines(path: String): Result<List<String>> = unused()
        override suspend fun appendToFile(path: String, content: String): Result<Boolean> = unused()
        override suspend fun copyPath(srcPath: String, destPath: String, onProgress: (suspend (CopyPathProgress) -> Unit)?, requestId: String?): Result<Boolean> = unused()
        override suspend fun controlCopy(requestId: String, action: CopyPathControlAction): Result<Boolean> = unused()
        private fun <T> unused(): Result<T> = Result.failure(UnsupportedOperationException("unused fixture operation"))
    }

    private class Paths(private val root: Path) : DevicePathClient {
        override suspend fun getRootPaths(requestId: String?, batchId: String?) = Result.success(listOf(PathInfo(root.toString(), 0, 0)))
        override suspend fun listPath(request: ListRequest, requestId: String?, batchId: String?) =
            PathUtils.getFileAndFolder(FileAccessPermission.Allowed, request.path).map { files ->
                files.map { it.copy(protocol = FileProtocol.Device, protocolId = "device") }
            }
        override fun traversePath(path: String, requestId: String?, batchId: String?) = flowOf(Result.success(emptyList<FileSimpleInfo>()))
        override suspend fun exists(path: String) = Result.success(Files.exists(Path.of(path)))
        override suspend fun createDirectory(path: String) = runCatching { Files.createDirectories(Path.of(path)); true }
        override suspend fun deleteDirectory(path: String) = runCatching { Files.deleteIfExists(Path.of(path)) }
    }

    private class Fixture(val state: FileState, val tasks: TaskState, val root: Path, val provider: Provider, val certificate: DeviceCertificateState, val writeAttempts: java.util.concurrent.atomic.AtomicInteger) {
        suspend fun request(includeShare: Boolean = false): CopyDestinationRequest {
            val files = mutableListOf(FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow())
            if (includeShare) files += FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("shared/from-share.txt").toString()).getOrThrow().copy(protocol = FileProtocol.Share, protocolId = "share")
            return state.createCopyDestinationRequest(files).also { request ->
                for ((protocol, path) in listOf(FileProtocol.Local to root.resolve("local").toString(), FileProtocol.Device to root.resolve("device").toString(), FileProtocol.Network to "/backup")) {
                    val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == protocol }.endpoint
                    val selected = FileSimpleInfo.pathFileSimpleInfo(path).copy(protocol = protocol, protocolId = endpoint.endpoint.sourceId)
                    assertEquals(CopyDestinationSelectionResult.Added, request.state.select(request.resolveDirectory(endpoint, selected)))
                }
            }
        }
        suspend fun submitReplacing(request: CopyDestinationRequest): CopyBatchAdmissionResult = coroutineScope {
            val submission = async { request.submit() }
            withTimeout(20_000) { while (!request.operationState.isWarningOperationDialog.value) delay(10) }
            request.operationState.files.indices.forEach { i ->
                request.operationState.files[i] = request.operationState.files[i].copy(type = FileOperationType.Replace)
            }
            request.operationState.updateWarningOperationDialog(false)
            submission.await()
        }
        suspend fun await(result: CopyBatchAdmissionResult) {
            withTimeout(30_000) { while (result.accepted.flatMap { it.taskKeys }.any { tasks.getTask(it)?.status in listOf(StatusEnum.LOADING, StatusEnum.PAUSE) }) delay(20) }
        }
    }

    @Test fun discoveryObservesDeviceAndNetworkChangesUntilClosed() = runBlocking {
        fixture {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val discovery = CopyDestinationDiscovery("live", scope)
            suspend fun waitFor(condition: () -> Boolean) {
                Snapshot.sendApplyNotifications()
                withTimeout(10_000) { while (!condition()) delay(10) }
            }
            try {
                discovery.observe(state.deviceState, state.networkState)
                val original = state.deviceState.devices.single()
                waitFor { discovery.loads.value.any { it.endpoint.endpoint.sourceId == original.id && !it.loading } }
                val added = original.copy(id = "new-device", name = "New device")
                state.deviceState.devices.add(added)
                waitFor { discovery.loads.value.any { it.connectionName == "New device" } }
                state.deviceState.devices.remove(original)
                waitFor { discovery.loads.value.none { it.endpoint.endpoint.sourceId == original.id } }
                state.deviceState.devices[0] = added.copy(name = "Renamed device")
                waitFor { discovery.loads.value.any { it.connectionName == "Renamed device" } }
                val entry = state.networkState.entries.single()
                state.networkState.entries.clear()
                waitFor { discovery.loads.value.none { it.endpoint.endpoint.protocol == FileProtocol.Network } }
                state.networkState.entries.add(entry)
                waitFor { discovery.loads.value.any { it.endpoint.endpoint.protocol == FileProtocol.Network } }
                discovery.close()
                val closed = discovery.loads.value
                state.deviceState.devices.clear()
                Snapshot.sendApplyNotifications()
                delay(50)
                assertEquals(closed, discovery.loads.value)
            } finally { discovery.close(); scope.cancel() }
        }
    }

    @Test fun appendedDeviceSourceKeepsItsAuthorityAfterTheOriginalSourceIsRemoved() = runBlocking {
        fixture {
            val source = Files.writeString(root.resolve("from-device.txt"), "device source")
            val request = request()
            request.state.selected.value.filter { it.directory.endpoint.endpoint.protocol != FileProtocol.Local }
                .forEach { request.state.remove(it.directory) }
            val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Device }.endpoint
            val file = FileUtils.getFile(FileAccessPermission.Allowed, source.toString()).getOrThrow()
                .copy(protocol = FileProtocol.Device, protocolId = "device")
            request.addSources(endpoint, listOf(file))
            request.removeSource(request.state.sources.first())
            assertEquals(endpoint, request.state.sources.single().endpoint)
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            assertEquals("device source", Files.readString(root.resolve("local/from-device.txt")))
            assertFalse(Files.exists(root.resolve("local/input.txt")))
            assertEquals("device source", Files.readString(source))
        }
    }

    @Test fun discoveryListsOneHomePerResourceAndNeverLoadsNativeShareTargets() = runBlocking {
        fixture {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val discovery = CopyDestinationDiscovery("home-resources", scope)
            try {
                val devices = org.koin.core.context.GlobalContext.get().get<DeviceState>()
                val networks = org.koin.core.context.GlobalContext.get().get<NetworkState>()
                discovery.refresh(devices, networks)
                withTimeout(20_000) { while (discovery.loads.value.any { it.loading }) delay(20) }
                val loads = discovery.loads.value
                assertEquals(listOf(FileProtocol.Local, FileProtocol.Device, FileProtocol.Network), loads.map { it.endpoint.endpoint.protocol })
                assertTrue(loads.all { it.error == null }, loads.toString())
                assertTrue(loads.all { it.destinations.size == 1 })
                assertEquals(PathUtils.getHomePath(), loads[0].destinations.single().directory.file.path)
                assertEquals(root.toString(), loads[1].destinations.single().directory.file.path)
                assertEquals("/", loads[2].destinations.single().directory.file.path)
                assertTrue(loads.all { it.destinations.single().name == it.connectionName })
            } finally { discovery.close(); scope.cancel() }
        }
    }

    @Test fun olderDeviceWithoutWritePreflightStillDiscoversAndCopiesThroughTheExistingQueue() = runBlocking {
        fixture(supportsWriteCheck = false) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val discovery = CopyDestinationDiscovery("legacy", scope)
            try {
                discovery.refresh(state.deviceState, state.networkState)
                withTimeout(20_000) { while (discovery.loads.value.any { it.loading }) delay(20) }
                val device = discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Device }.destinations.single()
                assertNull(device.unavailableReason)
                val request = request(includeShare = true)
                val result = request.submit()
                assertTrue(result.fullyAccepted, result.error)
                request.close(); await(result)
                assertEquals("local content", Files.readString(root.resolve("device/input.txt")))
                assertEquals("shared content", Files.readString(root.resolve("device/from-share.txt")))
            } finally { discovery.close(); scope.cancel() }
        }
    }

    @Test fun legacyDeviceStillRejectsActualWriteDenialAndPreservesAnExistingFile() = runBlocking {
        fixture(supportsWriteCheck = false, revokeBeforeWrite = true) {
            val existing = Files.writeString(root.resolve("device/input.txt"), "keep original")
            val request = request()
            val result = submitReplacing(request)
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val deviceTask = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Device }.taskKeys.single()
            assertEquals(StatusEnum.FAILURE, tasks.getTask(deviceTask)?.status)
            assertTrue(writeAttempts.get() > 0, "The write must reach the authoritative device service")
            val failure = tasks.getTask(deviceTask)!!.result.toString()
            assertTrue(listOf(strings.AppStrings.message_task_permission_denied, strings.AppStrings.ui_no_permission_to_write_to_that_path).any { it in failure }, failure)
            assertEquals("keep original", Files.readString(existing))
        }
    }

    @Test fun legacyDeviceCannotDeleteAnExistingDirectoryBeforeReplacingItWithAFile() = runBlocking {
        fixture(supportsWriteCheck = false) {
            val existing = Files.createDirectory(root.resolve("device/input.txt"))
            Files.writeString(existing.resolve("keep.txt"), "keep original")
            val request = request()
            val result = submitReplacing(request)
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val deviceTask = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Device }.taskKeys.single()
            assertEquals(StatusEnum.FAILURE, tasks.getTask(deviceTask)?.status)
            val failure = tasks.getTask(deviceTask)!!.result.toString()
            assertTrue(strings.AppStrings.copy_destination_replace_type_requires_check in failure, failure)
            assertEquals(0, writeAttempts.get())
            assertTrue(Files.isDirectory(existing))
            assertEquals("keep original", Files.readString(existing.resolve("keep.txt")))
        }
    }

    @Test fun localAndReadonlyNativeShareSourcesReachMixedOrdinaryTargetsAfterClosing() = runBlocking {
        fixture {
            val request = request(includeShare = true)
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            assertEquals(3, result.accepted.size)
            assertEquals(6, result.accepted.sumOf { it.taskKeys.size })
            request.close(); await(result)
            for (directory in listOf("local", "device")) {
                assertEquals("local content", Files.readString(root.resolve("$directory/input.txt")))
                assertEquals("shared content", Files.readString(root.resolve("$directory/from-share.txt")))
            }
            assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
            assertEquals("shared content", provider.contents.getValue("/backup/from-share.txt").decodeToString())
            result.accepted.flatMap { it.taskKeys }.forEach { key -> tasks.getTask(key)?.let { assertEquals(StatusEnum.SUCCESS, it.status, it.result.toString()); assertEquals(TaskType.Copy, it.taskType) } }
        }
    }

    @Test fun readonlyShareAndLocalFilesReachLocalDeviceAndRealSftpThroughTheOrdinaryQueue() = runBlocking {
        fixture {
            val remote = Files.createDirectory(root.resolve("sftp-server"))
            Files.createDirectory(remote.resolve("backup"))
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = KeyPairProvider.wrap(keyPair)
                passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture-password" }
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(remote)
            }
            var network: Network? = null
            try {
                server.start()
                val endpoint = Network("Real SFTP", "/", "SFTP", "127.0.0.1:${server.port}", "fixture", "fixture-password",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(keyPair.public)}")))
                network = endpoint
                state.networkState.entries.clear()
                state.networkState.entries.add(NetworkEntry(1, endpoint, true))
                val request = request(includeShare = true)
                val result = request.submit()
                assertTrue(result.fullyAccepted, result.error)
                assertEquals(3, result.accepted.size)
                request.close(); await(result)
                for (directory in listOf(root.resolve("local"), root.resolve("device"), remote.resolve("backup"))) {
                    assertEquals("local content", Files.readString(directory.resolve("input.txt")))
                    assertEquals("shared content", Files.readString(directory.resolve("from-share.txt")))
                }
                result.accepted.flatMap { it.taskKeys }.forEach { key -> tasks.getTask(key)?.let { assertEquals(StatusEnum.SUCCESS, it.status, it.result.toString()); assertEquals(0, it.failureCount()) } }
            } finally { network?.close(); server.stop(true) }
        }
    }

    @Test fun revokingOneDevicesWriteGrantRejectsTheWholeBatchBeforeAnyTaskStarts() = runBlocking {
        fixture {
            val request = request()
            certificate.setDeviceSharePathScope("device-token", DeviceSharePathScope(listOf(DeviceSharePathGrant(root.toString(), true))))
            val result = request.submit()
            assertFalse(result.fullyAccepted)
            assertTrue(result.accepted.isEmpty())
            assertTrue(tasks.tasks.isEmpty())
            assertFalse(Files.exists(root.resolve("local/input.txt")))
            assertTrue(provider.contents.isEmpty())
            request.close()
        }
    }

    @Test fun oneTargetFailureDoesNotRevokeSuccessfulCopiesAndCanRetryIndependently() = runBlocking {
        fixture {
            provider.failUploads = true
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            assertEquals("local content", Files.readString(root.resolve("local/input.txt")))
            assertEquals("local content", Files.readString(root.resolve("device/input.txt")))
            val failure = result.accepted.flatMap { it.taskKeys }.mapNotNull(tasks::getTask).single { it.status == StatusEnum.FAILURE }
            assertTrue(failure.key in result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys)
            provider.failUploads = false
            state.continueTask(failure)
            await(result)
            assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
        }
    }

    @Test fun sourceBytesChangingDuringATransferAreNotReportedAsTheCapturedVersion() = runBlocking {
        fixture {
            val source = root.resolve("input.txt")
            val originalTime = Files.getLastModifiedTime(source)
            provider.beforeRead = {
                Files.writeString(source, "other content")
                Files.setLastModifiedTime(source, originalTime)
            }
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            assertEquals(StatusEnum.FAILURE, tasks.getTask(key)?.status)
            assertEquals("local content", Files.readString(root.resolve("local/input.txt")))
            assertEquals("local content", Files.readString(root.resolve("device/input.txt")))
        }
    }

    @Test fun restartingTheFileRuntimeRecoversTheStoredSourceAndExactConnection() = runBlocking {
        fixture {
            provider.failUploads = true
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            val failure = tasks.getTask(key)!!
            val persisted = tasks.loadCopyValidation(key)!!
            assertFalse(persisted.decodeToString().contains("fixture-password"))
            assertFalse(persisted.decodeToString().contains("device-token"))
            provider.failUploads = false
            val restarted = FileState().also { it.mainScope.cancel() }
            restarted.continueTask(failure)
            await(result)
            assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
            assertEquals("local content", Files.readString(root.resolve("local/input.txt")))
        }
    }

    @Test fun recoveryUsesTheSameRuntimeOriginAndRejectsAnotherRuntime() = runBlocking {
        val original = WebUiAttachment.target
        try {
            WebUiAttachment.target = WebUiRuntimeAddress.parse("https://runtime.example.test")
            fixture {
                provider.failUploads = true
                val request = request()
                val result = request.submit()
                assertTrue(result.fullyAccepted, result.error)
                request.close(); await(result)
                val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
                provider.failUploads = false
                WebUiAttachment.target = WebUiRuntimeAddress.parse("https://other.example.test")
                FileState().also { it.mainScope.cancel() }.continueTask(tasks.getTask(key)!!)
                await(result)
                assertEquals(StatusEnum.FAILURE, tasks.getTask(key)?.status)
                assertTrue(provider.contents.isEmpty())
                WebUiAttachment.target = WebUiRuntimeAddress.parse("https://runtime.example.test")
                FileState().also { it.mainScope.cancel() }.continueTask(tasks.getTask(key)!!)
                await(result)
                assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
            }
        } finally { WebUiAttachment.target = original }
    }

    @Test fun restartRecoveryRejectsChangedCredentialsRatherThanMatchingTheSameConnectionName() = runBlocking {
        fixture {
            provider.failUploads = true
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            val replacement = Provider()
            val old = state.networkState.entries.single().network
            val rebound = object : Network(old.name, "/", old.protocol, old.host, old.username, "another-password") { override val client = replacement; override val taskState = tasks }
            state.networkState.entries.clear()
            state.networkState.entries.add(NetworkEntry(1, rebound, true))
            val restarted = FileState().also { it.mainScope.cancel() }
            restarted.continueTask(tasks.getTask(key)!!)
            await(result)
            assertEquals(StatusEnum.FAILURE, tasks.getTask(key)?.status)
            assertTrue(replacement.contents.isEmpty())
        }
    }

    @Test fun restartRecoveryRejectsDifferentBytesEvenWhenSourceSizeAndTimestampAreUnchanged() = runBlocking {
        fixture {
            provider.failUploads = true
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close(); await(result)
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            val source = root.resolve("input.txt")
            val timestamp = Files.getLastModifiedTime(source)
            Files.writeString(source, "other content")
            Files.setLastModifiedTime(source, timestamp)
            provider.failUploads = false
            val restarted = FileState().also { it.mainScope.cancel() }
            restarted.continueTask(tasks.getTask(key)!!)
            await(result)
            assertEquals(StatusEnum.FAILURE, tasks.getTask(key)?.status)
            assertTrue(provider.contents.isEmpty())
        }
    }

    @Test fun removingOneConnectionRejectsTheWholeBatchWithoutChangingItsTargetIdentity() = runBlocking {
        fixture {
            val request = request()
            val captured = request.state.selected.value.last().directory
            state.networkState.entries.clear()
            val result = request.submit()
            assertFalse(result.fullyAccepted)
            assertTrue(result.accepted.isEmpty())
            assertTrue(tasks.tasks.isEmpty())
            assertEquals(captured, request.state.selected.value.last().directory)
            assertFalse(Files.exists(root.resolve("local/input.txt")))
            request.close()
        }
    }

    @Test fun aPausedBackgroundCopyCanResumeAfterItsWindowCloses() = runBlocking {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            provider.beforeRead = { entered.complete(Unit); release.await() }
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close()
            withTimeout(10_000) { entered.await() }
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            val task = tasks.getTask(key)!!
            tasks.requestPause(task)
            assertEquals(StatusEnum.PAUSE, tasks.getTask(key)?.status)
            tasks.requestResume(task)
            release.complete(Unit)
            await(result)
            assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
        }
    }

    @Test fun cancellingOneBackgroundTargetLeavesOtherCopiesDelivered() = runBlocking {
        fixture {
            val entered = CompletableDeferred<Unit>()
            provider.beforeRead = { entered.complete(Unit); awaitCancellation() }
            val request = request()
            val result = request.submit()
            assertTrue(result.fullyAccepted, result.error)
            request.close()
            withTimeout(10_000) { entered.await() }
            val key = result.accepted.single { it.destination.directory.endpoint.endpoint.protocol == FileProtocol.Network }.taskKeys.single()
            tasks.requestCancel(tasks.getTask(key)!!)
            await(result)
            assertTrue(provider.contents.isEmpty())
            assertEquals("local content", Files.readString(root.resolve("local/input.txt")))
            assertEquals("local content", Files.readString(root.resolve("device/input.txt")))
            provider.beforeRead = {}
            val another = state.createCopyDestinationRequest(listOf(FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow()))
            val endpoint = another.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Network }.endpoint
            another.state.select(another.resolveDirectory(endpoint, FileSimpleInfo.pathFileSimpleInfo("/backup")))
            val retried = another.submit()
            assertTrue(retried.fullyAccepted, retried.error)
            another.close(); await(retried)
            assertEquals("local content", provider.contents.getValue("/backup/input.txt").decodeToString())
        }
    }

    private suspend fun fixture(supportsWriteCheck: Boolean = true, revokeBeforeWrite: Boolean = false, block: suspend Fixture.() -> Unit) {
        val root = Files.createTempDirectory("copy-mixed").toRealPath()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = createMcpHttpTestDatabase(driver)
        DataEncryptionKey.init(createSettings())
        database.grantTestAdministratorAccess()
        val certificate = DeviceCertificateState(database)
        certificate.setTokenPermission("device-token", 1L)
        certificate.setTokenPermission("share-token", 1L)
        val shared = Files.createDirectory(root.resolve("shared"))
        Files.writeString(shared.resolve("from-share.txt"), "shared content")
        certificate.setDeviceSharePathScope("share-token", DeviceSharePathScope(listOf(DeviceSharePathGrant(shared.toString(), true))))
        val service = DeviceFileService(certificate) { "device" }
        val paths = Paths(root)
        val shareClient = Client(service, "share-token")
        val session = object : DeviceBackedShareSession {
            override var isActive = true
            override val devicePathClient: DevicePathClient = paths
            override val deviceFileClient: DeviceFileClient = shareClient
            override suspend fun listRoots() = Result.success(listOf(FileUtils.getFile(FileAccessPermission.Allowed, shared.toString()).getOrThrow()))
            override suspend fun list(path: String) = paths.listPath(ListRequest(path), null, null)
            override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long) = shareClient.readBytes(path, startOffset, endOffset)
            override suspend fun readStream(path: String, startOffset: Long, endOffset: Long, onChunk: suspend (ByteArray) -> Unit) = shareClient.readStream(path, startOffset, endOffset, onChunk)
            override fun disconnect(): Boolean { isActive = false; return true }
        }
        Files.writeString(root.resolve("input.txt"), "local content")
        Files.createDirectory(root.resolve("local")); Files.createDirectory(root.resolve("device"))
        val tasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(root.resolve("cleanup").toString()))
        val provider = Provider()
        val writeAttempts = java.util.concurrent.atomic.AtomicInteger()
        stopKoin()
        val application = startKoin { modules(module {
            single { database }; single { tasks }; single<ShareHistoryStore> { TempShareHistoryStore(root.resolve("share-history").toString()) }; single { NetworkState(database) }; single { DeviceState() }; single { MainState() }; single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try {
            val devices = application.koin.get<DeviceState>()
            devices.socketDevices.add(SocketDevice("device", "Device", "/", type = DeviceType.JVM, connectType = ConnectType.Connect, transportType = DeviceTransportType.WebRtc))
            devices.devices.add(Device("device", "Device", "/", mutableMapOf(), DeviceType.JVM, "device-token", pathClient = paths, fileClient = Client(service, "device-token", supportsWriteCheck) {
                writeAttempts.incrementAndGet()
                if (revokeBeforeWrite) certificate.setDeviceSharePathScope("device-token", DeviceSharePathScope(listOf(DeviceSharePathGrant(root.toString(), true))))
            }))
            devices.shares.add(Share("share", "Read-only share", PathUtils.getPathSeparator(), session, type = DeviceType.JVM))
            val network = object : Network("Network", "/", "FTP", "copy-fixture.invalid", "", "fixture") { override val client = provider; override val taskState = tasks }
            application.koin.get<NetworkState>().entries.add(NetworkEntry(1, network, true))
            Fixture(application.koin.get(), tasks, root, provider, certificate, writeAttempts).block()
        } finally { tasks.stop(); stopKoin(); driver.close(); root.toFile().deleteRecursively() }
    }
}
