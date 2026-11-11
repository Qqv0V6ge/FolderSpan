package com.folderspan.ui.state.main

import com.folderspan.service.sync.beforeSyncConditionFile
import com.folderspan.service.sync.withSyncConditionPath

import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.network.Network
import com.folderspan.service.network.storageConnectionBinding
import kotlinx.coroutines.withTimeout

/** Settles the frozen journal before a new scan can observe an interrupted path as a user deletion. */
internal suspend fun SyncState.recoverSyncTaskBeforeScan(task: SyncTask, operation: Task) {
    val store = localStore ?: return
    val pending = store.recoveries(task.id)
    if (pending.isEmpty()) return
    check(isTaskOwnedByCurrentDevice(task) && pending.all { it.ownerDeviceId == task.ownerDeviceId }) {
        "Synchronization recovery belongs to another execution device"
    }
    val endpoints = pending.flatMap { record ->
        listOfNotNull(record.endpoint, record.sourceEndpoint) + record.participantLocations.values.map { it.endpoint }
    }.distinct()
    val bindings = linkedMapOf<SyncRecoveryEndpoint, DiskBase>()
    for (endpoint in endpoints) {
        ensureOperationRunning(operation.key)
        val connect = task.copy(targetType = endpoint.type, targetRef = endpoint.ref, targetPath = endpoint.rootPath,
            targetStorageReference = endpoint.storageReference)
        val error = withTimeout(30_000) { targetEndpointMissingError(connect) ?: connectTargetEndpointIfNeeded(connect) }
        check(error == null) { error ?: "Recovery endpoint is unavailable" }
        val desk = resolveEndpoint(endpoint.type, endpoint.ref) ?: error("Recovery endpoint is unavailable")
        check(endpoint.type != SyncEndpointType.Network || desk is Network && endpoint.connectionBinding.isNotEmpty() &&
            endpoint.connectionBinding == storageConnectionBinding(desk.connectionConfig())) { "Recovery endpoint configuration changed" }
        bindings[endpoint] = desk
    }
    val io = SyncRecoveryEndpointOperations(endpointFiles, operation, bindings) { desk, source, destination ->
        ensureOperationRunning(operation.key)
        beforeSyncConditionFile()
        fileState.runCopyAcrossEndpoints(operation, source, destination, desk, desk)
    }
    for (group in pending.groupBy { it.groupId }.values) withSyncConditionPath {
        beforeSyncConditionFile()
        val first = group.first()
        val current = getTask(task.id)
        if (current == null || syncDefinitionFingerprint(current) != first.fingerprint ||
            first.previewFingerprint.isEmpty() || syncPreviewFingerprint(current) != first.previewFingerprint) {
            restoreSyncRecoveryOriginals(group, io, store::saveRecoveryGroup)
            error("Synchronization definition changed; recovery files and records are retained: ${first.finalPath}; ${first.oldPath}; ${first.temporaryPath}")
        }
        require(first.expectedParticipants.keys == first.participantLocations.keys && first.participantLocations.isNotEmpty()) {
            "Recovery participant locations are unavailable"
        }
        suspend fun validate(records: List<SyncFileRecovery>) {
            ensureOperationRunning(operation.key)
            val latest = getTask(task.id)
            check(latest != null && isTaskOwnedByCurrentDevice(latest) && latest.ownerDeviceId == first.ownerDeviceId &&
                syncDefinitionFingerprint(latest) == first.fingerprint && syncPreviewFingerprint(latest) == first.previewFingerprint) {
                "Synchronization definition changed during recovery"
            }
            val changed = records.filter { it.relativePath == first.logicalPath }.mapTo(mutableSetOf()) { it.participant }
            for ((id, expected) in first.expectedParticipants) if (id !in changed) {
                val location = first.participantLocations.getValue(id)
                check(syncEntryMatchesSnapshot(expected, io.read(location.endpoint, location.path, expected))) {
                    "Synchronization recovery participant changed"
                }
            }
            if (first.backupPaths.isNotEmpty() && records.any { it.action == SyncRecoveryAction.Delete && it.participant == "source" } &&
                records.filter { it.participant != "source" }.all { it.phase == SyncRecoveryPhase.Verified }) {
                verifySyncBackupCopies(records, io)
            }
        }
        val completed = executeSyncRecoveryGroup(group, io, store::saveRecoveryGroup, ::validate,
            ensureRunning = { ensureOperationRunning(operation.key) })
        val baseline = verifySyncRecoveryBaseline(completed, io)
        validate(completed)
        rememberSyncSourceReference(task, baseline)
        store.commitVerified(task.id, first.fingerprint, listOf(baseline), completedRecoveryIds = completed.mapTo(mutableSetOf()) { it.id })
    }
}
