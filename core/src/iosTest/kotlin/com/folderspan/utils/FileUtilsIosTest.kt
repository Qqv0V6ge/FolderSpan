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
    @Test fun emptyDirectoryDeletionNeverRemovesFilesOrNonEmptyDirectories(): Unit = runBlocking {
        val directory = "${NSTemporaryDirectory().trimEnd('/')}/empty-${NSUUID().UUIDString}"
        val child = "$directory/new.txt"
        try {
            assertTrue(FileUtils.createFolder(FileAccessPermission.Allowed, directory).getOrThrow())
            assertTrue(FileUtils.deleteEmptyDirectory(FileAccessPermission.Denied, directory).isFailure)
            assertTrue(FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, directory).getOrThrow())
            assertTrue(FileUtils.createFolder(FileAccessPermission.Allowed, directory).getOrThrow())
            assertTrue(FileUtils.writeBytes(FileAccessPermission.Allowed, child, 4, "keep".encodeToByteArray(), 0).getOrThrow())
            assertTrue(FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, directory).isFailure)
            assertTrue(FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, child).isFailure)
            assertContentEquals("keep".encodeToByteArray(), FileUtils.readFile(FileAccessPermission.Allowed, child).getOrThrow())
        } finally {
            FileUtils.deleteFile(FileAccessPermission.Allowed, child)
            FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, directory)
        }
    }

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
