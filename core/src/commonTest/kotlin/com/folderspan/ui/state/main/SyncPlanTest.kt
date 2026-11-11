package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPlanTest {
    @Test fun selectedRootTypeConflictsProtectEveryParticipantAndDescendant() {
        for (mode in SyncMode.entries) for (selectedDirectory in listOf(false, true)) {
            val source = tree("source", mapOf("a" to if (selectedDirectory) directory() else file(), "a/child" to file(), "b" to file()))
            val target = tree("target", mapOf("a" to if (selectedDirectory) file() else directory(), "a/other" to file()))
            val plan = planSync(task(mode), listOf(source, target), emptyMap(), "run", selectedSourceTypes = mapOf("a" to selectedDirectory))
            val root = plan.paths.first { it.relativePath == "a" }
            assertTrue(root.conflict && root.skipped)
            assertTrue(plan.operations.none { it.relativePath == "a" || it.relativePath.startsWith("a/") })
            assertTrue(plan.operations.any { it.relativePath == "b" })
        }
    }

    @Test fun selectedDirectoryDeletionPropagatesChildrenWithoutRemovingThePhysicalRoot() {
        for (emptyDirectories in listOf(false, true)) for (policy in SyncBidiConflictPolicy.entries) {
            val before = tree("source", mapOf("a" to directory(), "a/child" to file()))
            val oldTarget = tree("target", before.scan.entries)
            val target = tree("target", emptyMap())
            val plan = planSync(task().copy(includeEmptyDirectories = emptyDirectories, bidiConflictPolicy = policy),
                listOf(before, target), treeBaseline(before, oldTarget), "run", selectedSourceTypes = mapOf("a" to true))
            assertTrue(plan.operations.none { it.participant == "source" && it.relativePath == "a" })
            assertEquals(SyncOperationKind.Delete, plan.operations.single { it.relativePath == "a/child" }.kind)
            assertEquals("source", plan.operations.single { it.relativePath == "a/child" }.participant)
            assertEquals(if (emptyDirectories) 1 else 0, plan.operations.count { it.kind == SyncOperationKind.CreateDirectory })
            val after = tree("source", mapOf("a" to directory()))
            val targetAfter = tree("target", if (emptyDirectories) mapOf("a" to directory()) else emptyMap())
            assertTrue(planSync(task().copy(includeEmptyDirectories = emptyDirectories), listOf(after, targetAfter),
                treeBaseline(after, targetAfter), "next", selectedSourceTypes = mapOf("a" to true)).operations.isEmpty())
        }
    }

    @Test fun bidirectionalPresenceAndTypeMatrixPropagatesOneChangeAndMergesNewPaths() {
        val original = file()
        for (old in listOf<FileSimpleInfo?>(null, original, directory())) {
            for (changed in listOf<FileSimpleInfo?>(null, file(20, 20_000), directory())) {
                if (old?.syncEntryState() == changed?.syncEntryState()) continue
                for (sourceChanged in listOf(true, false)) {
                    val ends = listOf(participant("source", if (sourceChanged) changed else old),
                        participant("target", if (sourceChanged) old else changed))
                    val previous = baseline("source" to old, "target" to old)
                    val plan = planSync(task(), ends, previous, "run")
                    assertFalse(plan.paths.single().conflict)
                    val operation = plan.operations.single()
                    assertEquals(if (sourceChanged) "target" else "source", operation.participant)
                    assertEquals(when {
                        changed == null -> if (old!!.isDirectory) SyncOperationKind.CleanEmptyDirectory else SyncOperationKind.Delete
                        changed.isDirectory -> SyncOperationKind.CreateDirectory
                        old == null -> SyncOperationKind.Copy
                        else -> SyncOperationKind.Overwrite
                    }, operation.kind)
                    assertEquals(old?.syncEntryState(), operation.expectedTarget)
                    assertEquals(changed?.syncEntryState(), operation.expectedSource)
                }
            }
        }
        for (current in listOf<FileSimpleInfo?>(null, file(20, 20_000), directory())) {
            val ends = listOf(participant("source", current), participant("target", current))
            val plan = planSync(task(), ends, baseline("source" to original, "target" to original), "run")
            assertFalse(plan.paths.single().conflict)
            assertTrue(plan.operations.isEmpty())
            assertEquals(setOf("source", "target"), plan.paths.single().expected.keys)
        }
        for (sourcePresent in listOf(true, false)) {
            val ends = listOf(participant("source", if (sourcePresent) original else null), participant("target", if (sourcePresent) null else original))
            val plan = planSync(task(), ends, emptyMap(), "run")
            assertEquals(SyncOperationKind.Copy, plan.operations.single().kind)
            assertEquals(if (sourcePresent) "target" else "source", plan.operations.single().participant)
        }
    }

    @Test fun conflictPoliciesChooseTheRequestedStateAndKeepVersionsForTiedOrUnknownTimes() {
        val original = file()
        val source = file(20, 20_000)
        val target = file(30, 30_000)
        val previous = baseline("source" to original, "target" to original)
        for (sourceDeleted in listOf(true, false)) {
            val ends = listOf(participant("source", if (sourceDeleted) null else source), participant("target", if (sourceDeleted) target else null))
            for (policy in SyncBidiConflictPolicy.entries) {
                val plan = planSync(task().copy(bidiConflictPolicy = policy), ends, previous, "run")
                assertTrue(plan.paths.single().conflict)
                val requestedDeletion = policy == SyncBidiConflictPolicy.Source && sourceDeleted || policy == SyncBidiConflictPolicy.Target && !sourceDeleted
                assertEquals(if (requestedDeletion) SyncOperationKind.Delete else SyncOperationKind.Copy, plan.operations.single().kind)
                assertEquals(if (requestedDeletion) null else if (sourceDeleted) "target" else "source", plan.operations.single().sourceParticipant)
            }
        }
        val ends = listOf(participant("source", source), participant("target", target))
        for ((policy, winner) in listOf(SyncBidiConflictPolicy.Source to "source", SyncBidiConflictPolicy.Target to "target", SyncBidiConflictPolicy.Newer to "target")) {
            val operation = planSync(task().copy(bidiConflictPolicy = policy), ends, previous, "run").operations.single()
            assertEquals(winner, operation.sourceParticipant)
            assertEquals(SyncOperationKind.Overwrite, operation.kind)
        }
        val sameTime = listOf(participant("source", source), participant("target", target.copy(updatedDate = source.updatedDate)))
        assertEquals(2, planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Newer), sameTime, previous, "run").operations.count { it.kind == SyncOperationKind.ConflictCopy })
        val differentTypes = listOf(participant("source", source), participant("target", directory().copy(updatedDate = 30_000)))
        for ((policy, winner, kind) in listOf(Triple(SyncBidiConflictPolicy.Source, "source", SyncOperationKind.Overwrite),
            Triple(SyncBidiConflictPolicy.Target, "target", SyncOperationKind.CreateDirectory),
            Triple(SyncBidiConflictPolicy.Newer, "target", SyncOperationKind.CreateDirectory))) {
            val operation = planSync(task().copy(bidiConflictPolicy = policy), differentTypes, previous, "run").operations.single()
            assertEquals(winner, operation.sourceParticipant)
            assertEquals(kind, operation.kind)
        }
    }

    @Test fun excludedRemaindersAndUnreadableDescendantsProtectTheirDirectoryFromRemoval() {
        val before = mapOf("a" to directory(), "a/visible" to file(), "a/private" to file())
        val target = tree("target", before - "a/private").let { it.copy(scan = it.scan.copy(occupied = before, protectedPaths = setOf("a/private"))) }
        val source = tree("source", emptyMap())
        for (mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) {
            val plan = planSync(task(mode), listOf(source, target), treeBaseline(tree("source", before), tree("target", before)), "run")
            assertTrue(plan.paths.first { it.relativePath == "a" }.skipped)
            assertTrue(plan.operations.none { it.relativePath == "a" || it.relativePath == "a/private" })
            assertEquals("a/visible", plan.operations.single().relativePath)
            assertEquals(SyncOperationKind.Delete, plan.operations.single().kind)
        }
        val changedId = file().syncEntryState().copy(storageReference = StorageReference(id = "replacement"))
        assertFalse(syncEntryUnchanged(file().syncEntryState(), changedId))
        assertFalse(syncEntryUnchanged(null, changedId))
        assertFalse(syncEntryUnchanged(changedId, null))
        assertFalse(syncEntryUnchanged(file().syncEntryState(), directory().syncEntryState()))
    }

    private fun task(mode: SyncMode = SyncMode.Bidirectional) = SyncTask(1, "plan", sourceType = SyncEndpointType.Local,
        sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target", mode = mode)
    private fun file(size: Long = 10, time: Long = 10_000) = FileSimpleInfo.pathFileSimpleInfo("/a")
        .withCopy(name = "a", isDirectory = false, size = size, updatedDate = time)
    private fun participant(id: String, file: FileSimpleInfo?, protected: Set<String> = emptySet()) = SyncParticipantScan(id, id,
        SyncDirectoryScan(if (file == null) emptyMap() else mapOf("a" to file), if (file == null) emptyMap() else mapOf("a" to file), protected))
    private fun baseline(vararg states: Pair<String, FileSimpleInfo?>) = mapOf("a" to SyncBaseline("a", states.associate { it.first to it.second?.syncEntryState() }))
    private fun directory() = file().withCopy(isDirectory = true, size = 0)
    private fun tree(id: String, files: Map<String, FileSimpleInfo>) = SyncParticipantScan(id, id,
        SyncDirectoryScan(files, files, emptySet(), nonEmptyDirectories = syncParentPaths(files.filterValues { !it.isDirectory }.keys)))
    private fun treeBaseline(vararg trees: SyncParticipantScan): Map<String, SyncBaseline> = trees.flatMap { it.scan.entries.keys }.toSet().associateWith { path ->
        SyncBaseline(path, trees.associate { it.id to it.scan.entries[path]?.syncEntryState() })
    }

    @Test fun firstBidirectionalMergeNeverDeletesAndLaterDeletionPropagates() {
        val old = file()
        val ends = listOf(participant("source", null), participant("target", old))
        val first = planSync(task(), ends, emptyMap(), "run")
        assertEquals(listOf(SyncOperationKind.Copy), first.operations.map { it.kind })
        assertEquals("source", first.operations.single().participant)
        val later = planSync(task(), ends, baseline("source" to old, "target" to old), "run")
        assertEquals(listOf(SyncOperationKind.Delete), later.operations.map { it.kind })
        assertEquals("target", later.operations.single().participant)
        assertTrue(planSync(task(), listOf(participant("source", null), participant("target", null)),
            baseline("source" to old, "target" to old), "run").operations.isEmpty())
    }

    @Test fun singleChangedTargetPropagatesToEveryOtherParticipantAndIdenticalCopiesAreSkipped() {
        val old = file()
        val changed = file(20, 20_000)
        val ends = listOf(participant("source", old), participant("first", changed), participant("second", changed))
        val previous = baseline("source" to old, "first" to old, "second" to old)
        val plan = planSync(task(), ends, previous, "run")
        assertEquals(listOf("source"), plan.operations.map { it.participant })
        assertFalse(plan.paths.single().conflict)
    }

    @Test fun multiTargetKeepBothPreservesAllChangedVersionsBeforeOriginalsAreOverwritten() {
        val old = file()
        val ends = listOf(participant("source", old), participant("first", file(20, 20_000)), participant("second", file(30, 30_000)))
        val previous = baseline("source" to old, "first" to old, "second" to old)
        val plan = planSync(task(), ends, previous, "2026-11-04 2000")
        assertTrue(plan.paths.single().conflict)
        assertEquals(3, plan.operations.count { it.kind == SyncOperationKind.ConflictCopy })
        assertEquals(setOf("source", "first", "second"), plan.operations.filter { it.kind == SyncOperationKind.ConflictCopy }.map { it.participant }.toSet())
        assertTrue(plan.operations.take(3).all { it.kind == SyncOperationKind.ConflictCopy })
        assertEquals(2, plan.operations.count { it.kind == SyncOperationKind.Overwrite })
        val reversed = planSync(task(), listOf(ends[0], ends[2], ends[1]), previous, "2026-11-04 2000")
        assertEquals(plan.operations.toSet(), reversed.operations.toSet())
    }

    @Test fun targetPriorityIsTheFirstSavedTargetAndModificationBeatsDeletionByDefault() {
        val old = file()
        val ends = listOf(participant("source", null), participant("first", file(20, 20_000)), participant("second", file(30, 30_000)))
        val previous = baseline("source" to old, "first" to old, "second" to old)
        val chosen = planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Target), ends, previous, "run")
        assertTrue(chosen.operations.all { it.sourceParticipant == "first" })
        val deletion = planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Source), ends, previous, "run")
        assertEquals(2, deletion.operations.count { it.kind == SyncOperationKind.Delete })
        assertTrue(planSync(task(), ends, previous, "run").operations.none { it.kind == SyncOperationKind.Delete })
    }

    @Test fun mirrorRestoresTargetChangesButNeverWritesTheSource() {
        val old = file()
        val ends = listOf(participant("source", old), participant("target", file(20, 20_000)))
        val plan = planSync(task(SyncMode.Mirror), ends, baseline("source" to old, "target" to old), "run")
        assertEquals(SyncOperationKind.Overwrite, plan.operations.single().kind)
        assertEquals("target", plan.operations.single().participant)
        assertEquals(SyncOperationKind.Delete, planSync(task(SyncMode.Mirror), listOf(participant("source", null), ends[1]), emptyMap(), "run").operations.single().kind)
    }

    @Test fun backupCleanupRequiresEveryTargetAndNeverAcceptsAnOldSourceVersion() {
        val source = file(20, 20_000)
        val old = file()
        val ends = listOf(participant("source", source), participant("first", old), participant("second", null))
        val backup = task(SyncMode.BackupThenClean).copy(conflictPolicy = SyncConflictPolicy.Skip)
        val skipped = planSync(backup, ends, emptyMap(), "run")
        assertTrue(skipped.paths.single().skipped)
        assertTrue(skipped.operations.none { it.kind == SyncOperationKind.CleanSource })
        val previous = mapOf("a" to SyncBaseline("a", mapOf("source" to null, "first" to old.syncEntryState()),
            mapOf("first" to SyncBackupCopy(old.syncEntryState(), "a", old.syncEntryState()))))
        assertTrue(planSync(backup, ends, previous, "run").operations.none { it.kind == SyncOperationKind.CleanSource })
        val rename = planSync(backup.copy(conflictPolicy = SyncConflictPolicy.Rename), ends, previous, "run")
        assertEquals("a(1)", rename.paths.single().backupPaths["first"])
        assertEquals(SyncOperationKind.CleanSource, rename.operations.last().kind)
        assertTrue(rename.operations.none { it.kind == SyncOperationKind.Overwrite || it.kind == SyncOperationKind.Delete })
    }

    @Test fun unreadableOrExcludedPathsNeverProduceWritesAndUnknownStateCannotAuthorizeCleanup() {
        val ends = listOf(participant("source", file()), participant("target", file(20), setOf("a")))
        for (mode in SyncMode.entries) assertTrue(planSync(task(mode), ends, emptyMap(), "run").operations.isEmpty())
        val missing = listOf(participant("source", null), participant("target", null))
        assertTrue(planSync(task().copy(filterRaw = "a"), missing, baseline("source" to file(), "target" to file()), "run").paths.single().skipped)
        val unknown = listOf(participant("source", file(time = 0)), participant("target", null))
        assertTrue(planSync(task(SyncMode.BackupThenClean), unknown, emptyMap(), "run").operations.none { it.kind == SyncOperationKind.CleanSource })
        assertFalse(syncEntryUnchanged(file(time = 0).syncEntryState(), file(time = 0).syncEntryState()))
        assertTrue(syncEntryUnchanged(file().syncEntryState(), file(time = 12_000).syncEntryState()))
        assertFalse(syncEntryUnchanged(file().syncEntryState(), file(time = 12_001).syncEntryState()))
    }

    @Test fun deletingADirectoryConflictsWithEditingAChildAndRestoresTheWholeDirectory() {
        val before = mapOf("a" to directory(), "a/b" to file())
        val previous = treeBaseline(tree("source", before), tree("target", before))
        val now = listOf(tree("source", emptyMap()), tree("target", before + ("a/b" to file(20, 20_000))))
        val plan = planSync(task(), now, previous, "run")
        assertTrue(plan.paths.first().conflict)
        assertEquals(listOf(SyncOperationKind.CreateDirectory, SyncOperationKind.Copy), plan.operations.map { it.kind })
        assertTrue(plan.operations.all { it.participant == "source" })
    }

    @Test fun fileDirectoryConflictPreservesTheFileBeforeRebuildingChildren() {
        val before = mapOf("a" to directory(), "a/b" to file())
        val previous = treeBaseline(tree("source", before), tree("target", before))
        val now = listOf(tree("source", mapOf("a" to file(30, 30_000))), tree("target", before + ("a/b" to file(20, 20_000))))
        val kept = planSync(task(), now, previous, "run")
        assertEquals(2, kept.paths.first().operations.count { it.kind == SyncOperationKind.ConflictCopy })
        assertEquals(SyncOperationKind.CreateDirectory, kept.paths.first().operations.last().kind)
        assertEquals(SyncOperationKind.Copy, kept.paths.last().operations.single().kind)
        val sourceWins = planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Source), now, previous, "run")
        assertEquals(SyncOperationKind.Overwrite, sourceWins.operations.single().kind)
        assertEquals("a", sourceWins.paths.last().removedWithParent)
    }

    @Test fun contentProofsAreBoundToTheComparedStatesAndUnknownMetadataCannotAuthorizeDestruction() {
        val source = file()
        val target = file(time = 20_000)
        val proof = SyncContentMatch("a", "source", "target", source.syncEntryState(), target.syncEntryState())
        assertTrue(planSync(task(), listOf(participant("source", source), participant("target", target)), emptyMap(), "run", setOf(proof)).operations.isEmpty())
        val changed = planSync(task(), listOf(participant("source", source.copy(size = 30)), participant("target", target)), emptyMap(), "run", setOf(proof))
        assertTrue(changed.paths.single().conflict)
        val unknown = listOf(participant("source", file(time = 0)), participant("target", file(20)))
        for (mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) {
            val plan = planSync(task(mode), unknown, baseline("source" to file(), "target" to file()), "run")
            assertTrue(plan.paths.single().skipped)
            assertTrue(plan.operations.none { it.kind == SyncOperationKind.Overwrite || it.kind == SyncOperationKind.Delete })
        }
    }

    @Test fun backupDirectoryCollisionsRenameTheWholeSubtreeAndSkipNeverCleansItsFiles() {
        val source = tree("source", mapOf("a" to directory(), "a/b" to file()))
        val target = tree("target", mapOf("a" to file(30)))
        val backup = task(SyncMode.BackupThenClean).copy(conflictPolicy = SyncConflictPolicy.Rename)
        val renamed = planSync(backup, listOf(source, target), emptyMap(), "run")
        assertEquals("a(1)", renamed.paths.first().backupPaths["target"])
        assertEquals("a(1)/b", renamed.paths.last().backupPaths["target"])
        assertTrue(renamed.operations.none { it.kind == SyncOperationKind.Overwrite || it.kind == SyncOperationKind.Delete })
        assertEquals(SyncOperationKind.CleanSource, renamed.operations.last().kind)
        val skipped = planSync(backup.copy(conflictPolicy = SyncConflictPolicy.Skip), listOf(source, target), emptyMap(), "run")
        assertTrue(skipped.paths.all { it.skipped })
        assertTrue(skipped.operations.isEmpty())
        val copiedDirectory = directory().copy(updatedDate = 40_000)
        val copiedFile = file(time = 40_000)
        val populated = tree("target", mapOf("a" to file(30), "a(1)" to copiedDirectory, "a(1)/b" to copiedFile))
        val baseline = mapOf(
            "a" to SyncBaseline("a", emptyMap(), mapOf("target" to SyncBackupCopy(directory().syncEntryState(), "a(1)", copiedDirectory.syncEntryState()))),
            "a/b" to SyncBaseline("a/b", emptyMap(), mapOf("target" to SyncBackupCopy(file().syncEntryState(), "a(1)/b", copiedFile.syncEntryState()))),
        )
        val repeated = planSync(backup, listOf(source, populated), baseline, "run")
        assertEquals(listOf(SyncOperationKind.CleanSource), repeated.operations.map { it.kind })
        assertEquals("a(1)/b", repeated.paths.last().backupPaths["target"])
    }

    @Test fun newerFallsBackAtEqualTimesAndConflictNamesNeverOverwriteExistingCopies() {
        val source = participant("source", file(20, 20_000))
        val target = participant("target", file(30, 20_000))
        val existing = "a (冲突 target run)"
        val occupied = source.copy(scan = source.scan.copy(occupied = source.scan.occupied + (existing to file())))
        val plan = planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Newer), listOf(occupied, target), emptyMap(), "run", conflictWord = "冲突")
        assertTrue(plan.paths.single().conflict)
        assertEquals(setOf("$existing(1)"), plan.operations.filter { it.kind == SyncOperationKind.ConflictCopy }.map { it.relativePath }.toSet())
        val first = file(time = 0)
        val second = file(20, 0)
        val verified = listOf(participant("source", first).copy(verifiedStates = mapOf("a" to first.syncEntryState().copy(contentSha256 = "a".repeat(64)))),
            participant("target", second).copy(verifiedStates = mapOf("a" to second.syncEntryState().copy(contentSha256 = "b".repeat(64)))))
        val unknownTimes = planSync(task().copy(bidiConflictPolicy = SyncBidiConflictPolicy.Newer), verified, emptyMap(), "run")
        assertTrue(unknownTimes.paths.single().conflict)
        assertFalse(unknownTimes.paths.single().skipped)
        assertEquals(2, unknownTimes.operations.count { it.kind == SyncOperationKind.ConflictCopy })
        val state = verified.first().state("a")!!
        assertTrue(syncEntryUnchanged(state, state))
        assertFalse(syncEntryUnchanged(state, state.copy(contentSha256 = "c".repeat(64))))
    }

    @Test fun missingPriorityParticipantCannotDeleteOtherVersionsOnTheFirstRun() {
        val firstTargetMissing = listOf(participant("source", file(20, 20_000)), participant("first", null), participant("second", file(30, 30_000)))
        val firstSourceMissing = listOf(participant("source", null), participant("first", file(20, 20_000)), participant("second", file(30, 30_000)))
        for ((policy, ends) in listOf(SyncBidiConflictPolicy.Target to firstTargetMissing, SyncBidiConflictPolicy.Source to firstSourceMissing)) {
            val plan = planSync(task().copy(bidiConflictPolicy = policy), ends, emptyMap(), "run")
            assertTrue(plan.operations.none { it.kind == SyncOperationKind.Delete || it.kind == SyncOperationKind.CleanEmptyDirectory })
            assertTrue(plan.operations.any { it.kind == SyncOperationKind.ConflictCopy })
        }
    }

    @Test fun mirrorFirstComparisonExtraDirectoriesAndTypeChangesKeepTheSourceUntouched() {
        val source = tree("source", mapOf("a" to file()))
        val target = tree("target", mapOf("a" to file(time = 30_000), "old" to directory(), "old/b" to file(), "private" to file()))
        val guardedTarget = target.copy(scan = target.scan.copy(protectedPaths = setOf("private")))
        val mirror = planSync(task(SyncMode.Mirror), listOf(source, guardedTarget), emptyMap(), "run")
        assertTrue(mirror.operations.all { it.participant == "target" })
        assertTrue(mirror.operations.none { it.relativePath == "a" || it.relativePath == "private" })
        assertEquals(setOf(SyncOperationKind.Delete, SyncOperationKind.CleanEmptyDirectory), mirror.operations.map { it.kind }.toSet())
        val directorySource = tree("source", mapOf("a" to directory(), "a/b" to file()))
        val conflict = planSync(task(SyncMode.Mirror).copy(conflictPolicy = SyncConflictPolicy.Skip),
            listOf(directorySource, participant("target", file(20))), emptyMap(), "run")
        assertEquals(SyncOperationKind.CreateDirectory, conflict.operations.first().kind)
        assertEquals(false, conflict.operations.first().expectedTarget?.isDirectory)
        assertEquals(SyncOperationKind.Copy, conflict.operations.last().kind)
        assertTrue(conflict.operations.all { it.participant == "target" })
    }
}
