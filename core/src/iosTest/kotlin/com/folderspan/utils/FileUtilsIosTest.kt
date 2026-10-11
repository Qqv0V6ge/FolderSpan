package com.folderspan.utils

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileUtilsIosTest {
    @Test
    fun rangeFlowFirstOrNullReturnsTheFirstChunk() = runBlocking {
        val bytes = "FolderSpan iOS range read".encodeToByteArray()
        val path = "${NSTemporaryDirectory().trimEnd('/')}/fileutils-${NSUUID().UUIDString}.txt"

        try {
            val written = FileUtils.writeBytes(
                permission = FileAccessPermission.Allowed,
                path = path,
                fileSize = bytes.size.toLong(),
                data = bytes,
                offset = 0L,
            )
            assertTrue(written.isSuccess, written.exceptionOrNull()?.message.orEmpty())

            val result = FileUtils.readFileRangeChunks(
                permission = FileAccessPermission.Allowed,
                path = path,
                start = 0L,
                end = bytes.size.toLong(),
                chunkSize = bytes.size.toLong(),
            ).firstOrNull()

            assertNotNull(result)
            assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
            assertContentEquals(bytes, result.getOrThrow().second)
        } finally {
            FileUtils.deleteFile(FileAccessPermission.Allowed, path)
        }
    }
}
