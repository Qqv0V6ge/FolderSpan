package com.folderspan.ui.state.file

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.RenameInfo
import com.folderspan.service.file.ContinuousRangeDeviceFileClient
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRuntimeTaskExecutorContinuousUploadJvmTest {
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
