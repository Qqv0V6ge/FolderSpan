package com.folderspan.service.http.clipboard

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.FileSystem

class ClipboardDownloadStagingJvmTest {
    @Test
    fun partsAreMergedInOrderAndOnlyFinalFileIsPublished(): Unit = runBlocking {
        val cache = Files.createTempDirectory("clipboard-staging-test-")
        try {
            val batch = OkioClipboardDownloadStagingFactory(
                fileSystem = FileSystem.SYSTEM,
                cacheRoot = { cache.toString() },
            ).create()
            batch.append(1, "CD".encodeToByteArray())
            batch.append(0, "AB".encodeToByteArray())
            val taskRoot = cache.listDirectoryEntries().single().listDirectoryEntries().single()
            assertTrue(taskRoot.listDirectoryEntries().all { it.fileName.toString().contains("part") })

            val published = batch.publish(listOf(0, 1), "ordered.bin", "application/octet-stream", 4L)
            val finalPath = requireNotNull(published.localPath).let(java.nio.file.Path::of)
            assertContentEquals("ABCD".encodeToByteArray(), finalPath.readBytes())
            assertEquals(listOf("ordered.bin"), finalPath.parent.listDirectoryEntries().map { it.fileName.toString() })

            published.release()
            assertFalse(finalPath.parent.exists())
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun resetAndCleanupLeaveNoOrphanedParts(): Unit = runBlocking {
        val cache = Files.createTempDirectory("clipboard-staging-cleanup-")
        try {
            val batch = OkioClipboardDownloadStagingFactory(
                fileSystem = FileSystem.SYSTEM,
                cacheRoot = { cache.toString() },
            ).create()
            batch.append(0, byteArrayOf(1, 2))
            batch.resetPart(0)
            assertEquals(0L, batch.partSize(0))
            batch.append(0, byteArrayOf(3, 4))
            batch.cleanup()
            assertTrue(cache.listDirectoryEntries().single().listDirectoryEntries().isEmpty())
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
