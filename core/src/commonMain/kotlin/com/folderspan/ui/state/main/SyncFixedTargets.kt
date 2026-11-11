package com.folderspan.ui.state.main

import com.folderspan.data.main.network.Network
import com.folderspan.service.network.storageConnectionBinding

internal fun SyncTask.requiresFixedSyncTargets(): Boolean = mode != SyncMode.Update && destinations.isNotEmpty() && selectRecommended

internal fun SyncTaskLocalState?.confirms(task: SyncTask): Boolean = this != null && fingerprint == syncDefinitionFingerprint(task) &&
    previewConfirmed && (!task.requiresFixedSyncTargets() || fixedTargets.size == 1)

internal fun SyncState.fixedSyncTargetsCurrent(task: SyncTask, targets: List<StorageDestination>, sourceBinding: String): Boolean {
    if (!task.requiresFixedSyncTargets()) return true
    val source = resolveEndpoint(task.sourceType, task.sourceRef)
    return targets.size == 1 && targets.all { target ->
        target in task.destinations && networkState.entries.any { target.matches(it) }
    } && (task.sourceType != SyncEndpointType.Network || source is Network) &&
        sourceBinding == (source as? Network)?.let { storageConnectionBinding(it.connectionConfig()) }.orEmpty()
}

/** A changed account loses consent and ordinary baseline state while its recovery journal stays intact. */
internal suspend fun SyncState.refreshFixedSyncTargets(task: SyncTask) {
    if (!task.requiresFixedSyncTargets()) return
    val store = checkNotNull(localStore)
    val state = store.localState(task.id) ?: return
    if (state.fixedTargets.isNotEmpty() && !fixedSyncTargetsCurrent(task, state.fixedTargets, state.fixedSourceBinding)) {
        store.invalidateFixedTargets(task.id, syncDefinitionFingerprint(task))
        refreshSyncAttention(task.id)
    }
}

internal fun SyncState.preparedSyncEndpointsCurrent(prepared: SyncPreparedRun): Boolean = prepared.endpoints.all { endpoint ->
    endpoint.connectionBinding.isEmpty() || (resolveEndpoint(endpoint.type, endpoint.ref) as? Network)?.let {
        storageConnectionBinding(it.connectionConfig()) == endpoint.connectionBinding
    } == true
}
