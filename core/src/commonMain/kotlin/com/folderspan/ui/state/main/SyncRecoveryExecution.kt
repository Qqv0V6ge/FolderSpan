package com.folderspan.ui.state.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface SyncRecoveryFileOperations {
    suspend fun read(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState?): SyncEntryState?
    suspend fun tree(endpoint: SyncRecoveryEndpoint, path: String, expected: Map<String, SyncEntryState>): Map<String, SyncEntryState>
    suspend fun ensureParent(endpoint: SyncRecoveryEndpoint, path: String)
    suspend fun move(endpoint: SyncRecoveryEndpoint, from: String, to: String)
    suspend fun createDirectory(endpoint: SyncRecoveryEndpoint, path: String)
    suspend fun remove(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState, tree: Map<String, SyncEntryState>)
}

private fun sameRecoveryContent(expected: SyncEntryState, actual: SyncEntryState?): Boolean {
    if (actual == null || expected.isDirectory != actual.isDirectory) return false
    if (expected.isDirectory) return true
    if (expected.size < 0 || expected.size != actual.size) return false
    return if (expected.contentSha256.isNotEmpty()) expected.contentSha256 == actual.contentSha256
        else expected.modifiedAt > 0 && expected.modifiedAt == actual.modifiedAt
}

private fun sameRecoveryTree(expected: Map<String, SyncEntryState>, actual: Map<String, SyncEntryState>): Boolean =
    expected.keys == actual.keys && expected.all { (path, state) -> syncEntryMatchesSnapshot(state, actual[path]) }

private fun sameRecoveryIdentity(expected: SyncEntryState, actual: SyncEntryState): Boolean =
    expected.storageReference?.id.isNullOrEmpty() || expected.storageReference.id == actual.storageReference?.id

private fun sameMovedTree(expected: Map<String, SyncEntryState>, actual: Map<String, SyncEntryState>): Boolean =
    expected.keys == actual.keys && expected.all { (path, state) -> actual[path]?.let {
        sameRecoveryIdentity(state, it) && state.isDirectory == it.isDirectory && (state.isDirectory || state.size == it.size &&
            (state.contentSha256.isEmpty() || state.contentSha256 == it.contentSha256))
    } == true }

private fun SyncFileRecovery.withRestoredOriginal(state: SyncEntryState, tree: Map<String, SyncEntryState>) =
    copy(phase = SyncRecoveryPhase.Prepared, attempt = attempt + 1, restoredState = state, restoredTree = tree,
        oldStoredState = null, oldStoredTree = emptyMap(), placedState = null)

