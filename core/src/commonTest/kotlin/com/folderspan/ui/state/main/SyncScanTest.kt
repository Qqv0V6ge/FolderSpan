package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncScanTest {
    @Test fun selectedDirectoryAliasesKeepDirectFilesWhenSubdirectoriesAreDisabled() = runTest {
        val task = task().copy(mode = SyncMode.Mirror, includeRaw = "*.jpg", includeSubdirectories = false)
        val root = file("/root/photos", true)
        val scope = SyncSourceScope(root, listOf(root), "/")
        val ignored: (String, Boolean) -> Boolean = { path, _ -> !scope.contains(path) || syncReservedPath(path) }
        val source = scanSyncDirectory(root, task, { Result.success(listOf(file("/root/photos/a.jpg"),
            file("/root/photos/nested", true), file("/root/photos/.folderspan-recycle", true))) },
            ignored = ignored, relativePrefix = "photos", directoryAliases = scope.directoryAliases).getOrThrow()
        val target = scanSyncDirectory(file("/target", true), task, { directory ->
            Result.success(if (directory.path == "/target") listOf(file("/target/photos", true), file("/target/private.txt")) else emptyList())
        }, ignored = ignored, directoryAliases = scope.directoryAliases).getOrThrow()
        assertEquals(setOf("photos", "photos/a.jpg"), source.entries.keys)
        assertTrue(source.protectedPaths.containsAll(listOf("photos/nested", "photos/.folderspan-recycle")))
        assertTrue("photos" in target.occupied)
        assertFalse("photos" in target.protectedPaths)
        val plan = planSync(task, listOf(SyncParticipantScan("source", "source", source), SyncParticipantScan("target", "target", target)),
            emptyMap(), "run", ignored = ignored, scopeRelativePath = scope::relativeWithinSelection)
        assertEquals(SyncOperationKind.Copy, plan.operations.single().kind)
        assertEquals("photos/a.jpg", plan.operations.single().relativePath)
        assertTrue(scanSyncDirectory(root, task, { Result.failure(IllegalStateException("unreadable")) }, relativePrefix = "photos").isFailure)
    }

    private fun task() = SyncTask(1, "scan", sourceType = SyncEndpointType.Local, sourcePath = "/root",
        targetType = SyncEndpointType.Local, targetPath = "/target")
    private fun file(path: String, directory: Boolean = false) = FileSimpleInfo.pathFileSimpleInfo(path)
        .withCopy(name = path.substringAfterLast('/'), isDirectory = directory, size = if (directory) 0 else 10, updatedDate = 10_000)

    @Test fun includesTraverseNestedDirectoriesAndExclusionAlwaysWins() = runTest {
        val task = task().copy(includeRaw = "*.jpg;*.mp4", filterRaw = "cache;private/*", includeEmptyDirectories = true)
        val tree = mapOf(
            "/root" to listOf(file("/root/photos", true), file("/root/cache", true), file("/root/notes.txt"), file("/root/empty", true)),
            "/root/photos" to listOf(file("/root/photos/sub", true), file("/root/photos/a.jpg")),
            "/root/photos/sub" to listOf(file("/root/photos/sub/b.mp4")),
            "/root/empty" to emptyList(),
        )
        val visited = mutableListOf<String>()
        val scan = scanSyncDirectory(file("/root", true), task, {
            visited += it.path
            Result.success(tree[it.path].orEmpty())
        }).getOrThrow()
        assertEquals(setOf("photos", "photos/sub", "photos/a.jpg", "photos/sub/b.mp4"), scan.entries.keys)
        assertFalse("/root/cache" in visited)
        assertTrue(scan.protectedPaths.containsAll(listOf("cache", "notes.txt")))
        assertFalse("empty" in scan.protectedPaths)
        assertTrue("empty" in scan.occupied)
        assertTrue("notes.txt" in scan.occupied)
    }

    @Test fun sharedIgnoreScopeAndNonRecursiveRulesProtectWholeSubtrees() = runTest {
        val root = file("/root", true)
        val children = listOf(file("/root/private", true), file("/root/nested", true), file("/root/a.jpg"))
        val ignored = scanSyncDirectory(root, task(), { Result.success(if (it.path == root.path) children else emptyList()) },
            ignored = { path, _ -> path == "private" }).getOrThrow()
        assertTrue("private" in ignored.protectedPaths)
        assertFalse("private" in ignored.entries)
        val flat = scanSyncDirectory(root, task().copy(includeSubdirectories = false), { Result.success(children) }).getOrThrow()
        assertEquals(setOf("a.jpg"), flat.entries.keys)
        assertTrue(flat.protectedPaths.containsAll(listOf("private", "nested")))
    }

    @Test fun rootFailuresAbortAndUnreadableSubtreesProtectBothEndpoints() = runTest {
        val task = task()
        val root = file("/root", true)
        assertTrue(scanSyncDirectory(root, task, { Result.failure(IllegalStateException("unavailable")) }).isFailure)
        val scan = scanSyncDirectory(root, task, { directory ->
            if (directory.path == "/root") Result.success(listOf(file("/root/blocked", true), file("/root/a")))
            else Result.failure(IllegalStateException("unreadable"))
        }).getOrThrow()
        assertTrue("blocked" in scan.protectedPaths)
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("blocked/keep", file("/root/blocked/keep"))), emptyList())
        val target = SyncTargetSnapshot(null, mutableMapOf(), mutableMapOf(), mutableSetOf(), mutableSetOf(), scan.protectedPaths)
        assertTrue(planDirectorySync(source, target, task).isEmpty())
    }

    @Test fun recycleTemporaryAndUnsafePathsNeverEnterTheScan() = runTest {
        val task = task()
        val root = file("/root", true)
        val temporary = ".folderspan-tmp-${syncTaskKey(task)}-attempt"
        val scan = scanSyncDirectory(root, task, { Result.success(listOf(file("/root/.folderspan-recycle", true), file("/root/$temporary"), file("/root/a"))) }).getOrThrow()
        assertEquals(setOf("a"), scan.entries.keys)
        assertTrue(syncReservedPath("nested/$temporary"))
        assertTrue(scanSyncDirectory(root, task, { Result.success(listOf(file("/outside/escape"))) }).isFailure)
        assertTrue(scanSyncDirectory(root, task, { Result.success(listOf(file("/root/a"), file("/root/a"))) }).isFailure)
    }

    @Test fun nestedArtifactsFromOtherTasksAndOrdinaryCopiesProtectTheirAncestors() = runTest {
        val artifacts = listOf(".folderspan-recycle", ".FOLDERSPAN-RECYCLE", ".folderspan-tmp-previous-owner-8-attempt",
            ".folderspan-34e1f0c1-3a94-49fe-9325-075a0ac6224c.part", ".FOLDERSPAN-copy.PART")
        val visited = mutableListOf<String>()
        val scan = scanSyncDirectory(file("/root", true), task(), { dir ->
            visited += dir.path
            Result.success(when (dir.path) {
                "/root" -> listOf(file("/root/nested", true), file("/root/plain.part"))
                "/root/nested" -> artifacts.map { file("/root/nested/$it", it.contains("RECYCLE", true)) }
                else -> error("Internal artifacts must never be traversed")
            })
        }).getOrThrow()
        assertEquals(setOf("nested", "plain.part"), scan.entries.keys)
        assertEquals(artifacts.map { "nested/$it" }.toSet(), scan.protectedPaths)
        assertEquals(listOf("/root", "/root/nested"), visited)
        assertFalse(syncReservedPath("nested/plain.part"))
        for (mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) {
            val source = SyncDirectoryScan(mapOf("nested" to file("/source/nested")), mapOf("nested" to file("/source/nested")), emptySet())
            val plan = planSync(task().copy(mode = mode), listOf(SyncParticipantScan("source", "source", source),
                SyncParticipantScan("target", "target", scan)), emptyMap(), "run")
            assertTrue(plan.paths.single { it.relativePath == "nested" }.skipped)
            assertTrue(plan.operations.none { it.relativePath == "nested" || it.relativePath.startsWith("nested/") })
        }
    }
}
