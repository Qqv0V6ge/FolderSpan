package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncUpdatePlanTest {
    private val task = SyncTask(1, "Update", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target")
    private fun file(path: String, directory: Boolean = false) = FileSimpleInfo.pathFileSimpleInfo(path)
        .copy(name = fileNameOf(path, "/"), isDirectory = directory, size = if (directory) 0 else 10, updatedDate = 10_000)
    private fun target(root: FileSimpleInfo? = null, occupied: Set<String> = emptySet()) = SyncTargetSnapshot(root,
        mutableMapOf(), root?.takeUnless { it.isDirectory }?.let { mutableMapOf(it.path to it) } ?: mutableMapOf(),
        root?.takeIf { it.isDirectory }?.let { mutableSetOf(it.path) } ?: mutableSetOf(),
        (occupied + listOfNotNull(root?.path)).toMutableSet())

    @Test fun rootFilePoliciesProduceFrozenActionsWithoutChangingTheSnapshot() {
        val existing = file("/target")
        val snapshot = target(existing, setOf("/target(1)"))
        val before = snapshot.filesByAbsolute.toMap()
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("a.txt", file("/source/a.txt"))), emptyList())
        val replace = planSync(task, source, snapshot, "/", "/target")
        assertEquals(SyncUpdateRootAction.ReplaceFile, replace.root.action)
        assertSame(existing, replace.root.expected)
        assertEquals("/target/a.txt", replace.items.single().targetPath)
        val rename = planSync(task.copy(conflictPolicy = SyncConflictPolicy.Rename), source, snapshot, "/", "/target")
        assertEquals("/target(2)", rename.root.path)
        assertEquals("/target(2)/a.txt", rename.items.single().targetPath)
        val skip = planSync(task.copy(conflictPolicy = SyncConflictPolicy.Skip), source, snapshot, "/", "/target")
        assertEquals(SyncUpdateRootAction.Skip, skip.root.action)
        assertTrue(skip.items.isEmpty())
        assertEquals(before, snapshot.filesByAbsolute)
        assertEquals(setOf("/target", "/target(1)"), snapshot.occupiedPaths)
    }

    @Test fun emptySourceStillEnsuresTheTargetRootWithEitherEmptyDirectoryOption() {
        for (includeEmpty in listOf(false, true)) {
            val plan = planSync(task.copy(includeEmptyDirectories = includeEmpty), SyncSourceSnapshot(emptyList(), emptyList()), target(), "/", "/target")
            assertEquals(SyncUpdateRootAction.EnsureDirectory, plan.root.action)
            assertTrue(plan.items.isEmpty())
        }
    }

    @Test fun orderedDuplicateSourcesAndEmptyDirectoryRulesKeepTheExistingUpdateActions() {
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("a.txt", file("/first/a.txt")),
            SyncSourceFileEntry("a.txt", file("/second/a.txt"))), listOf("empty"))
        for (policy in SyncConflictPolicy.entries) {
            val configured = task.copy(conflictPolicy = policy)
            val snapshot = target(file("/target", true))
            val plan = planSync(configured, source, snapshot, "/", "/target")
            assertEquals(planDirectorySync(source, snapshot, configured), plan.items)
            assertEquals(listOf("/first/a.txt", "/second/a.txt"), plan.items.mapNotNull { it.source?.path })
        }
        val noEmpty = planSync(task.copy(includeEmptyDirectories = false), source, target(), "/", "/target")
        assertTrue(noEmpty.items.none { it.action == SyncPlanAction.CreateDirectory })
    }
    @Test fun commonPlannerUsesTheLegacyUnknownTimeAndRenameRules() {
        for (policy in SyncConflictPolicy.entries) for (time in listOf(0L, 10_000L, 20_000L)) {
            val original = file("/source/a.txt").copy(updatedDate = time)
            val existing = file("/target/a.txt").copy(size = 3)
            val ends = listOf(SyncParticipantScan("source", "source", SyncDirectoryScan(mapOf("a.txt" to original), mapOf("a.txt" to original), emptySet())),
                SyncParticipantScan("target", "target", SyncDirectoryScan(mapOf("a.txt" to existing), mapOf("a.txt" to existing), emptySet())))
            val configured = task.copy(conflictPolicy = policy)
            val common = planSync(configured, ends, emptyMap(), "unused")
            val snapshot = target(file("/target", true)).also {
                it.filesByAbsolute[existing.path] = existing; it.occupiedPaths.add(existing.path)
            }
            val legacy = planSync(configured, SyncSourceSnapshot(listOf(SyncSourceFileEntry("a.txt", original)), emptyList()), snapshot, "/", "/target")
            assertEquals(SyncPlan(legacy.operations("source", "target", snapshot, "/")), common)
        }
    }

    @Test fun selectedDirectoryAliasDoesNotAddARecursionLevel() {
        val selection = file("/source/photos", true)
        val grouped = task.copy(destinations = listOf(StorageDestination(ref = "backup", path = "/target")),
            selectedSources = listOf(selection), includeSubdirectories = false)
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("photos/top.txt", file("/source/photos/top.txt")),
            SyncSourceFileEntry("photos/deep/nested.txt", file("/source/photos/deep/nested.txt"))), listOf("photos"))
        val plan = planSync(grouped, source, target(file("/target", true)), "/", "/target")
        assertEquals(listOf("photos/top.txt"), plan.items.filter { it.action == SyncPlanAction.Copy }.map { it.relativePath })
        assertEquals(SyncPlanAction.Skip, plan.items.single { it.relativePath == "photos/deep/nested.txt" }.action)
    }

    @Test fun selectedFileAndDirectoryTypeConflictsProtectTheAliasAndAllChildren() {
        for (directory in listOf(false, true)) {
            val selected = file("/source/item", directory)
            val configured = task.copy(destinations = listOf(StorageDestination(ref = "backup", path = "/target")), selectedSources = listOf(selected))
            val snapshot = target(file("/target", true))
            if (directory) snapshot.filesByAbsolute["/target/item"] = file("/target/item") else snapshot.directoryPaths.add("/target/item")
            snapshot.occupiedPaths.add("/target/item")
            val source = if (directory) SyncSourceSnapshot(listOf(SyncSourceFileEntry("item/child.txt", file("/source/item/child.txt"))), listOf("item"))
                else SyncSourceSnapshot(listOf(SyncSourceFileEntry("item", selected)), emptyList())
            for (policy in SyncConflictPolicy.entries) {
                val plan = planSync(configured.copy(conflictPolicy = policy), source, snapshot, "/", "/target")
                assertTrue(plan.items.isNotEmpty())
                assertTrue(plan.items.all { it.action == SyncPlanAction.Skip && it.skipReason == SyncPlanSkipReason.SelectedTypeConflict })
                assertTrue(plan.operations("source", "target", snapshot, "/").all { it.conflict && it.skipped })
            }
        }
    }

    @Test fun protectedManifestEntriesProduceOutcomesForTheGroupWithoutWriteActions() {
        val configured = task.copy(destinations = listOf(StorageDestination(ref = "backup", path = "/target")))
        val snapshot = target(file("/target", true)).copy(protectedPaths = setOf("protected"))
        val source = SyncSourceSnapshot(listOf(SyncSourceFileEntry("protected/a", file("/source/protected/a")),
            SyncSourceFileEntry("safe", file("/source/safe"))), listOf("protected"))
        val plan = planSync(configured, source, snapshot, "/", "/target")
        assertEquals(SyncPlanAction.Skip, plan.items.single { it.relativePath == "protected/a" }.action)
        assertEquals(SyncPlanSkipReason.Protected, plan.items.single { it.relativePath == "protected/a" }.skipReason)
        assertEquals(listOf("safe"), plan.items.filter { it.action == SyncPlanAction.Copy }.map { it.relativePath })
    }

}