/** All copies are staged before this coordinator moves any original; bytes still use the shared copy executor. */
internal suspend fun executeSyncRecoveryGroup(
    initial: List<SyncFileRecovery>,
    io: SyncRecoveryFileOperations,
    save: suspend (List<SyncFileRecovery>) -> Unit,
    validateCurrent: suspend (List<SyncFileRecovery>) -> Unit,
    ensureRunning: suspend () -> Unit = {},
): List<SyncFileRecovery> {
    require(initial.isNotEmpty() && initial.map { it.id }.toSet().size == initial.size)
    require(initial.none { it.action == SyncRecoveryAction.Delete && it.oldState?.isDirectory == true &&
        (it.oldPath.isNotEmpty() || it.recycleEnabled || it.oldTree.keys != setOf("")) }) {
        "Directory deletion requires a verified empty directory"
    }
    require(initial.none { it.oldState != null && it.oldPath.isEmpty() && (it.action != SyncRecoveryAction.Delete || it.recycleEnabled) }) {
        "Old versions require a reserved recovery path"
    }
    val first = initial.first()
    require(initial.all { it.taskId == first.taskId && it.fingerprint == first.fingerprint && it.groupId == first.groupId && it.logicalPath == first.logicalPath })
    val records = initial.associateByTo(linkedMapOf()) { it.id }
    suspend fun running() {
        currentCoroutineContext().ensureActive()
        ensureRunning()
    }
    suspend fun persist(record: SyncFileRecovery): SyncFileRecovery {
        records[record.id] = record
        save(listOf(record))
        return record
    }
    suspend fun validate() { running(); validateCurrent(records.values.toList()) }
    suspend fun actual(record: SyncFileRecovery, path: String, expected: SyncEntryState?) =
        io.read(record.endpoint, path, expected)

    // A staged version can already be at its final path after an interrupted move acknowledgement.
    for (record in initial) if (record.action in listOf(SyncRecoveryAction.Copy, SyncRecoveryAction.Overwrite)) {
        running()
        val staged = requireNotNull(record.stagedState) { "Staged copy has no verified state" }
        require(!staged.isDirectory && staged.contentSha256.length == 64) { "Staged copy has no content proof" }
        val temporary = actual(record, record.temporaryPath, staged)
        val available = temporary?.let { syncEntryMatchesSnapshot(staged, it) }
            ?: sameRecoveryContent(staged, actual(record, record.finalPath, staged))
        check(available) { "Staged version cannot be verified: ${record.temporaryPath}" }
    }
    validate()
    save(initial)
    val ordered = initial.sortedWith(compareBy<SyncFileRecovery> {
        when {
            it.action == SyncRecoveryAction.CreateDirectory -> 0
            it.conflictCopy -> 1
            it.action == SyncRecoveryAction.Delete -> 3
            else -> 2
        }
    }.thenBy { if (it.action == SyncRecoveryAction.Delete) -it.relativePath.count { c -> c == '/' } else it.relativePath.count { c -> c == '/' } })

    for (original in ordered) {
        var record = records.getValue(original.id)
        try {
            validate()
            if (record.phase == SyncRecoveryPhase.OldPreserved && record.oldStoredState != null) {
                val expected = record.restoredState ?: record.oldState
                val restored = actual(record, record.finalPath, expected)
                if (expected != null && syncEntryMatchesSnapshot(expected, restored) &&
                    actual(record, record.oldPath, record.oldStoredState) == null) {
                    val expectedTree = record.restoredTree.ifEmpty { record.oldTree }
                    val tree = if (expected.isDirectory) io.tree(record.endpoint, record.finalPath, expectedTree) else emptyMap()
                    check(!expected.isDirectory || sameRecoveryTree(expectedTree, tree)) { "Restored directory changed after interruption" }
                    record = persist(record.withRestoredOriginal(checkNotNull(restored), tree))
                }
            }
            if (record.phase == SyncRecoveryPhase.Prepared) {
                val expectedOld = record.restoredState ?: record.oldState
                val oldTree = if (record.restoredTree.isEmpty()) record.oldTree else
                    record.oldTree.mapValues { (path, state) -> record.restoredTree[path] ?: state }
                val existing = actual(record, record.finalPath, expectedOld)
                if (expectedOld != null) {
                    if (syncEntryMatchesSnapshot(expectedOld, existing)) {
                        if (expectedOld.isDirectory) {
                            check(sameRecoveryTree(oldTree, io.tree(record.endpoint, record.finalPath, oldTree))) { "Directory contents changed" }
                        }
                        validate()
                        if (record.action == SyncRecoveryAction.Delete && record.oldPath.isEmpty()) {
                            io.remove(record.endpoint, record.finalPath, expectedOld, oldTree)
                            check(actual(record, record.finalPath, null) == null) { "Deleted path is still present" }
                            record = record.copy(phase = SyncRecoveryPhase.Placed)
                            persist(record)
                        } else {
                            check(actual(record, record.oldPath, expectedOld) == null) { "Old-version path is occupied" }
                            io.ensureParent(record.endpoint, record.oldPath)
                            validate()
                            val metadata = if (expectedOld.modifiedAt > 0) expectedOld.copy(contentSha256 = "") else expectedOld
                            check(syncEntryMatchesSnapshot(metadata, actual(record, record.finalPath, metadata))) { "Original changed before moving" }
                            io.move(record.endpoint, record.finalPath, record.oldPath)
                            val stored = checkNotNull(actual(record, record.oldPath, expectedOld)) { "Old version is missing after move" }
                            val storedTree = if (stored.isDirectory) io.tree(record.endpoint, record.oldPath, oldTree) else emptyMap()
                            record = record.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = stored, oldStoredTree = storedTree)
                            records[record.id] = record
                            check(sameRecoveryIdentity(expectedOld, stored) && sameRecoveryContent(expectedOld, stored)) { "Old-version move verification failed" }
                            check(!stored.isDirectory || sameMovedTree(oldTree, storedTree)) { "Directory changed while moving its old version" }
                            check(actual(record, record.finalPath, null) == null) { "Move left the original path occupied" }
                            persist(record)
                        }
                    } else if (existing == null && record.action == SyncRecoveryAction.Delete && record.oldPath.isEmpty()) {
                        record = record.copy(phase = SyncRecoveryPhase.Placed)
                        persist(record)
                    } else {
                        val stored = actual(record, record.oldPath, expectedOld)
                        check(stored != null && sameRecoveryIdentity(expectedOld, stored) && sameRecoveryContent(expectedOld, stored)) {
                            "Interrupted old-version move is uncertain: ${record.oldPath}"
                        }
                        val storedTree = if (expectedOld.isDirectory) io.tree(record.endpoint, record.oldPath, oldTree) else emptyMap()
                        check(!expectedOld.isDirectory || sameMovedTree(oldTree, storedTree)) { "Interrupted directory move is uncertain" }
                        check(existing == null || record.stagedState?.let { sameRecoveryContent(it, existing) } == true &&
                            actual(record, record.temporaryPath, record.stagedState) == null) { "Original path changed after interruption" }
                        record = record.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = stored, oldStoredTree = storedTree)
                        persist(record)
                    }
                } else {
                    check(existing == null || record.stagedState?.let { sameRecoveryContent(it, existing) } == true &&
                        actual(record, record.temporaryPath, record.stagedState) == null) { "New destination is occupied" }
                    record = record.copy(phase = SyncRecoveryPhase.OldPreserved)
                    persist(record)
                }
            }
            if (record.phase == SyncRecoveryPhase.OldPreserved) {
                validate()
                val placed = when (record.action) {
                    SyncRecoveryAction.Delete -> {
                        check(actual(record, record.finalPath, null) == null) { "Deleted path was recreated" }
                        null
                    }
                    SyncRecoveryAction.CreateDirectory -> {
                        val existing = actual(record, record.finalPath, null)
                        if (existing == null) { io.ensureParent(record.endpoint, record.finalPath); io.createDirectory(record.endpoint, record.finalPath) }
                        actual(record, record.finalPath, null).also { check(it?.isDirectory == true) { "Directory creation could not be verified" } }
                    }
                    SyncRecoveryAction.Copy, SyncRecoveryAction.Overwrite -> {
                        val staged = checkNotNull(record.stagedState)
                        val existing = actual(record, record.finalPath, staged)
                        if (existing == null) {
                            check(syncEntryMatchesSnapshot(staged, actual(record, record.temporaryPath, staged))) { "Temporary version changed" }
                            io.ensureParent(record.endpoint, record.finalPath)
                            validate()
                            io.move(record.endpoint, record.temporaryPath, record.finalPath)
                        } else check(actual(record, record.temporaryPath, staged) == null && sameRecoveryContent(staged, existing)) { "Final destination is occupied" }
                        actual(record, record.finalPath, staged).also { check(sameRecoveryContent(staged, it)) { "Placed version failed verification" } }
                    }
                }
                record = record.copy(phase = SyncRecoveryPhase.Placed, placedState = placed)
                persist(record)
            }
            validate()
            when (record.action) {
                SyncRecoveryAction.Copy, SyncRecoveryAction.Overwrite -> check(record.placedState != null &&
                    sameRecoveryContent(checkNotNull(record.stagedState), record.placedState)) { "Placed copy has no verified state" }
                SyncRecoveryAction.CreateDirectory -> check(record.placedState?.isDirectory == true) { "Placed directory has no verified state" }
                SyncRecoveryAction.Delete -> check(record.placedState == null)
            }
            check(syncEntryMatchesSnapshot(record.placedState, actual(record, record.finalPath, record.placedState))) { "Placed path changed before verification" }
            record = record.copy(phase = SyncRecoveryPhase.Verified)
            persist(record)
        } catch (error: Exception) {
            // Rollback never replaces an arriving file, and failed rollback keeps the journal and both paths.
            if (record.phase == SyncRecoveryPhase.OldPreserved && record.oldStoredState != null) {
                withContext(NonCancellable) {
                    try {
                        withTimeout(30_000) {
                            val stored = record.oldStoredState
                            if (actual(record, record.finalPath, null) != null ||
                                !syncEntryMatchesSnapshot(stored, actual(record, record.oldPath, stored))) return@withTimeout
                            if (stored.isDirectory && !sameRecoveryTree(record.oldStoredTree, io.tree(record.endpoint, record.oldPath, record.oldStoredTree))) return@withTimeout
                            io.move(record.endpoint, record.oldPath, record.finalPath)
                            val restored = checkNotNull(actual(record, record.finalPath, stored))
                            check(sameRecoveryContent(stored, restored)) { "Restored version could not be verified" }
                            val restoredTree = if (restored.isDirectory) io.tree(record.endpoint, record.finalPath, record.oldStoredTree) else emptyMap()
                            check(!restored.isDirectory || sameMovedTree(record.oldStoredTree, restoredTree))
                            val original = checkNotNull(record.oldState)
                            if (sameRecoveryIdentity(original, restored) && sameRecoveryContent(original, restored) &&
                                (!restored.isDirectory || sameMovedTree(record.oldTree, restoredTree))) {
                                persist(record.withRestoredOriginal(restored, restoredTree))
                            }
                        }
                    } catch (rollback: Exception) { error.addSuppressed(rollback) }
                }
            }
            throw error
        }
    }
    validate()
    for (record in records.values) if (!record.recycleEnabled && record.oldStoredState != null && record.oldPath.isNotEmpty()) {
        validate()
        check(syncEntryMatchesSnapshot(record.placedState, actual(record, record.finalPath, record.placedState))) { "Placed version disappeared before cleanup" }
        val expected = record.oldStoredState
        val old = actual(record, record.oldPath, expected)
        if (old != null) {
            check(syncEntryMatchesSnapshot(expected, old)) { "Old temporary version changed" }
            io.remove(record.endpoint, record.oldPath, expected, record.oldStoredTree)
        }
    }
    return records.values.toList()
}

