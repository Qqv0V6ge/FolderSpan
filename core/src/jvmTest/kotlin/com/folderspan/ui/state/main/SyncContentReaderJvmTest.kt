package com.folderspan.ui.state.main

import com.folderspan.data.main.Local
import com.folderspan.data.StatusEnum
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import korlibs.crypto.sha256
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncContentReaderJvmTest {
    @Test fun verifiedContentRejectsMetadataChangesBeforeAndDuringReading(): Unit = runBlocking {
        val root = Files.createTempDirectory("sync-verified-content")
        try {
            val path = Files.writeString(root.resolve("content"), "keep content")
            val file = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
            val operations = SyncEndpointFileOperations()
            val operation = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
            val state = readVerifiedSyncEndpointState(operations, SyncEndpointType.Local, "", Local(), file, operation).getOrThrow()
            assertEquals("keep content".encodeToByteArray().sha256().hexLower, state.contentSha256)
            var changed = false
            assertTrue(readVerifiedSyncEndpointState(operations, SyncEndpointType.Local, "", Local(), file, operation, onRead = {
                if (!changed) {
                    Files.setLastModifiedTime(path, FileTime.fromMillis(file.updatedDate + 10_000))
                    changed = true
                }
            }).isFailure)
            var readBytes = 0
            assertTrue(readVerifiedSyncEndpointState(operations, SyncEndpointType.Local, "", Local(), file, operation, onRead = { readBytes += it }).isFailure)
            assertEquals(0, readBytes)
            assertEquals("keep content", Files.readString(path))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun localReaderUsesBoundedRangesAndLeavesTheFileUntouched(): Unit = runBlocking {
        val root = Files.createTempDirectory("sync-content")
        try {
            val bytes = ByteArray(2 * 1024 * 1024 + 123) { (it % 251).toByte() }
            val path = Files.write(root.resolve("content.bin"), bytes)
            val before = Files.getLastModifiedTime(path)
            val file = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow()
            var read = 0L
            val digest = readSyncEndpointSha256(SyncEndpointType.Local, Local(), file,
                Task(taskType = TaskType.Copy, status = StatusEnum.LOADING), onRead = { read += it }).getOrThrow()
            assertEquals(bytes.sha256().hexLower, digest)
            assertEquals(bytes.size.toLong(), read)
            assertEquals(before, Files.getLastModifiedTime(path))
            assertEquals(bytes.sha256().hexLower, Files.readAllBytes(path).sha256().hexLower)
        } finally { root.toFile().deleteRecursively() }
    }
}
