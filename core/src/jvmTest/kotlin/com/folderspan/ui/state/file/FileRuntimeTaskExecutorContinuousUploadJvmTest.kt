package com.folderspan.ui.state.file

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.file.ContinuousRangeDeviceFileClient
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.session.DeviceSessionHostTransfer
import com.folderspan.service.session.DeviceSessionHostTransferKind
import com.folderspan.service.session.DeviceSessionStreamOpen
import com.folderspan.service.session.HostDeviceFileClient
import com.folderspan.test.runSuspendTest
import com.folderspan.utils.PrivateFileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FileRuntimeTaskExecutorContinuousUploadJvmTest {
    @Test
    fun hostUploadUsesRuntimeOnlySourceAndCommitsAfterRemoteConfirmation() = runSuspendTest {
        val committed = mutableListOf<Pair<Long, Int>>()
        val client = RecordingHostUploadClient { open, _, onProgress, isPaused ->
            assertTrue(isPaused())
            assertEquals(if (open.startOffset == 2L) 0 else 1, committed.size)
            onProgress(mapOf("host-source" to open.endOffset - open.startOffset))
            assertEquals(if (open.startOffset == 2L) 0 else 1, committed.size)
            Result.success(FolderSpanArchiveTransferResult(committedFileBytes = open.endOffset - open.startOffset))
        }
        val result = copyLocalRangeToContinuousDevice(
            client, "/runtime-only/source.bin", "/remote/target.bin", 13, 2, 13,
            segmentSize = 6,
            isPaused = { true },
            onBytesCommitted = { offset, count -> committed += offset to count },
        )
        assertTrue(result.getOrThrow())
        assertEquals(listOf(2L to 8L, 8L to 13L), client.opens.map { it.startOffset to it.endOffset })
        assertEquals(listOf(2L to 6, 8L to 5), committed)
        assertTrue(client.transfers.all { it.kind == DeviceSessionHostTransferKind.SendFile && it.path == "/runtime-only/source.bin" && it.fileSize == 13L })
        assertTrue(client.fallback.ranges.isEmpty())
    }

    @Test
    fun failedHostSegmentDoesNotAdvanceCheckpointOrRetryThroughBrowser() = runSuspendTest {
        val committed = mutableListOf<Pair<Long, Int>>()
        val client = RecordingHostUploadClient { open, _, onProgress, _ ->
            onProgress(mapOf("host-source" to open.endOffset - open.startOffset))
            if (open.startOffset == 0L) Result.success(FolderSpanArchiveTransferResult(committedFileBytes = 4))
            else Result.failure(IllegalStateException("remote confirmation failed"))
        }
        val result = copyLocalRangeToContinuousDevice(
            client, "/runtime-only/source.bin", "/remote/target.bin", 12, 0, 12,
            segmentSize = 4,
            onBytesCommitted = { offset, count -> committed += offset to count },
        )
        assertTrue(result.isFailure)
        assertEquals(listOf(0L to 4), committed)
        assertEquals(2, client.opens.size)
        assertTrue(client.fallback.ranges.isEmpty())
    }

    @Test
    fun unavailableHostTransferKeepsNativeContinuousUpload() = runSuspendTest {
        val source = Files.createTempFile("folderspan-session-upload", ".bin")
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            Files.write(source, bytes)
            val client = RecordingHostUploadClient(RecordingContinuousUploadClient(bytes.size), false)
            assertTrue(copyLocalRangeToContinuousDevice(
                client, source.toString(), "/remote/target.bin", 4, 0, 4,
                onBytesCommitted = { _, _ -> },
            ).getOrThrow())
            assertTrue(client.opens.isEmpty())
            assertContentEquals(bytes, client.fallback.target)
        } finally { Files.deleteIfExists(source) }
    }

    @Test
    fun cancellationAndInvalidRangeDoNotCommitHostBytes() = runSuspendTest {
        val client = RecordingHostUploadClient { _, _, _, _ -> throw CancellationException("cancelled") }
        var commits = 0
        val result = copyLocalRangeToContinuousDevice(
            client, "/runtime-only/source.bin", "/remote/target.bin", 4, 0, 4,
            onBytesCommitted = { _, _ -> commits++ },
        )
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, commits)
        assertFalse(copyLocalRangeToContinuousDevice(
            client, "/runtime-only/source.bin", "/remote/target.bin", 4, 0, 5,
            onBytesCommitted = { _, _ -> commits++ },
        ).isSuccess)
        assertEquals(1, client.opens.size)
    }

    @Test
    fun applicationOwnedSourceStaysOnClientStream() = runSuspendTest {
        val directory = Files.createDirectories(Path.of(PrivateFileStorage.getAppPath(), "build"))
        val source = Files.createTempFile(directory, "folderspan-private-upload", ".bin")
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            Files.write(source, bytes)
            val client = RecordingHostUploadClient(RecordingContinuousUploadClient(bytes.size))
            assertTrue(copyLocalRangeToContinuousDevice(
                client, source.toString(), "/remote/target.bin", 4, 0, 4,
                onBytesCommitted = { _, _ -> },
            ).getOrThrow())
            assertTrue(client.opens.isEmpty())
            assertContentEquals(bytes, client.fallback.target)
        } finally { Files.deleteIfExists(source) }
    }

    @Test
    fun shortHostResultDoesNotCommitOrRetryThroughClientStream() = runSuspendTest {
        val client = RecordingHostUploadClient { _, _, _, _ -> Result.success(FolderSpanArchiveTransferResult(committedFileBytes = 3)) }
        var commits = 0
        assertTrue(copyLocalRangeToContinuousDevice(
            client, "/runtime-only/source.bin", "/remote/target.bin", 4, 0, 4,
            onBytesCommitted = { _, _ -> commits++ },
        ).isFailure)
        assertEquals(0, commits)
        assertTrue(client.fallback.ranges.isEmpty())
    }

    @Test
    fun continuousDeviceUploadStreamsSegmentsAndCommitsOnlyConfirmedRanges() = runSuspendTest {
        val source = Files.createTempFile("folderspan-session-upload", ".bin")
        try {
            val bytes = ByteArray(13) { index -> (index + 1).toByte() }
            Files.write(source, bytes)
            val client = RecordingContinuousUploadClient(bytes.size)
            val committed = mutableListOf<Pair<Long, Int>>()

            val result = copyLocalRangeToContinuousDevice(
                targetClient = client,
                localPath = source.toString(),
                remotePath = "/remote/target.bin",
                fileSize = bytes.size.toLong(),
                startOffset = 2L,
                endOffset = 13L,
                segmentSize = 4,
                onBytesCommitted = { offset, count -> committed += offset to count },
            )

            assertTrue(result.getOrThrow())
            assertEquals(listOf(2L to 6L, 6L to 10L, 10L to 13L), client.ranges)
            assertEquals(listOf(2L to 4, 6L to 4, 10L to 3), committed)
            assertContentEquals(bytes.copyOfRange(2, 13), client.target.copyOfRange(2, 13))
        } finally {
            Files.deleteIfExists(source)
        }
    }
}

