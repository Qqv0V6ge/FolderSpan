package com.folderspan.ui.state.main

import com.folderspan.service.network.*
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class TaskStorageUploadRecoveryTest {
    @Test
    fun restartQueriesConfirmedOffsetAndKeepsCompletedBytes() = runSuspendTest {
        val provider = Provider()
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        val bytes = ByteArray(8) { it.toByte() }
        provider.failChunkAt = 4
        assertTrue(copy(provider, bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null }).isFailure)
        assertEquals(4L, checkpoint?.uploadSession?.confirmedOffset)
        val reopened = Provider(provider.store, provider).also { it.failChunkAt = null }
        assertTrue(copy(reopened, bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null }).getOrThrow())
        assertEquals(listOf(0L, 4L), provider.successfulOffsets)
        assertEquals(1, provider.starts)
        assertEquals(1, reopened.queries)
        assertNull(checkpoint)
    }

    @Test
    fun sameLengthSourceMutationAndAnotherRuntimeAreRejectedBeforeWrite() = runSuspendTest {
        val provider = Provider().also { it.failChunkAt = 4 }
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        val bytes = ByteArray(8) { it.toByte() }
        copy(provider, bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null })
        val writes = provider.successfulOffsets.size
        val mutated = bytes.copyOf().also { it[0] = 99 }
        assertTrue(copy(provider, mutated, { checkpoint }, { checkpoint = it }, { checkpoint = null }).isFailure)
        assertEquals(writes, provider.successfulOffsets.size)
        assertTrue(copy(Provider(), bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null }).isFailure)
        assertNotNull(checkpoint)
    }

    @Test
    fun finalResponseLossIsVerifiedWithoutRepeatingSubmission() = runSuspendTest {
        val provider = Provider().also { it.loseFinalResponse = true }
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        val bytes = ByteArray(8) { it.toByte() }
        assertIs<StorageUploadUncertainException>(
            copy(provider, bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null }).exceptionOrNull(),
        )
        assertNotNull(checkpoint)
        assertTrue(copy(provider, bytes, { checkpoint }, { checkpoint = it }, { checkpoint = null }).getOrThrow())
        assertEquals(1, provider.finishes)
        assertEquals(listOf(0L, 4L), provider.successfulOffsets)
        assertNull(checkpoint)
    }

    @Test
    fun cancellationStopsChunkWriterBeforeCancellingAndRemovingSession() = runSuspendTest {
        val provider = Provider().also { it.cancelChunkAt = 4 }
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        assertFailsWith<CancellationException> {
            copy(provider, ByteArray(8), { checkpoint }, { checkpoint = it }, { checkpoint = null }, cancelled = true)
        }
        assertTrue(provider.cancelled)
        assertFalse(provider.writing)
        assertNull(checkpoint)
    }

    @Test
    fun runtimeUncertainChunkStateIsPersistedBeforeFailure() = runSuspendTest {
        val provider = Provider().also { it.uncertainChunk = true }
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        assertIs<StorageUploadUncertainException>(copy(provider, ByteArray(8),
            { checkpoint }, { checkpoint = it }, { checkpoint = null }).exceptionOrNull())
        assertNotNull(checkpoint)
        assertTrue(provider.store.hasUncertainTask(7))
        assertTrue(provider.successfulOffsets.isEmpty())
    }

    @Test
    fun emptyFileUsesNoSourceReadOrChunkWrite() = runSuspendTest {
        val provider = Provider()
        var checkpoint: TaskRuntimeTransferCheckpoint? = null
        assertTrue(copy(provider, byteArrayOf(), { checkpoint }, { checkpoint = it }, { checkpoint = null }).getOrThrow())
        assertTrue(provider.successfulOffsets.isEmpty())
        assertEquals(1, provider.finishes)
    }

    private suspend fun copy(
        provider: Provider,
        bytes: ByteArray,
        load: () -> TaskRuntimeTransferCheckpoint?,
        save: (TaskRuntimeTransferCheckpoint) -> Unit,
        delete: () -> Unit,
        cancelled: Boolean = false,
    ) = recoverStorageUpload(
        taskKey = 7, entryId = "entry", provider = provider,
        source = TaskRuntimeEndpointRef(path = "/source"),
        target = TaskRuntimeEndpointRef(path = "/target"), fileSize = bytes.size.toLong(),
        connectionId = "account-a", constraints = StorageUploadConstraints(maxChunkSize = 4),
        ensureRunning = {}, readRange = { start, end -> Result.success(bytes.copyOfRange(start.toInt(), end.toInt())) },
        loadCheckpoint = load, saveCheckpoint = save, deleteCheckpoint = delete, cancelledByUser = { cancelled },
    )

    private class Provider(
        val store: StorageUploadSessionStore = StorageUploadSessionStore(createInMemorySettings()),
        private val prior: Provider? = null,
    ) : StorageProvider {
        var failChunkAt: Long? = null
        var cancelChunkAt: Long? = null
        var uncertainChunk = false
        var loseFinalResponse = false
        var writing = false
        var cancelled = false
        var starts = 0
        var queries = 0
        var finishes = 0
        var serverCompleted = false
        val successfulOffsets: MutableList<Long> = prior?.successfulOffsets ?: mutableListOf()
        private var offset: Long = prior?.offset ?: 0L

        override suspend fun uploadSessionOwnerId() = Result.success(store.ownerRuntimeId)
        override suspend fun saveUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference, session: StorageUploadSession) =
            runCatching { store.save(taskKey, reference, session) }
        override suspend fun loadUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference) =
            runCatching { store.load(taskKey, reference) }
        override suspend fun deleteUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference) =
            runCatching { store.remove(taskKey, reference) }
        override suspend fun startUpload(target: StoragePath, size: Long): Result<StorageUploadSession> {
            starts++
            return Result.success(StorageUploadSession(
                backend = "fixture", target = target, size = size, confirmedOffset = 0,
                secret = "session-secret", binding = "account-a",
            ))
        }
        override suspend fun queryUpload(session: StorageUploadSession): Result<StorageUploadSession> {
            queries++
            return Result.success(session.copy(
                confirmedOffset = offset,
                status = if (serverCompleted) StorageUploadStatus.COMPLETED else StorageUploadStatus.UPLOADING,
            ))
        }
        override suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> {
            writing = true
            try {
                if (cancelChunkAt == offset) throw CancellationException("cancel")
                if (failChunkAt == offset) return Result.failure(IllegalStateException("network failure"))
                if (uncertainChunk) return Result.success(session.copy(status = StorageUploadStatus.UNCERTAIN))
                check(offset == this.offset)
                successfulOffsets += offset
                this.offset += bytes.size
                return Result.success(session.copy(confirmedOffset = this.offset))
            } finally { writing = false }
        }
        override suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> {
            finishes++
            serverCompleted = true
            return if (loseFinalResponse) Result.failure(StorageUploadUncertainException(session.copy(status = StorageUploadStatus.UNCERTAIN)))
            else Result.success(session.copy(status = StorageUploadStatus.COMPLETED))
        }
        override suspend fun cancelUpload(session: StorageUploadSession): Result<Unit> {
            check(!writing)
            cancelled = true
            return Result.success(Unit)
        }
        override suspend fun list(path: String, requestId: String?, batchId: String?) = Result.success(emptyList<NetworkFileEntry>())
        override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit) = Result.success(true)
        override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) = Result.success(true)
        override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = Result.success(true)
        override suspend fun rename(path: String, newPath: String) = Result.success(true)
        override suspend fun delete(path: String, isDirectory: Boolean) = Result.success(true)
        override suspend fun createFolder(path: String) = Result.success(true)
        override suspend fun createFile(path: String) = Result.success(true)
    }
}