/** Restores a missing original after a definition change; it never completes an obsolete replacement. */
internal suspend fun restoreSyncRecoveryOriginals(
    records: List<SyncFileRecovery>,
    io: SyncRecoveryFileOperations,
    save: suspend (List<SyncFileRecovery>) -> Unit,
): List<SyncFileRecovery> {
    val result = mutableListOf<SyncFileRecovery>()
    for (original in records) {
        currentCoroutineContext().ensureActive()
        var record = original
        val expected = record.restoredState ?: record.oldState
        if (record.phase <= SyncRecoveryPhase.OldPreserved && expected != null && record.oldPath.isNotEmpty()) {
            val final = io.read(record.endpoint, record.finalPath, expected)
            if (!syncEntryMatchesSnapshot(expected, final)) {
                val stored = io.read(record.endpoint, record.oldPath, record.oldStoredState ?: expected)
                if (final == null && stored != null && sameRecoveryContent(expected, stored)) {
                    val expectedTree = record.oldStoredTree.ifEmpty { record.oldTree }
                    val storedTree = if (stored.isDirectory) io.tree(record.endpoint, record.oldPath, expectedTree) else emptyMap()
                    check(!stored.isDirectory || sameMovedTree(expectedTree, storedTree)) { "Recovery directory changed" }
                    record = record.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = stored, oldStoredTree = storedTree)
                    save(listOf(record))
                    io.ensureParent(record.endpoint, record.finalPath)
                    io.move(record.endpoint, record.oldPath, record.finalPath)
                    val restored = checkNotNull(io.read(record.endpoint, record.finalPath, stored))
                    check(sameRecoveryContent(stored, restored)) { "Restored original failed verification" }
                    val restoredTree = if (restored.isDirectory) io.tree(record.endpoint, record.finalPath, storedTree) else emptyMap()
                    check(!restored.isDirectory || sameMovedTree(storedTree, restoredTree))
                    record = record.withRestoredOriginal(restored, restoredTree)
                    save(listOf(record))
                }
            }
        }
        result += record
    }
    return result
}