private class RecordingHostUploadClient(
    val fallback: RecordingContinuousUploadClient = RecordingContinuousUploadClient(0),
    override val hostTransfersAvailable: Boolean = true,
    private val transfer: suspend (DeviceSessionStreamOpen, DeviceSessionHostTransfer, suspend (Map<String, Long>) -> Unit, suspend () -> Boolean) -> Result<FolderSpanArchiveTransferResult> = { _, _, _, _ -> error("unexpected host transfer") },
) : ContinuousRangeDeviceFileClient by fallback, HostDeviceFileClient {
    val opens = mutableListOf<DeviceSessionStreamOpen>()
    val transfers = mutableListOf<DeviceSessionHostTransfer>()
    override suspend fun hostFileTransfer(open: DeviceSessionStreamOpen, transfer: DeviceSessionHostTransfer, onProgress: suspend (Map<String, Long>) -> Unit, isPaused: suspend () -> Boolean): Result<FolderSpanArchiveTransferResult> {
        opens += open
        transfers += transfer
        return this.transfer(open, transfer, onProgress, isPaused)
    }
    override suspend fun hostRelay(target: HostDeviceFileClient, sourceOpen: DeviceSessionStreamOpen, targetOpen: DeviceSessionStreamOpen, transfer: DeviceSessionHostTransfer, onProgress: suspend (Map<String, Long>) -> Unit, isPaused: suspend () -> Boolean): Result<FolderSpanArchiveTransferResult> = error("unexpected relay")
}

private class RecordingContinuousUploadClient(
    targetSize: Int,
) : ContinuousRangeDeviceFileClient {
    val target = ByteArray(targetSize)
    val ranges = mutableListOf<Pair<Long, Long>>()

    override suspend fun writeRangeStream(
        path: String,
        fileSize: Long,
        startOffset: Long,
        endOffset: Long,
        chunks: Flow<ByteArray>,
    ): Result<Boolean> = runCatching {
        ranges += startOffset to endOffset
        var offset = startOffset.toInt()
        chunks.collect { chunk ->
            chunk.copyInto(target, destinationOffset = offset)
            offset += chunk.size
        }
        require(offset.toLong() == endOffset)
        true
    }

    override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long): Result<ByteArray> = unused()

    override suspend fun renames(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>> = unused()

    override suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>> = unused()

    override suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> = unused()

    override suspend fun deletes(paths: List<String>): Result<List<Result<Boolean>>> = unused()

    override suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ): Result<Boolean> = unused()

    override suspend fun getFileByPath(path: String): Result<FileSimpleInfo> = unused()

    override suspend fun getFileByPathAndName(path: String, name: String): Result<FileSimpleInfo> = unused()

    override suspend fun getFileInfoByPath(path: String): Result<FileInfo> = unused()

    override suspend fun getFileInfoByPathAndName(path: String, name: String): Result<FileInfo> = unused()

    override suspend fun readFileLines(path: String): Result<List<String>> = unused()

    override suspend fun appendToFile(path: String, content: String): Result<Boolean> = unused()

    override suspend fun copyPath(
        srcPath: String,
        destPath: String,
        onProgress: (suspend (CopyPathProgress) -> Unit)?,
        requestId: String?,
    ): Result<Boolean> = unused()

    override suspend fun controlCopy(
        requestId: String,
        action: CopyPathControlAction,
    ): Result<Boolean> = unused()

    private fun <T> unused(): Result<T> = Result.failure(UnsupportedOperationException("unused in test"))
}
