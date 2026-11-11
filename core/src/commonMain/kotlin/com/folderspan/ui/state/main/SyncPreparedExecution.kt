package com.folderspan.ui.state.main

import com.folderspan.service.sync.beforeSyncConditionFile
import com.folderspan.service.sync.withSyncConditionPath

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.service.operation.reportOperationFailure
import com.folderspan.service.operation.reportOperationSkipped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import strings.AppStrings
import com.folderspan.utils.LogKit

/** Applies only the prepared operations. Consent is the exact read-only snapshot accepted by the user. */
internal suspend fun SyncState.executeSyncPreparedRun(
    prepared: SyncPreparedRun,
    operation: Task,
    trigger: String,
    consent: SyncPreparedRun? = null,
    approvedDeletions: Set<SyncPendingDelete> = emptySet(),
): SyncRunRecord {
    require(prepared.task.mode != SyncMode.Update) { "Update mode retains its existing executor" }
    val started = maxOf(nowMillis(), recordsFor(prepared.task.id).maxOfOrNull { it.runId }?.plus(1) ?: 0)
    val task = prepared.task
    val fingerprint = syncDefinitionFingerprint(task)
    val store = checkNotNull(localStore)
    val protection = protectSyncRunPlan(prepared, approvedDeletions)
    check(protection.unexpectedlyEmptyScopes.isEmpty() || consent === prepared) { "Empty synchronization scope requires preview confirmation" }
    val bindings = linkedMapOf<SyncRecoveryEndpoint, DiskBase>()
    fun bind(endpoint: SyncRecoveryEndpoint) {
        val current = prepared.endpoints.first { it.type == endpoint.type && it.ref == endpoint.ref }
        check(current.connectionBinding == endpoint.connectionBinding) { "Synchronization endpoint binding changed" }
        bindings[endpoint] = current.desk
    }
    for (endpoint in prepared.endpoints) {
        if (endpoint.id == "source" && prepared.sourceScope.selectionTypes.isNotEmpty()) {
            for (alias in prepared.sourceScope.selectionTypes.keys) bind(prepared.recoveryLocation(endpoint.id, alias).first)
        } else bind(SyncRecoveryEndpoint(endpoint.type, endpoint.ref, endpoint.root.path, endpoint.root.storageReference, endpoint.connectionBinding))
    }
    for (path in prepared.preparation.plan.paths) for (id in path.expected.keys) bind(prepared.recoveryLocation(id, path.relativePath).first)
    val io = SyncRecoveryEndpointOperations(endpointFiles, operation, bindings) { desk, source, destination ->
        ensureOperationRunning(operation.key)
        beforeSyncConditionFile()
        fileState.runCopyAcrossEndpoints(operation, source, destination, desk, desk)
    }
    suspend fun validate() {
        check(isTaskOwnedByCurrentDevice(task) && getTask(task.id)?.let(::syncPreviewFingerprint) == prepared.previewFingerprint) {
            "Synchronization definition changed"
        }
        val local = store.localState(task.id)
        check(local != null && local.confirms(task) && fixedSyncTargetsCurrent(task, local.fixedTargets, local.fixedSourceBinding)) { "Synchronization preview is not confirmed" }
        check(preparedSyncEndpointsCurrent(prepared)) { "Synchronization endpoint changed" }
    }
    validate()
    ensureOperationRunning(operation.key)
    store.replacePendingDeletions(task.id, fingerprint, started, protection.pendingDeletions)

    val items = mutableListOf<SyncRunItem>()
    val details = SyncRunDetails(task, started, store)
    val labels = prepared.endpoints.associate { it.id to it.label }
    val recoveryGroups = mutableMapOf<String, String>()
    suspend fun recoveryRecords(groupId: String): List<SyncFileRecovery> = try { store.recoveryGroup(task.id, groupId) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { LogKit.w(sanitizeSyncMessage(error.message)); emptyList() }
    suspend fun pendingRecords(path: SyncPathPlan): List<SyncFileRecovery> =
        recoveryGroups.remove(path.relativePath)?.let { recoveryRecords(it) }.orEmpty()
    val failedDirectories = mutableSetOf<String>()
    val accepted = mutableSetOf<String>()
    val mutex = Mutex()
    var success = 0
    var failure = 0
    var skipped = 0
    var unchanged = 0
    var copied = 0
    var renamed = 0
    var directories = 0
    var bytes = 0L
    var canceled = false
    val paths = protection.plan.paths
    taskState.putValue(operation, "progressMax", paths.size.toString())
    suspend fun progress() { taskState.putValue(operation, "progressCur", (success + failure + skipped).toString()) }
    suspend fun failed(path: SyncPathPlan, error: Exception) = mutex.withLock {
        failure++
        if (path.operations.any { it.kind == SyncOperationKind.CreateDirectory || it.expectedTarget?.isDirectory == true }) failedDirectories += path.relativePath
        appendRunItem(items, path.relativePath, false, sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed))
        details.add(syncRunPathItems(path, pendingRecords(path), labels, success = false, message = sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)))
        progress()
    }
    suspend fun recordCommitted(batch: List<Pair<SyncPathPlan, List<SyncFileRecovery>>>) = mutex.withLock {
        for ((path, records) in batch) {
            details.add(syncRunPathItems(path, records, labels))
            recoveryGroups.remove(path.relativePath)
            success++
            accepted += path.relativePath
            if (path.operations.isEmpty()) unchanged++
            for (planned in path.operations) when (planned.kind) {
                SyncOperationKind.Copy -> { copied++; bytes += planned.expectedSource?.size?.coerceAtLeast(0) ?: 0 }
                SyncOperationKind.Overwrite -> { copied++; bytes += planned.expectedSource?.size?.coerceAtLeast(0) ?: 0 }
                SyncOperationKind.ConflictCopy -> { renamed++; bytes += planned.expectedSource?.size?.coerceAtLeast(0) ?: 0 }
                SyncOperationKind.CreateDirectory -> directories++
                else -> Unit
            }
            if (path.operations.isNotEmpty()) appendRunItem(items, path.relativePath, true, AppStrings.ui_synchronization_completed)
        }
        progress()
    }
    suspend fun baseline(path: SyncPathPlan, records: List<SyncFileRecovery>, verifier: SyncRecoveryFileOperations): SyncBaseline? {
        if (path.removedWithParent != null) {
            check(path.removedWithParent in accepted) { "Parent replacement was not committed" }
            return null
        }
        if (records.isNotEmpty()) return verifySyncRecoveryBaseline(records, verifier)
        val states = path.expected.mapValues { (id, expected) ->
            val (endpoint, location) = prepared.recoveryLocation(id, path.relativePath)
            verifier.read(endpoint, location.path, expected).also { check(syncEntryMatchesSnapshot(expected, it)) { "Synchronization result changed before baseline acceptance" } }
        }
        val previous = prepared.preparation.baselines[path.relativePath]
        return SyncBaseline(path.relativePath, states, previous?.backupCopies.orEmpty(), states["source"]?.storageReference ?: previous?.selectedSourceReference)
    }
    suspend fun commit(completed: List<Pair<SyncPathPlan, List<SyncFileRecovery>>>) {
        val listings = mutableMapOf<Pair<SyncRecoveryEndpoint, String>, List<FileSimpleInfo>>()
        val verifier = object : SyncRecoveryFileOperations by io {
            override suspend fun read(endpoint: SyncRecoveryEndpoint, path: String, expected: SyncEntryState?): SyncEntryState? {
                val desk = bindings.getValue(endpoint)
                val separator = endpointFiles.endpointSeparator(endpoint.type, desk)
                if (normalizePathForCompare(path, separator) == normalizePathForCompare(endpoint.rootPath, separator)) return io.read(endpoint, path, expected)
                val parent = parentPathOf(path, separator)
                val children = listings.getOrPut(endpoint to parent) { io.children(endpoint, parent) }
                val fresh = children.singleOrNull { it.name == fileNameOf(path, separator) } ?: return null
                check(!fresh.isSymbolicLink) { "Synchronization result became a symbolic link" }
                val actual = (if (fresh.isDirectory) fresh else syncComparisonTargetFile(desk, fresh)).syncEntryState()
                if (expected?.contentSha256.isNullOrEmpty()) return actual
                if (expected.modifiedAt <= 0) return io.read(endpoint, path, expected)
                // The already verified content proof remains bound to its exact metadata and stable reference.
                return if (syncEntryMatchesSnapshot(expected.copy(contentSha256 = ""), actual)) actual.copy(contentSha256 = expected.contentSha256) else actual
            }
        }
        for (batch in completed.chunked(500)) {
            validate()
            val verified = mutableListOf<Pair<SyncPathPlan, List<SyncFileRecovery>>>()
            val baselines = mutableListOf<SyncBaseline>()
            val removed = mutableSetOf<String>()
            for ((path, records) in batch) try {
                val actual = baseline(path, records, verifier)
                if (actual == null || actual.participants.values.all { it == null }) removed += path.relativePath else {
                    rememberSyncSourceReference(task, actual)
                    baselines += actual
                }
                verified += path to records
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failed(path, error) }
            if (verified.isEmpty()) continue
            validate()
            store.commitVerified(task.id, fingerprint, baselines, removed,
                verified.flatMapTo(mutableSetOf()) { it.second.map { record -> record.id } })
            recordCommitted(verified)
        }
    }
    suspend fun process(group: List<SyncPathPlan>, parallelism: Int) {
        val completed = mutableListOf<Pair<SyncPathPlan, List<SyncFileRecovery>>>()
        val tuning = syncCopyOperationTuning(prepared.endpoints.map { it.desk }, parallelism)
        try {
            processItemsAdaptive(group, tuning.config(),
                ensureRunning = { ensureOperationRunning(operation.key) },
                dynamicMaxParallelismProvider = tuning::currentMaxParallelism,
                onActiveParallelismChanged = { taskState.putRuntimeParallelCount(operation, it) }) { path ->
                withSyncConditionPath {
                    beforeSyncConditionFile()
                    val blocked = mutex.withLock { path.skipped || syncPathProtected(path.relativePath, failedDirectories) ||
                        path.removedWithParent?.let { it !in accepted } == true }
                    if (blocked || path.operations.isEmpty()) reportOperationSkipped()
                    if (blocked) mutex.withLock {
                        skipped++
                        if (path.conflict) appendRunItem(items, path.relativePath, true, AppStrings.sync_conflict_skipped)
                        details.add(syncRunPathItems(path.copy(skipped = true, operations = emptyList()), emptyList(), labels,
                            message = AppStrings.sync_protected_path_skipped))
                        progress()
                    }
                    else try {
                        validate()
                        val result = if (path.operations.isEmpty()) emptyList() else executeSyncPathRecovery(prepared, path, operation, started) { id -> mutex.withLock { recoveryGroups[path.relativePath] = id } }
                        mutex.withLock { completed += path to result }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (changed: SyncPlannedStateChangedException) {
                        reportOperationSkipped()
                        mutex.withLock {
                        skipped++
                        if (path.operations.any { it.kind == SyncOperationKind.CreateDirectory || it.expectedTarget?.isDirectory == true }) failedDirectories += path.relativePath
                        appendRunItem(items, path.relativePath, true, sanitizeSyncMessage(changed.message))
                        details.add(SyncRunItem(path.relativePath, true, sanitizeSyncMessage(changed.message), kind = SyncRunItemKind.Skip))
                        details.add(pendingRecords(path).map { it.runRecoveryItem() })
                        progress()
                    } }
                    catch (error: Exception) { reportOperationFailure(); failed(path, error) }
                }
            }
        } finally {
            // Writers have joined before verified results are committed, including the final short batch on cancellation.
            withContext(NonCancellable) { withTimeout(30_000) { commit(completed) } }
        }
        ensureOperationRunning(operation.key)
    }
    try {
        cleanupSyncTaskArtifacts(task, bindings.mapValues { (endpoint, desk) -> endpointFiles.endpointSeparator(endpoint.type, desk) },
            io, started, { store.recoveries() }, ensureRunning = { ensureOperationRunning(operation.key); validate() })
        val creates = paths.filter { !it.skipped && it.operations.any { op -> op.kind == SyncOperationKind.CreateDirectory } }.toSet()
        val cleanDirectories = paths.filter { !it.skipped && it.operations.any { op -> op.kind == SyncOperationKind.CleanEmptyDirectory } }.toSet()
        process(creates.sortedWith(compareBy<SyncPathPlan> { it.relativePath.count { c -> c == '/' } }.thenBy { it.relativePath }), 1)
        val deletes = paths.filter { !it.skipped && it.operations.any { op -> op.kind == SyncOperationKind.Delete } }.toSet()
        val ordinary = paths.filter { it !in creates && it !in cleanDirectories && it !in deletes && it.removedWithParent == null }
        for (group in ordinary.groupBy { it.relativePath.substringBeforeLast('/', "") }.values) process(group, task.parallelism)
        for ((_, group) in deletes.groupBy { it.relativePath.count { c -> c == '/' } }.entries.sortedByDescending { it.key }) process(group, task.parallelism)
        process(paths.filter { it.removedWithParent != null }, 1)
        process(cleanDirectories.sortedWith(compareByDescending<SyncPathPlan> { it.relativePath.count { c -> c == '/' } }.thenBy { it.relativePath }), 1)
        validate()
        if (paths.isEmpty()) store.commitVerified(task.id, fingerprint, emptyList())
    } catch (_: CancellationException) { canceled = true }
    catch (error: Exception) {
        failure = maxOf(failure, paths.size - success - skipped)
        val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)
        appendRunItem(items, "", false, message)
        details.add(SyncRunItem("", false, message))
    }
    withContext(NonCancellable) {
        for (groupId in recoveryGroups.values) details.add(recoveryRecords(groupId).map { it.runRecoveryItem() })
    }
    val status = when {
        canceled -> SyncRunStatus.Canceled
        failure > 0 && success > 0 -> SyncRunStatus.PartialSuccess
        failure > 0 -> SyncRunStatus.Failure
        else -> SyncRunStatus.Success
    }
    return details.finish(SyncRunRecord(started, task.id, trigger, started, nowMillis(), status, paths.size, success, failure,
        if (canceled) AppStrings.ui_sync_canceled else if (failure > 0) AppStrings.ui_sync_failed else if (success == unchanged) AppStrings.ui_no_changes else AppStrings.ui_synchronization_completed,
        items, policySnapshot = task, copiedCount = copied, renamedCount = renamed, createdDirectoryCount = directories,
        unchangedCount = unchanged, skippedCount = skipped, transferredBytes = bytes, transferDurationMillis = (nowMillis() - started).coerceAtLeast(0),
        unsavedFailureCount = failure, participants = prepared.runParticipants()))
}
