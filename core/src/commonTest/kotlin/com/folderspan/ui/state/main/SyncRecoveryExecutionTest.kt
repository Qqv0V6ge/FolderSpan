package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRecoveryExecutionTest {
    @Test fun twoAndThreeParticipantConflictsRecoverEveryMoveFailureAndCancellationWithoutLosingVersions() = runTest {
        for (count in 2..3) {
            val ids = listOf("source", "target-0", "target-1").take(count)
            val versions = ids.mapIndexed { index, _ -> SyncEntryState(false, (index + 1) * 10L, 27_000,
                contentSha256 = ('a' + index).toString().repeat(64)) }
            val endpoints = ids.associateWith { SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/$it") }
            val participants = ids.mapIndexed { index, id ->
                val state = versions[index]
                val file = FileSimpleInfo.pathFileSimpleInfo("/$id/a").withCopy(name = "a", isDirectory = false,
                    size = state.size, updatedDate = state.modifiedAt)
                SyncParticipantScan(id, id, SyncDirectoryScan(mapOf("a" to file), mapOf("a" to file), emptySet()), mapOf("a" to state))
            }
            val task = SyncTask(1, "conflict", sourceType = SyncEndpointType.Local, sourcePath = "/source",
                targetType = SyncEndpointType.Local, targetPath = "/target-0", mode = SyncMode.Bidirectional)
            val before = SyncBaseline("a", ids.associateWith { SyncEntryState(false, 5, 1_000) })
            val path = planSync(task, participants, mapOf("a" to before), "run").paths.single()
            assertTrue(path.conflict && !path.skipped)
            val locations = endpoints.mapValues { (_, end) -> SyncRecoveryLocation(end, end.rootPath + "/a") }
            val records = path.operations.mapIndexed { index, operation ->
                val end = endpoints.getValue(operation.participant)
                val staged = checkNotNull(operation.expectedSource)
                SyncFileRecovery("record-$index", task.id, "definition", "device", "group", operation.participant,
                    end, operation.relativePath, end.rootPath + "/" + operation.relativePath,
                    end.rootPath + "/tmp-$index", if (operation.expectedTarget == null) "" else end.rootPath + "/old-$index",
                    operation.expectedTarget, staged, true, logicalPath = path.relativePath,
                    action = if (operation.expectedTarget == null) SyncRecoveryAction.Copy else SyncRecoveryAction.Overwrite,
                    conflictCopy = operation.kind == SyncOperationKind.ConflictCopy, stagedState = staged,
                    sourceEndpoint = endpoints.getValue(checkNotNull(operation.sourceParticipant)),
                    sourcePath = "/${operation.sourceParticipant}/${operation.sourceRelativePath}",
                    expectedParticipants = path.expected, participantLocations = locations)
            }
            fun files() = Files().apply {
                entries.clear()
                ids.forEachIndexed { index, id -> entries["/$id/a"] = versions[index] }
                records.forEach { entries[it.temporaryPath] = checkNotNull(it.stagedState) }
            }
            val successful = files()
            executeSyncRecoveryGroup(records, successful, {}, {})
            val moveCount = successful.moves.size
            assertTrue(moveCount > count)
            for (move in 1..moveCount) for (after in listOf(false, true)) for (cancel in listOf(false, true)) {
                val files = files()
                val journal = Journal()
                var seen = 0
                var injected = false
                val fault: (String, String) -> Unit = { _, _ ->
                    if (!injected && ++seen == move) {
                        injected = true
                        if (cancel) throw CancellationException("interrupted conflict") else error("conflict move failed")
                    }
                }
                if (after) files.afterMove = fault else files.beforeMove = fault
                val failure = assertFailsWith<Exception> { executeSyncRecoveryGroup(records, files, journal::save, {}) }
                assertTrue(injected)
                assertEquals(cancel, failure is CancellationException)
                versions.forEach { assertTrue(it in files.entries.values, "Version lost at move $move; after=$after; cancel=$cancel") }
                assertFailsWith<IllegalArgumentException> { verifySyncRecoveryBaseline(journal.records.values.toList(), files) }
                val verified = journal.records.values.filter { it.phase == SyncRecoveryPhase.Verified }.map { it.finalPath }.toSet()
                val completedMoves = files.moves.size
                files.beforeMove = { _, _ -> }; files.afterMove = { _, _ -> }
                val resumed = executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {})
                assertTrue(resumed.all { it.phase == SyncRecoveryPhase.Verified })
                assertTrue(files.moves.drop(completedMoves).none { it.second in verified }, "Previously verified copy moved again")
                val baseline = verifySyncRecoveryBaseline(resumed, files)
                assertEquals(ids.associateWith { versions.first() }, baseline.participants)
                for (id in ids) {
                    val retained = files.entries.filterKeys { it.startsWith("/$id/") && "/tmp-" !in it && "/old-" !in it }.values.toSet()
                    assertEquals(versions.toSet(), retained)
                }
                val beforeRepeat = files.moves.size
                executeSyncRecoveryGroup(resumed, files, journal::save, {})
                assertEquals(beforeRepeat, files.moves.size)
            }
        }
    }

    @Test fun emptyDirectoryCleanupIsJournaledAndRejectsArrivingContents() = runTest {
        for (arriving in listOf(false, true)) {
            val files = Files().apply {
                entries.clear()
                entries["/target/a"] = directory
                if (arriving) entries["/target/a/keep"] = old
            }
            val journal = Journal()
            val remove = record(false).copy(action = SyncRecoveryAction.Delete, temporaryPath = "", oldPath = "",
                oldState = directory, newState = null, stagedState = null, oldTree = mapOf("" to directory))
            if (arriving) {
                assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(remove), files, journal::save, {}) }
                assertEquals(old, files.entries["/target/a/keep"])
            } else {
                var lost = false
                val io = object : SyncRecoveryFileOperations by files {
                    override suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>) {
                        files.remove(endpoint, path, expected, tree)
                        if (!lost) { lost = true; error("delete acknowledgement lost") }
                    }
                }
                assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(remove), io, journal::save, {}) }
                val recovered = executeSyncRecoveryGroup(journal.records.values.toList(), io, journal::save, {})
                assertEquals(SyncRecoveryPhase.Verified, recovered.single().phase)
                assertFalse("/target/a" in files.entries)
            }
        }
    }

    @Test fun contentChangedWhileMovingIsRestoredButNeverAdoptedForTheOldPlan() = runTest {
        val files = Files()
        val journal = Journal()
        val edit = old.copy(contentSha256 = "c".repeat(64))
        var edited = false
        files.beforeMove = { from, _ -> if (from == "/target/a" && !edited) {
            edited = true
            files.entries[from] = edit
        } }
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record(false)), files, journal::save, {}) }
        assertEquals(edit, files.entries["/target/a"])
        assertEquals(fresh, files.entries["/target/tmp"])
        assertFalse("/target/old" in files.entries)
        assertEquals(SyncRecoveryPhase.Prepared, journal.records.values.single().phase)
        assertEquals(null, journal.records.values.single().restoredState)
        val moves = files.moves.size
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {}) }
        assertEquals(moves, files.moves.size)
        assertEquals(edit, files.entries["/target/a"])
    }

    @Test fun lostRollbackAcknowledgementCanRetryOnlyAfterExactRestorationProof() = runTest {
        for (changedAfterRestore in listOf(false, true)) {
            val files = Files()
            val journal = Journal()
            files.beforeMove = { from, _ -> if (from == "/target/tmp") error("placement failed") }
            files.afterMove = { from, _ -> if (from == "/target/old") error("rollback acknowledgement lost") }
            assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record()), files, journal::save, {}) }
            assertEquals(old, files.entries["/target/a"])
            assertEquals(fresh, files.entries["/target/tmp"])
            assertEquals(SyncRecoveryPhase.OldPreserved, journal.records.values.single().phase)
            files.beforeMove = { _, _ -> }
            files.afterMove = { _, _ -> }
            if (changedAfterRestore) {
                files.entries["/target/a"] = old.copy(modifiedAt = old.modifiedAt + 1)
                val moves = files.moves.size
                assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {}) }
                assertEquals(moves, files.moves.size)
                assertEquals(fresh, files.entries["/target/tmp"])
            } else {
                val result = executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {})
                assertEquals(fresh, files.entries["/target/a"])
                assertEquals(1, result.single().attempt)
                assertEquals(SyncRecoveryPhase.Verified, result.single().phase)
            }
        }
    }

    private val old = SyncEntryState(false, 10, 17_000, contentSha256 = "a".repeat(64))
    private val fresh = SyncEntryState(false, 20, 27_000, contentSha256 = "b".repeat(64))
    private val directory = SyncEntryState(true, -1, 0)
    private val endpoint = SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target")
    private fun record(recycle: Boolean = true) = SyncFileRecovery("replace", 1, "definition", "device", "group", "target",
        endpoint, "a", "/target/a", "/target/tmp", "/target/old", old, fresh, recycle, stagedState = fresh)

    private inner class Files : SyncRecoveryFileOperations {
        val entries = linkedMapOf("/target/a" to old, "/target/tmp" to fresh)
        val moves = mutableListOf<Pair<String, String>>()
        var beforeMove: (String, String) -> Unit = { _, _ -> }
        var afterMove: (String, String) -> Unit = { _, _ -> }
        var moveChangesTime = false
        override suspend fun read(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState?) = entries[path]
        override suspend fun tree(endpoint: SyncRecoveryEndpoint, path: String, expected: Map<String, SyncEntryState>) =
            entries.filterKeys { it == path || it.startsWith("$path/") }.mapKeys { it.key.removePrefix(path).trimStart('/') }
        override suspend fun ensureParent(endpoint: SyncRecoveryEndpoint, path: String) = Unit
        override suspend fun move(endpoint: SyncRecoveryEndpoint, from: String, to: String) {
            beforeMove(from, to)
            check(to !in entries) { "Destination exists" }
            check(from in entries) { "Source missing" }
            val moving = entries.filterKeys { it == from || it.startsWith("$from/") }
            moving.keys.forEach(entries::remove)
            for ((path, state) in moving) entries[to + path.removePrefix(from)] =
                if (moveChangesTime && !state.isDirectory) state.copy(modifiedAt = state.modifiedAt + 10_000) else state
            moves += from to to
            afterMove(from, to)
        }
        override suspend fun createDirectory(endpoint: SyncRecoveryEndpoint, path: String) {
            check(path !in entries)
            entries[path] = directory
        }
        override suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>) {
            check(syncEntryMatchesSnapshot(expected, entries[path]))
            if (expected.isDirectory) check(this.tree(endpoint, path, tree) == tree)
            entries.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach(entries::remove)
        }
    }

    private class Journal {
        val records = linkedMapOf<String, SyncFileRecovery>()
        var writes = 0
        var failAt = -1
        suspend fun save(items: List<SyncFileRecovery>) {
            if (++writes == failAt) error("database write failed")
            for (item in items) records[item.id] = item
        }
    }

    @Test fun overwritePreservesOldBeforePlacementAndOnlyCleansAfterTheGroupIsVerified() = runTest {
        for (recycle in listOf(true, false)) {
            val files = Files()
            val journal = Journal()
            val result = executeSyncRecoveryGroup(listOf(record(recycle)), files, journal::save, {})
            assertEquals(listOf("/target/a" to "/target/old", "/target/tmp" to "/target/a"), files.moves)
            assertEquals(fresh, files.entries["/target/a"])
            assertEquals(if (recycle) old else null, files.entries["/target/old"])
            assertEquals(SyncRecoveryPhase.Verified, result.single().phase)
            assertEquals(result.single(), journal.records.values.single())
        }
    }

    @Test fun lostMoveAcknowledgementsResumeFromVerifiedPathsWithoutCopyingAgain() = runTest {
        for (failedFrom in listOf("/target/a", "/target/tmp")) {
            val files = Files()
            val journal = Journal()
            var injected = false
            files.afterMove = { from, _ -> if (!injected && from == failedFrom) { injected = true; error("acknowledgement lost") } }
            assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record()), files, journal::save, {}) }
            files.afterMove = { _, _ -> }
            val resumed = executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {})
            assertEquals(fresh, files.entries["/target/a"])
            assertEquals(old, files.entries["/target/old"])
            assertEquals(2, files.moves.size)
            assertTrue(resumed.all { it.phase == SyncRecoveryPhase.Verified })
        }
    }

    @Test fun placementFailureAndCancellationRestoreOldWithoutLosingTheNewTemporaryVersion() = runTest {
        for (cancel in listOf(false, true)) {
            val files = Files()
            val journal = Journal()
            files.beforeMove = { from, _ -> if (from == "/target/tmp") {
                if (cancel) throw CancellationException("cancel") else error("placement failed")
            } }
            val failure = assertFailsWith<Exception> { executeSyncRecoveryGroup(listOf(record()), files, journal::save, {}) }
            assertEquals(cancel, failure is CancellationException)
            assertEquals(old, files.entries["/target/a"])
            assertEquals(fresh, files.entries["/target/tmp"])
            assertFalse("/target/old" in files.entries)
            val restored = journal.records.values.single()
            assertEquals(1, restored.attempt)
            assertEquals(SyncRecoveryPhase.Prepared, restored.phase)
            files.beforeMove = { _, _ -> }
            executeSyncRecoveryGroup(listOf(restored), files, journal::save, {})
            assertEquals(fresh, files.entries["/target/a"])
            assertEquals(old, files.entries["/target/old"])
        }
    }

    @Test fun arrivingFilesAreNeverOverwrittenAndRollbackFailureKeepsAllRecoveryPaths() = runTest {
        for (occupied in listOf(true, false)) {
            val files = Files()
            val journal = Journal()
            val arriving = old.copy(contentSha256 = "c".repeat(64))
            files.beforeMove = { from, _ ->
                if (from == "/target/tmp") {
                    if (occupied) files.entries["/target/a"] = arriving
                    error("placement failed")
                }
                if (!occupied && from == "/target/old") error("rollback failed")
            }
            assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record()), files, journal::save, {}) }
            assertEquals(if (occupied) arriving else null, files.entries["/target/a"])
            assertEquals(old, files.entries["/target/old"])
            assertEquals(fresh, files.entries["/target/tmp"])
            assertEquals(SyncRecoveryPhase.OldPreserved, journal.records.values.single().phase)
        }
    }

    @Test fun databaseFailureBeforeOldMoveAfterOldMoveOrAfterPlacementAlwaysKeepsVersions() = runTest {
        for (failure in listOf(1, 2, 3)) {
            val files = Files()
            val journal = Journal().apply { failAt = failure }
            assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record()), files, journal::save, {}) }
            assertTrue(files.entries.values.any { it.contentSha256 == old.contentSha256 })
            assertTrue(files.entries.values.any { it.contentSha256 == fresh.contentSha256 })
            if (journal.records.isNotEmpty()) {
                journal.failAt = -1
                executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {})
                assertEquals(fresh, files.entries["/target/a"])
                assertEquals(old, files.entries["/target/old"])
            } else assertEquals(old, files.entries["/target/a"])
        }
    }

    @Test fun everyConflictVersionMustBeStagedBeforeAnyOriginalChanges() = runTest {
        val files = Files()
        val journal = Journal()
        val missing = record().copy(id = "conflict", relativePath = "a (conflict)", finalPath = "/target/a (conflict)",
            temporaryPath = "/target/missing", oldPath = "", oldState = null, action = SyncRecoveryAction.Copy, conflictCopy = true)
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record(), missing), files, journal::save, {}) }
        assertTrue(files.moves.isEmpty())
        assertTrue(journal.records.isEmpty())
        assertEquals(old, files.entries["/target/a"])
    }

    @Test fun directoryChangesWhileMovingAreRestoredAndNewChildrenRemainProtectedOnRetry() = runTest {
        val files = Files()
        files.entries["/target/a"] = directory
        files.entries["/target/a/original"] = old
        val journal = Journal()
        var added = false
        files.beforeMove = { from, _ -> if (from == "/target/a" && !added) {
            added = true; files.entries["/target/a/new"] = fresh
        } }
        val overwrite = record().copy(oldState = directory, oldTree = mapOf("" to directory, "original" to old))
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(overwrite), files, journal::save, {}) }
        assertEquals(directory, files.entries["/target/a"])
        assertEquals(old, files.entries["/target/a/original"])
        assertEquals(fresh, files.entries["/target/a/new"])
        assertEquals(fresh, files.entries["/target/tmp"])
        val movesBefore = files.moves.size
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(journal.records.values.toList(), files, journal::save, {}) }
        assertEquals(movesBefore, files.moves.size)
        assertEquals(fresh, files.entries["/target/a/new"])
    }

    @Test fun confirmedMoveMetadataIsRecordedAndStateChangesStillAbortBeforeMutation() = runTest {
        val files = Files().apply { moveChangesTime = true }
        val journal = Journal()
        val result = executeSyncRecoveryGroup(listOf(record(false)), files, journal::save, {})
        assertEquals(37_000, result.single().placedState!!.modifiedAt)
        assertEquals(27_000, result.single().oldStoredState!!.modifiedAt)
        assertEquals(fresh.contentSha256, files.entries["/target/a"]!!.contentSha256)
        val unchanged = Files()
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record()), unchanged, Journal()::save, { error("definition changed") }) }
        assertTrue(unchanged.moves.isEmpty())
    }

    @Test fun obsoleteRecoveryOnlyRestoresMissingOriginalsAndNeverReplacesArrivingContent() = runTest {
        for (arriving in listOf(false, true)) {
            val files = Files()
            files.entries.remove("/target/a")
            files.entries["/target/old"] = old
            if (arriving) files.entries["/target/a"] = fresh
            val journal = Journal()
            val pending = record().copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = old)
            val restored = restoreSyncRecoveryOriginals(listOf(pending), files, journal::save).single()
            assertEquals(if (arriving) fresh else old, files.entries["/target/a"])
            assertEquals(fresh, files.entries["/target/tmp"])
            if (arriving) {
                assertTrue(files.moves.isEmpty())
                assertEquals(old, files.entries["/target/old"])
                assertEquals(pending, restored)
            } else {
                assertEquals(1, restored.attempt)
                assertEquals(SyncRecoveryPhase.Prepared, restored.phase)
                assertEquals(old, restored.restoredState)
                assertFalse("/target/old" in files.entries)
            }
        }
    }

    @Test fun obsoleteRecoveryDoesNotUndoAnAlreadyVerifiedReplacement() = runTest {
        val files = Files()
        files.entries.remove("/target/a")
        files.entries["/target/old"] = old
        val verified = record().copy(phase = SyncRecoveryPhase.Verified, placedState = fresh, oldStoredState = old)
        assertEquals(listOf(verified), restoreSyncRecoveryOriginals(listOf(verified), files, Journal()::save))
        assertTrue(files.moves.isEmpty())
        assertEquals(old, files.entries["/target/old"])
        assertFalse("/target/a" in files.entries)
    }

    @Test fun sourceCleanupRequiresEveryCurrentBackupAndRetainsSourceOnFailureOrCancellation() = runTest {
        val sourceEndpoint = endpoint.copy(rootPath = "/source")
        val otherEndpoint = endpoint.copy(rootPath = "/other")
        val cleanup = record(false).copy(id = "clean", participant = "source", endpoint = sourceEndpoint,
            finalPath = "/source/a", temporaryPath = "", oldPath = "", oldState = fresh, newState = null,
            stagedState = null, action = SyncRecoveryAction.Delete,
            participantLocations = mapOf("source" to SyncRecoveryLocation(sourceEndpoint, "/source/a"),
                "target" to SyncRecoveryLocation(endpoint, "/target/a"), "other" to SyncRecoveryLocation(otherEndpoint, "/other/a")),
            expectedParticipants = mapOf("source" to fresh, "target" to fresh, "other" to fresh),
            backupPaths = mapOf("target" to "a", "other" to "a"), expectedBackupStates = mapOf("target" to fresh, "other" to fresh))
        for (fault in listOf("missing", "size", "time", "source", "delete", "cancel")) {
            val files = Files().apply {
                entries["/source/a"] = fresh
                entries["/target/a"] = fresh
                entries["/other/a"] = fresh
                when (fault) {
                    "missing" -> entries.remove("/other/a")
                    "size" -> entries["/other/a"] = fresh.copy(size = fresh.size + 1)
                    "time" -> entries["/other/a"] = fresh.copy(modifiedAt = fresh.modifiedAt + 1)
                    "source" -> entries["/source/a"] = fresh.copy(modifiedAt = fresh.modifiedAt + 1)
                }
            }
            val io = object : SyncRecoveryFileOperations by files {
                override suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>) {
                    if (fault == "cancel") throw CancellationException("cancel")
                    if (fault == "delete") error("delete failed")
                    files.remove(endpoint, path, expected, tree)
                }
            }
            val journal = Journal()
            assertFailsWith<Exception>(fault) { executeSyncRecoveryGroup(listOf(cleanup), io, journal::save,
                { verifySyncBackupCopies(it, io) }) }
            assertTrue("/source/a" in files.entries, fault)
            assertTrue("/target/a" in files.entries, fault)
        }
        val valid = Files().apply { entries["/source/a"] = fresh; entries["/target/a"] = fresh; entries["/other/a"] = fresh }
        executeSyncRecoveryGroup(listOf(cleanup), valid, Journal()::save, { verifySyncBackupCopies(it, valid) })
        assertFalse("/source/a" in valid.entries)
        assertEquals(fresh, valid.entries["/target/a"])
        assertEquals(fresh, valid.entries["/other/a"])
        val lost = Files().apply { entries["/source/a"] = fresh; entries["/target/a"] = fresh; entries["/other/a"] = fresh }
        val journal = Journal()
        var deletes = 0
        val uncertain = object : SyncRecoveryFileOperations by lost {
            override suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>) {
                lost.remove(endpoint, path, expected, tree)
                if (++deletes == 1) error("delete acknowledgement lost")
            }
        }
        assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(cleanup), uncertain, journal::save,
            { verifySyncBackupCopies(it, uncertain) }) }
        assertFalse("/source/a" in lost.entries)
        executeSyncRecoveryGroup(journal.records.values.toList(), uncertain, journal::save, { verifySyncBackupCopies(it, uncertain) })
        assertEquals(1, deletes)
        assertEquals(SyncRecoveryPhase.Verified, journal.records.values.single().phase)
    }
}
