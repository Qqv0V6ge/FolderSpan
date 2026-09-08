package com.folderspan.utils

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebInMemoryFileStoreTest {
    @Test
    fun putFileWithExternalSourceReadsRangeWithoutStoringContent() = runSuspendTest {
        val basePath = "/web-drop-source-test"
        val relativePath = "payload.bin"
        val content = byteArrayOf(0, 1, 2, 3, 4)
        val source = RecordingWebExternalFileSource(content)

        WebInMemoryFileStore.ensureDirectory(basePath).getOrThrow()
        WebInMemoryFileStore.putFile(
            basePath = basePath,
            relativePath = relativePath,
            size = content.size.toLong(),
            lastModified = 1234L,
            source = source,
        )

        val path = "$basePath/$relativePath"
        assertTrue(WebInMemoryFileStore.readFile(path).isFailure)
        assertContentEquals(byteArrayOf(1, 2, 3), WebInMemoryFileStore.readExternalFileRange(path, 1L, 4L).getOrThrow())
        assertEquals(content.size.toLong(), WebInMemoryFileStore.get(path).getOrThrow().size)
        assertEquals(listOf(1L to 4L), source.requests)
    }

    @Test
    fun writtenMemoryFileDoesNotUseExternalSourcePath() {
        val path = "/web-memory-file-test/payload.txt"
        val content = "hello".encodeToByteArray()

        WebInMemoryFileStore.ensureDirectory("/web-memory-file-test").getOrThrow()
        WebInMemoryFileStore.writeBytes(path, content.size.toLong(), content, 0L).getOrThrow()

        assertTrue(!WebInMemoryFileStore.hasExternalFileSource(path))
        assertContentEquals(content, WebInMemoryFileStore.readFile(path).getOrThrow())
    }

    private class RecordingWebExternalFileSource(
        private val content: ByteArray,
    ) : WebExternalFileSource {
        val requests = mutableListOf<Pair<Long, Long>>()

        override suspend fun readRange(start: Long, endExclusive: Long): Result<ByteArray> {
            requests += start to endExclusive
            return Result.success(content.copyOfRange(start.toInt(), endExclusive.toInt()))
        }
    }
}
