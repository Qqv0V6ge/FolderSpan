package com.folderspan.utils

import com.folderspan.exception.AuthorityException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import strings.AppStrings

class FileUtilsJvmTest {
    @Test
    fun writeBytesCreatesFileAndWritesAtZeroOffset() = runBlocking {
        withTempDir("fileutils-jvm-create-") { tempDir ->
            val target = File(tempDir, "created.bin")

            val result = FileUtils.writeBytes(FileAccessPermission.Allowed, target.absolutePath, 3L, byteArrayOf(1, 2, 3), 0L)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(result.getOrThrow())
            assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
        }
    }

    @Test
    fun writeBytesCombinesMultipleChunks() = runBlocking {
        withTempDir("fileutils-jvm-chunks-") { tempDir ->
            val target = File(tempDir, "chunks.txt")
            val path = target.absolutePath

            FileUtils.writeBytes(FileAccessPermission.Allowed, path, 6L, "Hel".encodeToByteArray(), 0L).getOrThrow()
            FileUtils.writeBytes(FileAccessPermission.Allowed, path, 6L, "lo".encodeToByteArray(), 3L).getOrThrow()
            FileUtils.writeBytes(FileAccessPermission.Allowed, path, 6L, "!".encodeToByteArray(), 5L).getOrThrow()

            assertEquals("Hello!", target.readText())
        }
    }

    @Test
    fun writeBytesSupportsNonZeroOffset() = runBlocking {
        withTempDir("fileutils-jvm-offset-") { tempDir ->
            val target = File(tempDir, "offset.bin")

            val result = FileUtils.writeBytes(FileAccessPermission.Allowed, target.absolutePath, 6L, byteArrayOf(9, 8), 4L)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertContentEquals(byteArrayOf(0, 0, 0, 0, 9, 8), target.readBytes())
        }
    }

    @Test
    fun writeBytesTruncatesTargetWhenItIsLargerThanDeclaredSize() = runBlocking {
        withTempDir("fileutils-jvm-truncate-") { tempDir ->
            val target = File(tempDir, "truncate.bin")
            target.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

            val result = FileUtils.writeBytes(FileAccessPermission.Allowed, target.absolutePath, 3L, byteArrayOf(9), 1L)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertContentEquals(byteArrayOf(1, 9, 3), target.readBytes())
        }
    }

    @Test
    fun writeBytesWithZeroFileSizeTruncatesExistingFile() = runBlocking {
        withTempDir("fileutils-jvm-zero-") { tempDir ->
            val target = File(tempDir, "zero.bin")
            target.writeBytes(byteArrayOf(7, 7))

            val result = FileUtils.writeBytes(FileAccessPermission.Allowed, target.absolutePath, 0L, byteArrayOf(), 0L)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertContentEquals(byteArrayOf(), target.readBytes())
        }
    }

