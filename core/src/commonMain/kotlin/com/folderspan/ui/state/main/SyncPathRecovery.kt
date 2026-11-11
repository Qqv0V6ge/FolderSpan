package com.folderspan.ui.state.main

import com.folderspan.service.sync.beforeSyncConditionFile

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import kotlin.uuid.Uuid

internal class SyncPlannedStateChangedException(message: String) : IllegalStateException(message)

/** Executes the journaled part of a single plan path; baseline acceptance remains a separate verified transaction. */
internal suspend fun SyncState.executeSyncPathRecovery(
    prepared: SyncPreparedRun,
    path: SyncPathPlan,
    operation: Task,
    runId: Long,
    onRecoveryGroup: suspend (String) -> Unit = {},
): List<SyncFileRecovery> {
    val task = prepared.task
    val store = checkNotNull(localStore) { "Synchronization local state is unavailable" }
    val fingerprint = syncDefinitionFingerprint(task)
    val byId = prepared.endpoints.associateBy { it.id }
    val bindings = linkedMapOf<SyncRecoveryEndpoint, DiskBase>()

    fun location(id: String, relative: String, conflict: SyncPlannedOperation? = null): FileSimpleInfo {
        return prepared.recoveryLocation(id, relative, conflict).second
    }
    fun binding(id: String, relative: String, conflict: SyncPlannedOperation? = null): SyncRecoveryEndpoint {
        return prepared.recoveryLocation(id, relative, conflict).first.also { bindings[it] = byId.getValue(id).desk }
    }
    suspend fun validateDefinition() {
        ensureOperationRunning(operation.key)
        check(isTaskOwnedByCurrentDevice(task) && getTask(task.id)?.let(::syncPreviewFingerprint) == prepared.previewFingerprint) {
            "Synchronization definition changed"
        }
        val local = store.localState(task.id)
        check(local != null && local.fingerprint == fingerprint && (task.mode == SyncMode.Update || local.confirms(task)) &&
            fixedSyncTargetsCurrent(task, local.fixedTargets, local.fixedSourceBinding)) {
            "Synchronization preview is not confirmed"
        }
        check(preparedSyncEndpointsCurrent(prepared)) { "Synchronization endpoint changed" }
    }

    val operations = path.operations
    if (operations.isEmpty()) return emptyList()
    require(!path.skipped) { "Cannot execute a protected plan path" }
    val originalBindings = path.expected.keys.associateWith { binding(it, path.relativePath) }
    val participantLocations = originalBindings.mapValues { (id, endpoint) ->
        SyncRecoveryLocation(endpoint, location(id, path.relativePath).path)
    }
    val expectedBackupStates = path.backupPaths.mapNotNull { (id, relative) ->
        prepared.preparation.participants.first { it.id == id }.state(relative)?.let { id to it }
    }.toMap()
    val destinations = operations.associateWith { planned ->
        val conflict = planned.takeIf { it.kind == SyncOperationKind.ConflictCopy && it.participant == "source" }
        binding(planned.participant, planned.relativePath, conflict) to location(planned.participant, planned.relativePath, conflict)
    }
    val sources = operations.filter { it.sourceParticipant != null }.associateWith {
        val id = checkNotNull(it.sourceParticipant)
        binding(id, it.sourceRelativePath) to location(id, it.sourceRelativePath)
    }
    val io = SyncRecoveryEndpointOperations(endpointFiles, operation, bindings) { desk, source, destination ->
        ensureOperationRunning(operation.key)
        beforeSyncConditionFile()
        fileState.runCopyAcrossEndpoints(operation, source, destination, desk, desk)
    }
    suspend fun validateOriginals(except: Set<String> = emptySet()) {
        validateDefinition()
        for ((id, expected) in path.expected) if (id !in except) {
            if (!syncEntryMatchesSnapshot(expected, io.read(originalBindings.getValue(id), location(id, path.relativePath).path, expected)))
                throw SyncPlannedStateChangedException("Synchronization path changed after planning: ${path.relativePath}")
        }
    }
    validateOriginals()
    val groupId = Uuid.random().toString()
    val owner = syncRecycleOwnerKey(task)
    val records = mutableListOf<SyncFileRecovery>()
    for (planned in operations) {
        validateDefinition()
        val (destinationEndpoint, final) = destinations.getValue(planned)
        val destination = byId.getValue(planned.participant)
        val separator = endpointFiles.endpointSeparator(destination.type, destination.desk)
        check(normalizePathForCompare(final.path, separator) != normalizePathForCompare(destinationEndpoint.rootPath, separator)) {
            "A selected physical root cannot be replaced"
        }
        val id = Uuid.random().toString()
        val temporary = if (planned.kind in listOf(SyncOperationKind.Copy, SyncOperationKind.Overwrite, SyncOperationKind.ConflictCopy))
            joinPath(destinationEndpoint.rootPath, ".folderspan-tmp-${syncTaskKey(task)}-${nowMillis()}-$id-new", separator) else ""
        val permanent = planned.kind in listOf(SyncOperationKind.CleanSource, SyncOperationKind.CleanEmptyDirectory)
        val oldPath = if (planned.expectedTarget == null || permanent) "" else if (task.recycleEnabled)
            joinPath(destinationEndpoint.rootPath, ".folderspan-recycle/$owner/${task.id}/$runId-$groupId/${planned.relativePath}", separator)
        else joinPath(destinationEndpoint.rootPath, ".folderspan-tmp-${syncTaskKey(task)}-$id-old", separator)
        val old = io.verifiedState(destinationEndpoint, final.path)
        check(syncEntryMatchesSnapshot(planned.expectedTarget, old)) { "Synchronization destination changed" }
        val oldTree = if (old?.isDirectory == true) io.verifiedTree(destinationEndpoint, final.path) else emptyMap()
        if (planned.kind == SyncOperationKind.CleanEmptyDirectory) check(oldTree.keys == setOf("")) { "Directory is no longer empty" }
        else if (old?.isDirectory == true) {
            val participant = prepared.preparation.participants.first { it.id == planned.participant }
            val prefix = planned.relativePath + "/"
            val expectedTree = mapOf("" to checkNotNull(planned.expectedTarget)) + participant.scan.occupied.keys
                .filter { it.startsWith(prefix) }.associate { it.removePrefix(prefix) to checkNotNull(participant.state(it)) }
            if (oldTree.keys != expectedTree.keys || expectedTree.any { (path, state) -> !syncEntryMatchesSnapshot(state, oldTree[path]) })
                throw SyncPlannedStateChangedException("Directory contents changed after planning: ${planned.relativePath}")
        }
        val source = sources[planned]
        var staged: SyncEntryState? = null
        var sourceState = planned.expectedSource
        if (temporary.isNotEmpty()) {
            val (sourceEndpoint, sourceLocation) = checkNotNull(source)
            val sourceEnd = byId.getValue(checkNotNull(planned.sourceParticipant))
            check(syncEntryMatchesSnapshot(planned.expectedSource, io.read(sourceEndpoint, sourceLocation.path, planned.expectedSource))) {
                "Synchronization source changed"
            }
            val actualSource = endpointFiles.readPathInfo(sourceEnd.type, sourceEnd.ref, sourceEnd.desk, sourceLocation.path, sourceLocation)
                .getOrThrow() ?: error("Synchronization source disappeared")
            val logicalSource = syncComparisonTargetFile(sourceEnd.desk, actualSource)
            val verifiedSource = readVerifiedSyncEndpointState(endpointFiles, sourceEnd.type, sourceEnd.ref, sourceEnd.desk,
                logicalSource, operation, ensureRunning = { ensureOperationRunning(operation.key) }).getOrThrow()
            sourceState = verifiedSource
            check(syncEntryMatchesSnapshot(planned.expectedSource, verifiedSource)) { "Synchronization source changed while preparing its copy" }
            check(io.read(destinationEndpoint, temporary, null) == null) { "Synchronization temporary path is occupied" }
            io.ensureParent(destinationEndpoint, temporary)
            val copyTask = task.copy(sourceType = sourceEnd.type, sourceRef = sourceEnd.ref,
                targetType = destination.type, targetRef = destination.ref, targetPath = destinationEndpoint.rootPath,
                targetStorageReference = destinationEndpoint.storageReference)
            val copied = copySyncFileWithRetry(scope, task.retryCount, task.timeoutSeconds * 1_000L,
                lastProgressAt = { taskState.lastRuntimeProgressAt(operation.key, temporary) },
                ensureRunning = { ensureOperationRunning(operation.key) },
                stopBlockedIo = { (destination.desk as? NetworkAccess)?.disconnect() },
                // An uncertain copy is retained for bounded, journal-aware temporary cleanup.
                cleanup = {},
                isUncertainFailure = { it.message.orEmpty().contains("uncertain", ignoreCase = true) },
            ) { copyOnce(copyTask, operation, sourceEnd.desk, destination.desk, logicalSource, temporary) }
            check(copied.getOrThrow()) { "Synchronization staging copy failed" }
            if (verifiedSource.modifiedAt > 0) {
                endpointFiles.setModifiedTime(destination.type, destination.desk, temporary, verifiedSource.modifiedAt)
            }
            staged = checkNotNull(io.read(destinationEndpoint, temporary, verifiedSource)) { "Staged copy disappeared" }
            check(staged.size == verifiedSource.size && staged.contentSha256 == verifiedSource.contentSha256) { "Staged copy content does not match" }
            check(syncEntryMatchesSnapshot(verifiedSource, io.read(sourceEndpoint, sourceLocation.path, verifiedSource))) {
                "Synchronization source changed during copying"
            }
        }
        records += SyncFileRecovery(id, task.id, fingerprint, task.ownerDeviceId, groupId, planned.participant,
            destinationEndpoint, planned.relativePath, final.path, temporary, oldPath, old, sourceState,
            task.recycleEnabled && !permanent, logicalPath = path.relativePath,
            action = when (planned.kind) {
                SyncOperationKind.CreateDirectory -> SyncRecoveryAction.CreateDirectory
                SyncOperationKind.Delete, SyncOperationKind.CleanSource, SyncOperationKind.CleanEmptyDirectory -> SyncRecoveryAction.Delete
                SyncOperationKind.Overwrite -> SyncRecoveryAction.Overwrite
                else -> SyncRecoveryAction.Copy
            }, conflictCopy = planned.kind == SyncOperationKind.ConflictCopy,
            sourceEndpoint = source?.first, sourcePath = source?.second?.path.orEmpty(), stagedState = staged,
            oldTree = oldTree, expectedParticipants = path.expected, participantLocations = participantLocations,
            previewFingerprint = prepared.previewFingerprint, backupPaths = path.backupPaths,
            expectedBackupStates = expectedBackupStates,
            selectedSourceReference = path.expected["source"]?.storageReference
                ?: prepared.preparation.baselines[path.relativePath]?.selectedSourceReference
                ?: prepared.preparation.baselines[path.relativePath]?.participants?.get("source")?.storageReference)
    }
    validateOriginals()
    onRecoveryGroup(groupId)
    return executeSyncRecoveryGroup(records, io, store::saveRecoveryGroup, { current ->
        val changedParticipants = current.filter { it.relativePath == path.relativePath }.mapTo(mutableSetOf()) { it.participant }
        validateOriginals(changedParticipants)
        if (path.backupPaths.isNotEmpty() && current.any { it.action == SyncRecoveryAction.Delete && it.participant == "source" } &&
            current.filter { it.participant != "source" }.all { it.phase == SyncRecoveryPhase.Verified }) {
            verifySyncBackupCopies(current, io)
        }
    }, ensureRunning = { ensureOperationRunning(operation.key) })
}