/** Rechecks every participant and generated conflict copy before accepting the path as a whole. */
internal suspend fun verifySyncRecoveryBaseline(
    records: List<SyncFileRecovery>,
    io: SyncRecoveryFileOperations,
): SyncBaseline {
    val first = records.first()
    require(records.all { it.phase == SyncRecoveryPhase.Verified && it.logicalPath == first.logicalPath &&
        it.groupId == first.groupId && it.expectedParticipants == first.expectedParticipants &&
        it.participantLocations == first.participantLocations && it.backupPaths == first.backupPaths &&
        it.expectedBackupStates == first.expectedBackupStates && it.selectedSourceReference == first.selectedSourceReference }) {
        "Recovery group is incomplete"
    }
    require(first.expectedParticipants.isNotEmpty() && first.expectedParticipants.keys == first.participantLocations.keys) {
        "Recovery participant locations are unavailable"
    }
    val expected = first.expectedParticipants.toMutableMap()
    val changed = records.filter { it.relativePath == first.logicalPath }.mapTo(mutableSetOf()) { it.participant }
    for (record in records) if (record.newState?.contentSha256?.isNotEmpty() == true) {
        val source = first.participantLocations.entries.firstOrNull { (_, location) ->
            location.endpoint == record.sourceEndpoint && location.path == record.sourcePath
        }?.key
        if (source != null && source !in changed) expected[source] = record.newState
    }
    for (record in records) {
        check(syncEntryMatchesSnapshot(record.placedState, io.read(record.endpoint, record.finalPath, record.placedState))) {
            "Recovered output changed before baseline acceptance"
        }
        if (record.relativePath == first.logicalPath) expected[record.participant] = record.placedState
    }
    val states = linkedMapOf<String, SyncEntryState?>()
    for ((id, location) in first.participantLocations) {
        val state = io.read(location.endpoint, location.path, expected[id])
        check(syncEntryMatchesSnapshot(expected[id], state)) { "Recovered participant changed before baseline acceptance" }
        states[id] = state
    }
    val cleanup = first.backupPaths.isNotEmpty() && records.any { it.action == SyncRecoveryAction.Delete && it.participant == "source" }
    val backups = if (cleanup) verifySyncBackupCopies(records, io) else emptyMap()
    return SyncBaseline(first.logicalPath, states, backups, states["source"]?.storageReference
        ?: first.selectedSourceReference ?: first.expectedParticipants["source"]?.storageReference)
}