    @Test
    fun writeBytesDoesNotPreallocateWholeTargetBeforeDataArrives() = runBlocking {
        withTempDir("fileutils-jvm-") { tempDir ->
            val target = File(tempDir, "large.bin")
            val path = target.absolutePath
            val fileSize = 128L * 1024 * 1024 + 1_024L
            val head = byteArrayOf(1, 2, 3)
            val tail = byteArrayOf(4, 5, 6)
            val tailOffset = fileSize - tail.size

            val firstWrite = FileUtils.writeBytes(FileAccessPermission.Allowed, path, fileSize, head, 0L)
            assertTrue(firstWrite.isSuccess, firstWrite.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(firstWrite.getOrThrow())
            assertEquals(head.size.toLong(), target.length(), AppStrings.ui_test_file_utils_jvm_after_writing_the_first_block_the_final_file_size)

            val secondWrite = FileUtils.writeBytes(FileAccessPermission.Allowed, path, fileSize, tail, tailOffset)
            assertTrue(secondWrite.isSuccess, secondWrite.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(secondWrite.getOrThrow())

            assertEquals(fileSize, target.length())
            assertContentEquals(head, FileUtils.readFileRange(FileAccessPermission.Allowed, path, 0L, head.size.toLong()).getOrThrow())
            assertContentEquals(tail, FileUtils.readFileRange(FileAccessPermission.Allowed, path, tailOffset, fileSize).getOrThrow())
        }
    }

    @Test
    fun writeByteStreamWritesRangeWithReusableBuffer() = runBlocking {
        withTempDir("fileutils-jvm-stream-") { tempDir ->
            val target = File(tempDir, "stream.bin")
            val source = "streamed-range-data".encodeToByteArray()
            var readOffset = 0
            var writes = 0

            val result = FileUtils.writeByteStream(FileAccessPermission.Allowed,
                path = target.absolutePath,
                fileSize = 32L,
                startOffset = 8L,
                expectedBytes = source.size.toLong(),
                bufferSize = 5,
                readNext = { buffer, length ->
                    val count = minOf(length, source.size - readOffset)
                    if (count <= 0) {
                        -1
                    } else {
                        source.copyInto(buffer, destinationOffset = 0, startIndex = readOffset, endIndex = readOffset + count)
                        readOffset += count
                        count
                    }
                },
                onBytesWritten = { _, _ ->
                    writes += 1
                },
            )

            assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
            assertTrue(result.getOrThrow())
            assertEquals(32L, target.length())
            assertContentEquals(source, FileUtils.readFileRange(FileAccessPermission.Allowed, target.absolutePath, 8L, 8L + source.size).getOrThrow())
            assertTrue(writes > 1, "bufferSize should force multiple direct writes")
        }
    }

    @Test
    fun readFileRejectsLargeFilesInsteadOfLoadingWholeFileIntoMemory() {
        withTempDir("fileutils-jvm-large-read-") { tempDir ->
            val target = File(tempDir, "huge.bin")
            val path = target.absolutePath.toPath()
            FileSystem.SYSTEM.sink(path).buffer().use { }
            FileSystem.SYSTEM.openReadWrite(path).use { handle ->
                handle.resize(64L * 1024 * 1024 + 1L)
            }

            val result = FileUtils.readFile(FileAccessPermission.Allowed, target.absolutePath)
            assertTrue(result.isFailure)
            assertEquals(
                AppStrings.ui_file_too_large_cannot_read_all_at_once_arg0_bytes_please_use_chunked_reading.format(arg0 = (target.length()).toString()),
                result.exceptionOrNull()?.message
            )
        }
    }

    @Test
    fun readFileChunksRejectsSymbolicLinkWithoutFollowingIt() = runBlocking {
        withTempDir("fileutils-jvm-symlink-read-") { tempDir ->
            val secret = File(tempDir, "secret.bin")
            secret.writeBytes(byteArrayOf(9, 9, 9, 9))
            val link = File(tempDir, "alias.bin")
            Files.createSymbolicLink(link.toPath(), secret.toPath())

            val results = FileUtils.readFileChunks(FileAccessPermission.Allowed, link.absolutePath, 1024L).toList()
            assertEquals(1, results.size)
            assertTrue(results.single().isFailure)
            val error = results.single().exceptionOrNull()
            assertTrue(
                error is AuthorityException ||
                    error?.message == AppStrings.ui_not_allowed_to_read_write_symbolic_links_arg0.format(arg0 = link.absolutePath)
            )
            assertContentEquals(byteArrayOf(9, 9, 9, 9), secret.readBytes())
        }
    }

    @Test
    fun writeBytesRejectsSymbolicLinkWithoutFollowingIt() = runBlocking {
        withTempDir("fileutils-jvm-symlink-write-") { tempDir ->
            val secret = File(tempDir, "secret.bin")
            secret.writeBytes(byteArrayOf(1, 2, 3, 4))
            val link = File(tempDir, "alias.bin")
            Files.createSymbolicLink(link.toPath(), secret.toPath())

            val result = FileUtils.writeBytes(
                FileAccessPermission.Allowed,
                link.absolutePath,
                4L,
                byteArrayOf(9, 9, 9, 9),
                0L,
            )
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue(
                error is AuthorityException ||
                    error?.message == AppStrings.ui_not_allowed_to_read_write_symbolic_links_arg0.format(arg0 = link.absolutePath)
            )
            assertContentEquals(byteArrayOf(1, 2, 3, 4), secret.readBytes())
        }
    }

    private inline fun withTempDir(prefix: String, block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory(prefix).toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
