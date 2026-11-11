package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.extensions.fileOperationKey
import com.folderspan.service.network.*
import com.folderspan.service.operation.TraversalParallelismConfig
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.main.*
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.*

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LegacyStorageFile(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(3) val isDirectory: Boolean,
    @ProtoNumber(4) val isHidden: Boolean,
    @ProtoNumber(5) val path: String,
    @ProtoNumber(6) val mineType: String,
    @ProtoNumber(7) val size: Long,
    @ProtoNumber(8) val createdDate: Long,
    @ProtoNumber(9) val updatedDate: Long,
)

class StorageReferenceFlowTest {
    private fun file(id: String, path: String = "/same", directory: Boolean = false) =
        FileSimpleInfo(
            name = path.substringAfterLast('/'), path = path, isDirectory = directory, isHidden = false,
            mineType = "", size = 0L, createdDate = 0L, updatedDate = 0L,
            protocol = FileProtocol.Network, protocolId = "network:test", isSymbolicLinkKnown = true,
            storageReference = StorageReference(id = id, parentId = "parent"),
        )

    @Test fun oldProtobufAndNewReferencesRoundTripWithoutChangingLegacyTags() {
        val old = LegacyStorageFile("report.txt", false, false, "/report.txt", ".txt", 12, 0, 0)
        val decoded = ProtoBufCodec.decode<FileSimpleInfo>(ProtoBufCodec.encode(old))
        assertEquals(old.path, decoded.path)
        assertNull(decoded.storageReference)
        val current = file("opaque-id")
        val wire = ProtoBufCodec.encode(current)
        assertEquals(current.storageReference, ProtoBufCodec.decode<FileSimpleInfo>(wire).storageReference)
        assertEquals(current.path, ProtoBufCodec.decode<LegacyStorageFile>(wire).path)
    }

    @Test fun sameNamedItemsKeepDistinctSelectionsAndPersistedTaskIdentities() {
        val first = file("first")
        val second = file("second")
        assertNotEquals(first.fileOperationKey(), second.fileOperationKey())
        val target = first.withCopy(protocol = FileProtocol.Local, protocolId = "", path = "/tmp/same")
        assertNull(target.storageReference)
        val one = buildTaskRuntimeCopyQueueEntry(0, first, target)
        val two = buildTaskRuntimeCopyQueueEntry(1, second, target)
        assertNotEquals(one.entryId, two.entryId)
        val restored = ProtoBufCodec.decode<TaskRuntimeQueueEntry>(ProtoBufCodec.encode(one))
        assertEquals("first", restored.toSourceFileSimpleInfo().storageReference?.id)
        val retry = buildCopyRetryEntry(TaskType.Copy, first, target)
        assertEquals(first.storageReference, ProtoBufCodec.decode<TaskRetryEntry>(ProtoBufCodec.encode(retry)).srcStorageReference)
    }

    @Test fun newDescendantsAndSiblingOutputsKeepTheCorrectParentAnchor() {
        val directory = file("selected-folder", "/duplicate", true)
        val child = directory.withCopy(path = "/duplicate/new/sub/file", isDirectory = false)
        assertEquals(StorageReference(ancestorId = "selected-folder", ancestorPath = "/duplicate"), child.storageReference)
        val sibling = file("existing", "/parent/original").withCopy(path = "/parent/stage")
        assertEquals(StorageReference(ancestorId = "parent", ancestorPath = "/parent"), sibling.storageReference)
        assertNull(directory.withCopy(path = "/outside/file", isDirectory = false).storageReference)
    }

    @Test fun traversalDoesNotMergeDifferentDirectoriesWithTheSameDisplayPath() = runSuspendTest {
        val root = file("root", "/", true)
        val collected = collectDirectoryEntriesAdaptive(root, TraversalParallelismConfig(1, 1, 4), "/") { directory ->
            Result.success(when (directory.storageReference?.id) {
                "root" -> listOf(file("dir-a", "/same", true), file("dir-b", "/same", true))
                "dir-a" -> listOf(file("file-a", "/same/report"))
                "dir-b" -> listOf(file("file-b", "/same/report"))
                else -> error("Unexpected directory")
            })
        }
        assertEquals(setOf("dir-a", "dir-b", "file-a", "file-b"), collected.map { it.storageReference?.id }.toSet())
    }

    @Test fun networkForwardsSelectedIdsForMetadataAndZeroLengthDownloads() = runSuspendTest {
        var recorded: StoragePath? = null
        val provider = object : StorageProvider by UnsupportedNetworkClient("test") {
            override val capabilities = StorageCapabilities.ReadWrite
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> {
                recorded = path
                return Result.success(listOf(
                    NetworkFileEntry("same", "/same", false, reference = StorageReference("one")),
                    NetworkFileEntry("same", "/same", false, reference = StorageReference("two")),
                ))
            }
            override suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
                recorded = remotePath
                return Result.success(true)
            }
        }
        val network = object : Network("test", "/", "S3", "https://example.test", "", "") {
            override val client = provider
        }
        val directory = file("folder", "/folder", true).withCopy(protocolId = network.protocolId, storageReference = StorageReference("folder"))
        val entries = network.getList(directory).getOrThrow()
        assertEquals("folder", recorded?.reference?.id)
        assertEquals(listOf("one", "two"), entries.map { it.storageReference?.id })
        assertTrue(network.downloadFileToLocal(entries[1], "/unused") { _, _ -> }.getOrThrow())
        assertEquals("two", recorded?.reference?.id)
    }

    @Test fun syncFailureRecordsRetainExactSourceIdentityForRetry() {
        val first = file("first")
        val second = file("second")
        val restored = ProtoBufCodec.decode<SyncRunRecord>(ProtoBufCodec.encode(SyncRunRecord(
            runId = 1, taskId = 2, trigger = "manual", startedAt = 1, endedAt = 2,
            status = SyncRunStatus.PartialSuccess, totalCount = 2, successCount = 1, failureCount = 1,
            items = listOf(SyncRunItem("/target/same", true, source = first), SyncRunItem("/target/same", false, source = second)),
        )))
        assertEquals("second", restored.items.single { !it.success }.source?.storageReference?.id)
        assertNotEquals(restored.items[0].source?.fileOperationKey(), restored.items[1].source?.fileOperationKey())
    }
}