/** Every fixed destination must still have a verified current copy immediately before source cleanup. */
internal suspend fun verifySyncBackupCopies(records: List<SyncFileRecovery>, io: SyncRecoveryFileOperations): Map<String, SyncBackupCopy> {
    val first = records.first()
    val cleanup = records.single { it.action == SyncRecoveryAction.Delete && it.participant == "source" }
    val source = checkNotNull(cleanup.oldState)
    require(!source.isDirectory && source.size >= 0 && first.backupPaths.keys == first.participantLocations.keys - "source") {
        "Backup cleanup requires every fixed destination"
    }
    val result = linkedMapOf<String, SyncBackupCopy>()
    for ((id, relative) in first.backupPaths) {
        require(relative.split('/').all { it.isNotBlank() && it != "." && it != ".." } && relative.none { it in "\\\u0000" })
        val location = first.participantLocations.getValue(id)
        val copied = records.firstOrNull { it.participant == id && it.relativePath == relative }
        check(copied == null || copied.phase == SyncRecoveryPhase.Verified) { "Backup copy is not verified" }
        val expected = checkNotNull(copied?.placedState ?: first.expectedBackupStates[id]) { "Backup copy state is unavailable" }
        val path = joinPath(location.endpoint.rootPath, relative, if ('\\' in location.endpoint.rootPath) "\\" else "/")
        val actual = checkNotNull(io.read(location.endpoint, path, expected)) { "Backup copy disappeared" }
        check(!actual.isDirectory && actual.size == source.size && syncEntryMatchesSnapshot(expected, actual)) { "Backup copy changed" }
        result[id] = SyncBackupCopy(source, relative, actual)
    }
    // Re-read the source after all destinations, since either verification can take time.
    if (cleanup.phase <= SyncRecoveryPhase.Prepared) {
        val actual = io.read(cleanup.endpoint, cleanup.finalPath, source)
        check(actual == null || syncEntryMatchesSnapshot(source, actual)) { "Backup source changed before cleanup" }
    }
    return result
}
