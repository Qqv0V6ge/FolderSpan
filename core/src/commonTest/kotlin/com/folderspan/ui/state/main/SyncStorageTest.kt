package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.service.network.*
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.runSuspendTest
import com.folderspan.utils.ProtoBufCodec
import kotlin.test.*

class SyncStorageTest : ChineseLocalizationTest() {
    private val store = object : SyncTaskStore {
        override fun loadTasks() = emptyList<SyncTask>()
        override fun saveTask(task: SyncTask) = task
        override fun deleteTask(taskId: Long) = Unit
        override fun appendRun(record: SyncRunRecord) = Unit
        override fun loadRuns(taskId: Long) = emptyList<SyncRunRecord>()
        override fun deleteRuns(taskId: Long) = Unit
    }

    private fun file(id: String, path: String, directory: Boolean = false) =
        FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
            name = path.substringAfterLast('/'), isDirectory = directory,
            protocol = FileProtocol.Network, protocolId = "network:test",
            storageReference = StorageReference(id, "parent"),
        )

    private fun network(provider: StorageProvider, protocol: String = "GoogleDrive") =
        object : Network("test", "/", protocol, "https://example.test", "", "") {
            override val client = provider
        }

    private fun task(network: Network) = SyncTask(
        id = 1, name = "Sync", sourceType = SyncEndpointType.Network, sourceRef = network.protocolId,
        sourcePath = "/", targetType = SyncEndpointType.Local, targetPath = "/target",
    )

    @Test fun allProtocolsUseActualReadWriteCapabilitiesForSync() = runSuspendTest {
        for (protocol in listOf("FTP", "SFTP", "SMB", "WebDav", "S3", "HttpDirectory", "NFS", "GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) {
            val provider = object : StorageProvider by UnsupportedNetworkClient("test") {
                override val capabilities = if (protocol == "HttpDirectory") StorageCapabilities.ReadOnly else StorageCapabilities.ReadWrite
                override suspend fun effectiveCapabilities() = Result.success(capabilities)
            }
            val endpoint = network(provider, protocol)
            assertTrue(endpoint.supportsSync, protocol)
            assertEquals(true to (protocol != "HttpDirectory"), endpoint.syncReadWrite().getOrThrow(), protocol)
        }
        assertFalse(network(UnsupportedNetworkClient("unavailable")).supportsSync)
    }

    @Test fun scanPreservesSameNamedDirectoriesAndFailsOnAuthorizationError() = runSuspendTest {
        val visited = mutableListOf<String?>()
        var denied = false
        val provider = object : StorageProvider by UnsupportedNetworkClient("test") {
            override val capabilities = StorageCapabilities.ReadWrite
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> {
                visited.add(path.reference?.id)
                if (denied) return Result.failure(IllegalStateException("Authorization expired"))
                return Result.success(when (path.reference?.id) {
                    "root" -> listOf(
                        NetworkFileEntry("same", "/same", true, reference = StorageReference("dir-a")),
                        NetworkFileEntry("same", "/same", true, reference = StorageReference("dir-b")),
                    )
                    "dir-a" -> listOf(NetworkFileEntry("report", "/same/report", false, reference = StorageReference("file-a")))
                    "dir-b" -> listOf(NetworkFileEntry("report", "/same/report", false, reference = StorageReference("file-b")))
                    else -> error("Lost directory identity")
                })
            }
        }
        val endpoint = network(provider)
        val state = SyncState(store)
        val root = file("root", "/", true).copy(protocolId = endpoint.protocolId)
        val result = state.collectSourceSnapshot(task(endpoint), endpoint, root, emptyList()).getOrThrow()
        assertEquals(listOf<String?>("root", "dir-a", "dir-b"), visited)
        assertEquals(listOf<String?>("file-a", "file-b"), result.files.map { it.file.storageReference?.id })
        assertEquals(listOf("same/report", "same/report"), result.files.map { it.relativePath })
        denied = true
        val failed = state.collectSourceSnapshot(task(endpoint), endpoint, root, emptyList())
        assertEquals("Authorization expired", failed.exceptionOrNull()?.message)
    }

    @Test fun selectedRootAndFailureIdentitySurvivePersistenceWithoutCrossAccountReuse() {
        val source = file("file-a", "/same")
        val otherAccount = source.copy(protocolId = "network:other-account")
        val run = SyncRunItem("/target/same", false, "interrupted", source)
        assertEquals(source, ProtoBufCodec.decode<SyncRunItem>(ProtoBufCodec.encode(run)).source)
        val original = task(network(UnsupportedNetworkClient("test"))).copy(
            sourceStorageReference = StorageReference("source-root"),
            targetStorageReference = StorageReference("target-root"),
        )
        val restored = ProtoBufCodec.decode<SyncTask>(ProtoBufCodec.encode(original))
        assertEquals(original, restored)
        assertNotEquals(source.protocolId, otherAccount.protocolId)
    }

    @Test fun endpointLookupRejectsAmbiguousDisplayPathsAndForwardsSelectedId() = runSuspendTest {
        var selectedId: String? = null
        val provider = object : StorageProvider by UnsupportedNetworkClient("test") {
            override val capabilities = StorageCapabilities.ReadWrite
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) = Result.success(listOf(
                NetworkFileEntry("same", "/same", false, reference = StorageReference("first")),
                NetworkFileEntry("same", "/same", false, reference = StorageReference("second")),
            ))
            override suspend fun list(path: String, requestId: String?, batchId: String?) = list(StoragePath(path), requestId, batchId)
            override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> {
                selectedId = path.reference?.id
                return Result.success(NetworkFileEntry("same", "/same", false, reference = path.reference))
            }
        }
        val endpoint = network(provider)
        val operations = SyncEndpointFileOperations()
        assertTrue(operations.readPathInfo(SyncEndpointType.Network, endpoint.protocolId, endpoint, "/same").isFailure)
        val selected = operations.readPathInfo(SyncEndpointType.Network, endpoint.protocolId, endpoint, "/same", file("second", "/same")).getOrThrow()
        assertEquals("second", selectedId)
        assertEquals("second", selected?.storageReference?.id)
    }

    @Test fun selectedIgnoreFilesAreReadByIdentityAndReadFailuresAbortTheScan() = runSuspendTest {
        var denied = false
        val provider = object : StorageProvider by UnsupportedNetworkClient("test"), ChunkReadableNetworkClient {
            override val capabilities = StorageCapabilities.ReadWrite
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) = Result.success(listOf(
                NetworkFileEntry(".gitignore", "/.gitignore", false, size = 6, reference = StorageReference("ignore-id")),
                NetworkFileEntry("cache.tmp", "/cache.tmp", false, reference = StorageReference("skip-id")),
                NetworkFileEntry("report", "/report", false, reference = StorageReference("keep-id")),
            ))
            override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) =
                error("String-only download loses the selected identity")
            override suspend fun downloadByChunks(remotePath: StoragePath, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> {
                assertEquals("ignore-id", remotePath.reference?.id)
                return if (denied) Result.failure(IllegalStateException("Authorization expired"))
                else onChunk("*.tmp\n".encodeToByteArray(), 6).map { true }
            }
        }
        val endpoint = network(provider)
        val state = SyncState(store)
        val config = task(endpoint).copy(useIgnoreFiles = true, ignoreFileNames = listOf(".gitignore"))
        val root = file("root", "/", true).copy(protocolId = endpoint.protocolId)
        val snapshot = state.collectSourceSnapshot(config, endpoint, root, emptyList()).getOrThrow()
        assertEquals(listOf<String?>("keep-id"), snapshot.files.map { it.file.storageReference?.id })
        denied = true
        assertTrue(state.collectSourceSnapshot(config, endpoint, root, emptyList()).isFailure)
    }
    @Test fun logicalEncryptedMetadataIsOnlyUsedForIncrementalComparison() = runSuspendTest {
        val endpoint = network(UnsupportedNetworkClient("test"))
        val physical = file("cipher-id", "/report").copy(size = 700, updatedDate = 90)
        val logical = physical.copy(size = 7, updatedDate = 40)
        val compared = syncComparisonTargetFile(endpoint, physical) { network, observed ->
            assertSame(endpoint, network)
            assertSame(physical, observed)
            Result.success(logical)
        }
        assertSame(logical, compared)
        assertEquals(700L, physical.size)
        assertEquals("cipher-id", physical.storageReference?.id)
        assertSame(physical, syncComparisonTargetFile(endpoint, physical) { _, _ -> Result.success(null) })
        assertFailsWith<IllegalStateException> {
            syncComparisonTargetFile(endpoint, physical) { _, _ -> Result.failure(IllegalStateException("Locked metadata")) }
        }
    }

    @Test fun failedRetryScanKeepsItsOriginalScopeOnFurtherRetries() {
        val failed = file("failed-id", "/same")
        val completed = file("completed-id", "/same")
        val record = SyncRunRecord(1, 1, "retry", 1, 2, SyncRunStatus.Failure, 1, 0, 1, "Authorization expired",
            listOf(SyncRunItem("/", false, "Authorization expired")))
        val scoped = preserveSyncRetryIdentity(record, listOf(failed))
        assertEquals(listOf(failed), scoped.items.mapNotNull { it.source })
        val partial = record.copy(successCount = 1, items = record.items + SyncRunItem("/same", true, "Done", completed))
        assertEquals(listOf(failed), preserveSyncRetryIdentity(partial, listOf(failed, completed)).items.filter { !it.success }.mapNotNull { it.source })
        assertSame(record, preserveSyncRetryIdentity(record, null))
    }

}
