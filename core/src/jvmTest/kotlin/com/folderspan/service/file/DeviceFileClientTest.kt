package com.folderspan.service.file

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.RenameInfo
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceFileClientTest {
    @Test
    fun defaultRangeStreamAdvancesWriteOffsetAndRequiresExactLength() = runSuspendTest {
        val client = RecordingRangeWriteClient()

        val result = client.writeRangeStream(
            path = "/remote/target.bin",
            fileSize = 20L,
            startOffset = 5L,
            endOffset = 10L,
            chunks = flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5)),
        )

        assertTrue(result.getOrThrow())
        assertEquals(listOf(5L, 7L), client.writes.map { (offset, _) -> offset })
        assertContentEquals(byteArrayOf(1, 2), client.writes[0].second)
        assertContentEquals(byteArrayOf(3, 4, 5), client.writes[1].second)
    }

    @Test
    fun downloadRangeToFileCoalescesFramesIntoBoundedPersistentWrites() = runSuspendTest {
        val target = Files.createTempFile("folderspan-session-download", ".bin")
        try {
            val frameSize = 64 * 1024
            val chunks = List(5) { frameIndex ->
                ByteArray(frameSize) { (frameIndex + 1).toByte() }
            }
            val expected = ByteArray(chunks.sumOf { it.size })
            chunks.forEachIndexed { index, chunk ->
                chunk.copyInto(expected, destinationOffset = index * frameSize)
            }
            val committedPrefixes = mutableListOf<ByteArray>()
            val client = StreamingDownloadClient(chunks)
            val writes = mutableListOf<Pair<Long, Int>>()

            val result = client.downloadRangeToFile(
                remotePath = "/remote/source.bin",
                startOffset = 0L,
                endOffset = expected.size.toLong(),
                localPath = target.toString(),
                fileSize = expected.size.toLong(),
            ) { offset, bytesWritten ->
                writes += offset to bytesWritten
                committedPrefixes += Files.readAllBytes(target).copyOf((offset + bytesWritten).toInt())
            }

            assertTrue(result.getOrThrow())
            assertContentEquals(expected, Files.readAllBytes(target))
            assertEquals(listOf(0L to 256 * 1024, 256L * 1024L to frameSize), writes)
            assertContentEquals(expected.copyOf(256 * 1024), committedPrefixes[0])
            assertContentEquals(expected, committedPrefixes[1])
        } finally {
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun failedDestinationStopsTheBufferedSource() = runSuspendTest {
        val target = Files.createTempFile("folderspan-session-download-failure", ".bin")
        try {
            val block = ByteArray(64 * 1024)
            val chunks = List(64) { block }
            val client = StreamingDownloadClient(chunks)
            val size = chunks.size * block.size.toLong()
            val result = client.downloadRangeToFile(
                "/remote/source.bin", 0, size, target.toString(), size,
            ) { _, _ -> error("destination failed") }

            assertTrue(result.isFailure)
            assertTrue(client.sentChunks < chunks.size)
        } finally {
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun downloadRangeToFileRejectsStreamThatEndsBeforeRequestedRange() = runSuspendTest {
        val target = Files.createTempFile("folderspan-session-download-short", ".bin")
        try {
            val result = StreamingDownloadClient(listOf(byteArrayOf(1, 2))).downloadRangeToFile(
                remotePath = "/remote/source.bin",
                startOffset = 0L,
                endOffset = 4L,
                localPath = target.toString(),
                fileSize = 4L,
            ) { _, _ -> }

            assertTrue(result.isFailure)
        } finally {
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun downloadRangeToFileRejectsStreamThatExceedsRequestedRange() = runSuspendTest {
        val target = Files.createTempFile("folderspan-session-download-long", ".bin")
        try {
            val result = StreamingDownloadClient(
                listOf(byteArrayOf(1, 2), byteArrayOf(3)),
            ).downloadRangeToFile(
                remotePath = "/remote/source.bin",
                startOffset = 0L,
                endOffset = 2L,
                localPath = target.toString(),
                fileSize = 2L,
            ) { _, _ -> }

            assertTrue(result.isFailure)
        } finally {
            Files.deleteIfExists(target)
        }
    }
}

private class RecordingRangeWriteClient : DeviceFileClient {
    val writes = mutableListOf<Pair<Long, ByteArray>>()

    override suspend fun writeBytes(
        fileSize: Long,
        blockIndex: Long,
        blockLength: Long,
        path: String,
        byteArray: ByteArray,
        startOffset: Long,
    ): Result<Boolean> {
        writes += startOffset to byteArray.copyOf()
        return Result.success(true)
    }

    override suspend fun readBytes(path: String, startOffset: Long, endOffset: Long): Result<ByteArray> = unused()

    override suspend fun renames(renameInfos: List<RenameInfo>): Result<List<Result<Boolean>>> = unused()

    override suspend fun createFolders(paths: List<String>): Result<List<Result<Boolean>>> = unused()

    override suspend fun createFiles(paths: List<String>): Result<List<Result<Boolean>>> = unused()

    override suspend fun deletes(paths: List<String>): Result<List<Result<Boolean>>> = unused()

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

private class StreamingDownloadClient(
    private val chunks: List<ByteArray>,
) : DeviceFileClient {
    var sentChunks = 0
        private set

    override suspend fun readStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> = runCatching {
        chunks.forEach { chunk ->
            onChunk(chunk)
            sentChunks++
        }
        true
    }

    override suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray> = Result.failure(AssertionError("streaming download must not assemble the range"))

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
