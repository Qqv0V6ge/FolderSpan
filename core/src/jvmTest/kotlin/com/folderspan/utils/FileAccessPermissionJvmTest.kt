package com.folderspan.utils

import com.folderspan.exception.AuthorityException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileAccessPermissionJvmTest {
    @Test
    fun allowedAccessPreservesReadWriteAndListingBehavior() = runBlocking {
        val root = Files.createTempDirectory("file-access-allowed")
        val target = root.resolve("allowed.bin")
        val content = byteArrayOf(1, 2, 3)
        try {
            assertTrue(
                FileUtils.writeBytes(
                    FileAccessPermission.Allowed,
                    target.toString(),
                    content.size.toLong(),
                    content,
                    0L,
                ).getOrThrow()
            )
            assertContentEquals(content, FileUtils.readFile(FileAccessPermission.Allowed, target.toString()).getOrThrow())
            assertEquals(
                listOf("allowed.bin"),
                PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString())
                    .getOrThrow()
                    .map { item -> item.name },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedResultDoesNotProbeOrCreateTarget() = runBlocking {
        val root = Files.createTempDirectory("file-access-denied")
        val missing = root.resolve("missing.bin")
        try {
            val read = FileUtils.readFile(FileAccessPermission.Denied, missing.toString())
            val write = FileUtils.writeBytes(
                FileAccessPermission.Denied,
                missing.toString(),
                3L,
                byteArrayOf(1, 2, 3),
                0L,
            )

            assertIs<AuthorityException>(read.exceptionOrNull())
            assertIs<AuthorityException>(write.exceptionOrNull())
            assertFalse(Files.exists(missing))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedBooleanAndFlowUseStableFailureSemantics() = runBlocking {
        val root = Files.createTempDirectory("path-access-denied")
        try {
            assertFalse(PathUtils.exists(FileAccessPermission.Denied, root.toString()))
            assertFalse(PathUtils.isSymbolicLink(FileAccessPermission.Denied, root.toString()))

            val results = PathUtils.traverse(FileAccessPermission.Denied, root.toString()).toList()
            assertEquals(1, results.size)
            assertIs<AuthorityException>(results.single().exceptionOrNull())

            val chunks = FileUtils.readFileChunks(FileAccessPermission.Denied, root.toString(), 4L).toList()
            assertEquals(1, chunks.size)
            assertIs<AuthorityException>(chunks.single().exceptionOrNull())
            Unit
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedListingsReturnAuthorityFailure() {
        val root = Files.createTempDirectory("path-listing-denied")
        try {
            Files.writeString(root.resolve("must-not-be-returned.txt"), "secret")

            val listing = PathUtils.getFileAndFolder(FileAccessPermission.Denied, root.toString())
            val roots = PathUtils.getRootPaths(FileAccessPermission.Denied)

            assertIs<AuthorityException>(listing.exceptionOrNull())
            assertIs<AuthorityException>(roots.exceptionOrNull())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedThrowingApiDoesNotMutateFileSystem() {
        val root = Files.createTempDirectory("path-mutation-denied")
        val target = root.resolve("nested")
        try {
            assertFailsWith<AuthorityException> {
                PathUtils.createDirectoryIfNotExists(FileAccessPermission.Denied, target.toString())
            }
            assertFalse(Files.exists(target))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedWriteLeavesExistingContentUntouched() = runBlocking {
        val target = Files.createTempFile("file-write-denied", ".bin")
        val original = byteArrayOf(7, 8, 9)
        Files.write(target, original)
        try {
            val result = FileUtils.writeBytes(
                FileAccessPermission.Denied,
                target.toString(),
                original.size.toLong(),
                byteArrayOf(1),
                0L,
            )

            assertTrue(result.isFailure)
            assertIs<AuthorityException>(result.exceptionOrNull())
            assertContentEquals(original, Files.readAllBytes(target))
        } finally {
            Files.deleteIfExists(target)
        }
    }
}
