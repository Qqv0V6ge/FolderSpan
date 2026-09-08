package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.test.runSuspendTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathUtilsJvmTest {
    @Test
    fun getFileAndFolderReturnsEmptyListForEmptyDirectory() {
        val directory = Files.createTempDirectory("path-utils-empty").toFile()
        try {
            val result = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.absolutePath)

            assertTrue(result.isSuccess)
            assertEquals(emptyList(), result.getOrThrow())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun issueAwareListingDoesNotEnumerateChildDirectoryForDisplayCount() {
        val root = Files.createTempDirectory("path-utils-stat-list")
        val child = Files.createDirectory(root.resolve("child"))
        val nestedFile = Files.createFile(child.resolve("nested.txt"))
        try {
            val normalEntry = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString()).getOrThrow().single()
            val statisticsEntry = PathUtils.getFileAndFolderWithIssues(FileAccessPermission.Allowed, root.toString())
                .getOrThrow()
                .entries
                .single()

            assertEquals(1L, normalEntry.size)
            assertEquals(0L, statisticsEntry.size)
        } finally {
            Files.deleteIfExists(nestedFile)
            Files.deleteIfExists(child)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun isSymbolicLinkDoesNotFollowDirectoryTarget() {
        val root = Files.createTempDirectory("path-utils-symlink")
        val target = Files.createDirectory(root.resolve("target"))
        val link = root.resolve("link")
        try {
            runCatching { Files.createSymbolicLink(link, target) }.getOrNull() ?: return

            assertTrue(PathUtils.isSymbolicLink(FileAccessPermission.Allowed, link.toString()))
            assertFalse(PathUtils.isSymbolicLink(FileAccessPermission.Allowed, target.toString()))
        } finally {
            Files.deleteIfExists(link)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun listMarksSymbolicLinkWithoutTreatingItAsDirectory() {
        val root = Files.createTempDirectory("path-utils-list-link")
        val outside = Files.createTempDirectory("path-utils-list-target")
        val link = root.resolve("outside-link")
        try {
            runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return

            val listed = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString()).getOrThrow().single()

            assertTrue(listed.isSymbolicLinkKnown)
            assertTrue(listed.isSymbolicLink)
            assertFalse(listed.isDirectory)
        } finally {
            Files.deleteIfExists(link)
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun pathBoundaryRejectsSimilarPrefixAndNestedSymbolicLink() {
        val parent = Files.createTempDirectory("path-utils-boundary")
        val root = Files.createDirectory(parent.resolve("share"))
        val similar = Files.createDirectory(parent.resolve("share-backup"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        val link = root.resolve("outside-link")
        try {
            runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return

            assertTrue(PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root.toString(), root.toString()))
            assertTrue(
                PathUtils.isPathWithinRoot(FileAccessPermission.Allowed,
                    root.toString(),
                    root.resolve("new.txt").toString(),
                    allowNonExistentLeaf = true,
                )
            )
            assertFalse(PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root.toString(), similar.resolve("file.txt").toString(), true))
            assertFalse(PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root.toString(), link.toString()))
            assertFalse(PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root.toString(), link.resolve("secret.txt").toString(), true))
        } finally {
            Files.deleteIfExists(link)
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun deleteDirectoryNeverDeletesSymbolicLinkTarget() {
        val parent = Files.createTempDirectory("path-utils-safe-delete")
        val root = Files.createDirectory(parent.resolve("root"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("keep.txt"), "keep")
        val link = root.resolve("outside-link")
        try {
            runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return

            PathUtils.deleteDirectory(FileAccessPermission.Allowed, root.toString())

            assertFalse(Files.exists(root))
            assertTrue(Files.exists(sentinel))
            assertEquals("keep", Files.readString(sentinel))
        } finally {
            Files.deleteIfExists(link)
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileCopyNeverReadsSymbolicLinkTarget() = runSuspendTest {
        val root = Files.createTempDirectory("path-utils-copy-link")
        val outside = Files.createTempFile("path-utils-copy-target", ".txt")
        Files.writeString(outside, "secret")
        val link = root.resolve("secret-link")
        val destination = root.resolve("copied.txt")
        try {
            runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return@runSuspendTest
            val linkInfo = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, root.toString()).getOrThrow().single()

            val result = linkInfo.writeToFile(destination.toString())

            assertTrue(result.isFailure)
            assertFalse(Files.exists(destination))
            assertEquals("secret", Files.readString(outside))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(destination)
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun resolveCanonicalPathFollowsSymlinkAndMissingLeaf() {
        val root = Files.createTempDirectory("path-utils-canonical")
        val target = Files.writeString(root.resolve("notes.txt"), "ok")
        val link = root.resolve("notes-link.txt")
        val missing = root.resolve("new.bin")
        try {
            runCatching { Files.createSymbolicLink(link, target) }.getOrNull() ?: return
            assertEquals(
                target.toRealPath().toString(),
                PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, link.toString()),
            )
            assertEquals(
                root.toRealPath().resolve("new.bin").toString(),
                PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, missing.toString(), allowNonExistentLeaf = true),
            )
            assertNull(PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, missing.toString()))
        } finally {
            Files.deleteIfExists(link)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmCachePathIsNotSystemTmpdirAndIsOwnerOnly() {
        val cachePath = PathUtils.getCachePath()
        val tmpdir = System.getProperty("java.io.tmpdir").orEmpty()
        val applicationCache = resolveDesktopApplicationDataDirectory().resolve("cache").toString()

        assertEquals(applicationCache, cachePath)
        assertFalse(cachePath.startsWith(tmpdir))
        val permissions = runCatching { Files.getPosixFilePermissions(java.nio.file.Paths.get(cachePath)) }.getOrNull()
        if (permissions != null) {
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                permissions,
            )
        }
    }
}
