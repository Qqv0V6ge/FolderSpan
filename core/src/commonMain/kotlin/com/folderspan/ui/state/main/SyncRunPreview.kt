package com.folderspan.ui.state.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import strings.AppStrings
import kotlin.uuid.Uuid
import com.folderspan.notification.clearSyncNotification
import com.folderspan.notification.postSyncNotification
import com.folderspan.notification.SyncNotificationEvent
import com.folderspan.runtime.RuntimeServiceFailures

data class SyncTaskAttention(val fingerprint: String, val previewConfirmed: Boolean, val pendingDeletionCount: Long,
    val confirmationNotified: Boolean, val pendingRunId: Long = 0,
    val fixedTargets: List<StorageDestination> = emptyList(), val fixedSourceBinding: String = "")

enum class SyncPreviewAction { CreateDirectory, Copy, Overwrite, Delete, CleanEmptyDirectory, CleanSource, ConflictCopy, Unchanged, Skipped, HandledWithParent, Recycle }
data class SyncPreviewItem(val action: SyncPreviewAction, val participant: String, val relativePath: String,
    val sourceParticipant: String? = null, val sourceRelativePath: String = relativePath, val size: Long = 0,
    val conflict: Boolean = false)
data class SyncPreviewCount(val count: Int, val bytes: Long)
data class SyncRunPreview(val token: String, val taskId: Long, val taskName: String, val mode: SyncMode,
    val participants: Map<String, String>, val counts: Map<SyncPreviewAction, SyncPreviewCount>, val itemCount: Int,
    val comparisonReadBytes: Long, val requiresFirstConfirmation: Boolean, val unexpectedlyEmptyScopes: List<Pair<String, String>>,
    val pendingDeletionsOnly: Boolean, val conflictCount: Int = 0, val recycleEnabled: Boolean = false, val conflictItemCount: Int = 0,
    val participantPaths: Map<String, List<String>> = emptyMap(), val pendingRunId: Long = 0,
    val fixedRecommendedTarget: Boolean = false)

internal data class SyncCachedPreview(val token: String, val prepared: SyncPreparedRun, val pendingRunId: Long = 0,
    val recycleDirectoryBytes: Map<Pair<String, String>, Long> = emptyMap())

fun SyncState.currentTaskAttention(taskId: Long): SyncTaskAttention? {
    val attention = taskAttention[taskId] ?: return null
    val task = getTask(taskId) ?: return null
    return if (attention.previewConfirmed && !fixedSyncTargetsCurrent(task, attention.fixedTargets, attention.fixedSourceBinding))
        attention.copy(previewConfirmed = false, fixedTargets = emptyList()) else attention
}

fun SyncState.requiresSyncPreview(task: SyncTask): Boolean = task.mode != SyncMode.Update &&
    currentTaskAttention(task.id)?.let { it.fingerprint != syncDefinitionFingerprint(task) || !it.previewConfirmed } != false

internal suspend fun SyncState.deferUnconfirmedSyncRun(task: SyncTask): Boolean {
    if (task.mode == SyncMode.Update) return false
    check(isTaskOwnedByCurrentDevice(task)) { AppStrings.sync_preview_expired }
    val store = checkNotNull(localStore)
    refreshFixedSyncTargets(task)
    val local = store.localState(task.id)
    check(local?.fingerprint == syncDefinitionFingerprint(task)) { "Synchronization local state is unavailable or changed" }
    if (!local.confirms(task) && store.markConfirmationNotified(task.id, local.fingerprint)) {
        postSyncNotification(task, SyncNotificationEvent.PreviewRequired)
    }
    refreshSyncAttention(task.id)
    return !local.confirms(task)
}

/** Requests the confirmation notice without queuing file work or occupying a run permit. */
fun SyncState.requestSyncPreviewConfirmation(taskId: Long) {
    scope.launch {
        RuntimeServiceFailures.observe("sync") {
            lifecycleMutex.withLock {
                val task = getTask(taskId) ?: return@withLock
                if (localStore != null && isTaskOwnedByCurrentDevice(task) && !hasActiveSyncRun(taskId) && deferUnconfirmedSyncRun(task)) {
                    updateTaskStatus(taskId, SyncRunStatus.Idle, AppStrings.sync_preview_required)
                }
            }
        }
    }
}

internal suspend fun SyncState.refreshSyncAttention(taskId: Long) {
    val task = getTask(taskId) ?: return
    val store = localStore
    val state = store?.localState(taskId)
    val attention = SyncTaskAttention(state?.fingerprint.orEmpty(), state.confirms(task),
        store?.pendingDeletionCount(taskId) ?: 0, state?.confirmationNotified == true, state?.pendingRunId ?: 0,
        if (isTaskOwnedByCurrentDevice(task)) state?.fixedTargets.orEmpty() else emptyList(), state?.fixedSourceBinding.orEmpty())
    withContext(Dispatchers.Main) {
        if (getTask(taskId)?.let(::syncDefinitionFingerprint) == syncDefinitionFingerprint(task)) {
            taskAttention[taskId] = attention
            notifyChanged()
        }
    }
}

private fun SyncPathPlan.previewAction(): SyncPreviewAction? = when {
    skipped -> SyncPreviewAction.Skipped
    operations.isNotEmpty() -> null
    removedWithParent != null -> SyncPreviewAction.HandledWithParent
    else -> SyncPreviewAction.Unchanged
}

private fun SyncPlannedOperation.previewAction(): SyncPreviewAction = when (kind) {
    SyncOperationKind.CreateDirectory -> SyncPreviewAction.CreateDirectory
    SyncOperationKind.Copy -> SyncPreviewAction.Copy
    SyncOperationKind.Overwrite -> SyncPreviewAction.Overwrite
    SyncOperationKind.Delete -> SyncPreviewAction.Delete
    SyncOperationKind.CleanEmptyDirectory -> SyncPreviewAction.CleanEmptyDirectory
    SyncOperationKind.CleanSource -> SyncPreviewAction.CleanSource
    SyncOperationKind.ConflictCopy -> SyncPreviewAction.ConflictCopy
}

private fun SyncPlannedOperation.previewSize(): Long =
    (expectedSource ?: expectedTarget)?.takeUnless { it.isDirectory }?.size?.coerceAtLeast(0) ?: 0

private fun SyncTask.recycles(operation: SyncPlannedOperation): Boolean = recycleEnabled &&
    mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional) && operation.expectedTarget != null &&
    operation.kind in listOf(SyncOperationKind.Overwrite, SyncOperationKind.Delete, SyncOperationKind.CreateDirectory)

private fun SyncPlannedOperation.recycleSize(directoryBytes: Map<Pair<String, String>, Long>): Long =
    expectedTarget?.let { if (it.isDirectory) directoryBytes[participant to relativePath] ?: 0 else it.size }?.coerceAtLeast(0) ?: 0

internal data class SyncPreviewSummary(val counts: Map<SyncPreviewAction, SyncPreviewCount>, val itemCount: Int, val conflictItemCount: Int)

internal fun SyncPlan.previewSummary(task: SyncTask, recycleDirectoryBytes: Map<Pair<String, String>, Long> = emptyMap()): SyncPreviewSummary {
    val counts = IntArray(SyncPreviewAction.entries.size)
    val bytes = LongArray(counts.size)
    var total = 0
    var conflicts = 0
    fun add(action: SyncPreviewAction, size: Long, conflict: Boolean) {
        counts[action.ordinal]++
        bytes[action.ordinal] += size.coerceAtMost(Long.MAX_VALUE - bytes[action.ordinal])
        total++
        if (conflict) conflicts++
    }
    for (path in paths) {
        val fallback = path.previewAction()
        if (fallback != null) add(fallback, 0, path.conflict)
        else for (operation in path.operations) {
            add(operation.previewAction(), operation.previewSize(), path.conflict)
            if (task.recycles(operation)) add(SyncPreviewAction.Recycle, operation.recycleSize(recycleDirectoryBytes), path.conflict)
        }
    }
    return SyncPreviewSummary(SyncPreviewAction.entries.filter { counts[it.ordinal] > 0 }
        .associateWith { SyncPreviewCount(counts[it.ordinal], bytes[it.ordinal]) }, total, conflicts)
}

internal fun SyncPlan.previewItems(task: SyncTask, offset: Int = 0, limit: Int = Int.MAX_VALUE,
    recycleDirectoryBytes: Map<Pair<String, String>, Long> = emptyMap(), filter: SyncPreviewAction? = null,
    conflictsOnly: Boolean = false): Sequence<SyncPreviewItem> = sequence {
    var skippedRows = offset
    var remaining = limit
    for (path in paths) {
        if (conflictsOnly && !path.conflict) continue
        val fallback = path.previewAction()
        if (fallback != null) {
            val action = fallback
            if (filter != null && filter != action) continue
            if (skippedRows > 0) { skippedRows--; continue }
            if (remaining-- <= 0) return@sequence
            yield(SyncPreviewItem(action, "", path.relativePath, conflict = path.conflict))
        } else for (operation in path.operations) {
            val action = operation.previewAction()
            if (filter == null || filter == action) {
                if (skippedRows > 0) skippedRows-- else {
                    if (remaining-- <= 0) return@sequence
                    yield(SyncPreviewItem(action, operation.participant, operation.relativePath, operation.sourceParticipant,
                        operation.sourceRelativePath, operation.previewSize(), path.conflict))
                }
            }
            if (task.recycles(operation) && (filter == null || filter == SyncPreviewAction.Recycle)) {
                if (skippedRows > 0) skippedRows-- else {
                    if (remaining-- <= 0) return@sequence
                    yield(SyncPreviewItem(SyncPreviewAction.Recycle, operation.participant, operation.relativePath,
                        size = operation.recycleSize(recycleDirectoryBytes), conflict = path.conflict))
                }
            }
        }
    }
}

/** Read-only: one snapshot per task; rows are materialized only for the requested page. */
suspend fun SyncState.createSyncPreview(taskId: Long, pendingDeletionsOnly: Boolean = false): Result<SyncRunPreview> = try {
    val task = checkNotNull(getTask(taskId)) { AppStrings.sync_preview_expired }
    check(isTaskOwnedByCurrentDevice(task) && !hasActiveSyncRun(taskId)) { AppStrings.sync_preview_expired }
    var prepared = prepareSyncRun(task, createOperationTask(task)).getOrThrow()
    var pendingRunId = 0L
    if (pendingDeletionsOnly) {
        val store = checkNotNull(localStore)
        pendingRunId = store.localState(taskId)?.pendingRunId ?: 0
        check(pendingRunId > 0) { AppStrings.sync_pending_deletions_changed }
        val pending = mutableSetOf<SyncPendingDelete>()
        var offset = 0L
        do {
            val page = store.pendingDeletionPage(taskId, syncDefinitionFingerprint(task), offset)
            pending += page
            offset += page.size
        } while (page.size == 500)
        val pendingPaths = pending.mapTo(mutableSetOf()) { it.relativePath }
        val paths = prepared.preparation.plan.paths.filter { it.relativePath in pendingPaths }.map { path ->
            val allowed = path.operations.filter { it.pendingDeletion(path) in pending }
            path.copy(operations = allowed, skipped = path.skipped || allowed.isEmpty())
        }
        prepared = prepared.copy(preparation = prepared.preparation.copy(plan = SyncPlan(paths)))
    }
    val recycleBytes = mutableMapOf<Pair<String, String>, Long>()
    if (task.recycleEnabled && task.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) {
        val directoryParticipants = prepared.preparation.plan.paths.flatMap { it.operations }.filter { it.expectedTarget?.isDirectory == true &&
            it.kind != SyncOperationKind.CleanEmptyDirectory }.mapTo(mutableSetOf()) { it.participant }
        for (participant in prepared.preparation.participants.filter { it.id in directoryParticipants }) {
            for ((path, file) in participant.scan.occupied) if (!file.isDirectory) {
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    val key = participant.id to parent
                    val before = recycleBytes[key] ?: 0
                    recycleBytes[key] = before + file.size.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - before)
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
    }
    val cached = SyncCachedPreview(Uuid.random().toString(), prepared, pendingRunId, recycleBytes)
    lifecycleMutex.withLock {
        val current = getTask(taskId)
        check(current != null && isTaskOwnedByCurrentDevice(current) && !hasActiveSyncRun(taskId) &&
            current.lastRunAt == task.lastRunAt && syncPreviewFingerprint(current) == prepared.previewFingerprint) { AppStrings.sync_preview_expired }
        if (pendingRunId > 0) check(localStore?.localState(taskId)?.pendingRunId == pendingRunId) { AppStrings.sync_pending_deletions_changed }
        runPreviews[taskId] = cached
    }
    val summary = prepared.preparation.plan.previewSummary(task, recycleBytes)
    Result.success(SyncRunPreview(cached.token, taskId, task.name, task.mode, prepared.endpoints.associate { it.id to it.label }, summary.counts, summary.itemCount,
        prepared.preparation.comparisonReadBytes, task.mode != SyncMode.Update && !prepared.localState.confirms(task),
        protectSyncRunPlan(prepared).unexpectedlyEmptyScopes, pendingDeletionsOnly,
        prepared.preparation.plan.paths.count { it.conflict }, task.recycleEnabled && task.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional), summary.conflictItemCount,
        prepared.endpoints.associate { endpoint -> endpoint.id to if (endpoint.id == "source" && task.selectedSources.isNotEmpty())
            task.selectedSources.map { it.path } else listOf(endpoint.root.path) }, pendingRunId, task.requiresFixedSyncTargets()))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(IllegalStateException(sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed), error)) }

suspend fun SyncState.syncPreviewPage(token: String, offset: Int = 0, limit: Int = 100, action: SyncPreviewAction? = null,
    conflictsOnly: Boolean = false): List<SyncPreviewItem> = lifecycleMutex.withLock {
    val cached = runPreviews.values.firstOrNull { it.token == token } ?: error(AppStrings.sync_preview_expired)
    cached.prepared.preparation.plan.previewItems(cached.prepared.task, offset.coerceAtLeast(0), limit.coerceIn(1, 500),
        cached.recycleDirectoryBytes, action, conflictsOnly).toList()
}

suspend fun SyncState.discardSyncPreview(token: String) = lifecycleMutex.withLock {
    runPreviews.entries.removeAll { it.value.token == token }
}

/** The queue reservation precedes the confirmation write, so an automatic run cannot steal first consent. */
suspend fun SyncState.confirmSyncPreview(token: String): Result<SyncTask> = confirmSyncPreview(token, ignoreConditions = false)

suspend fun SyncState.confirmSyncPreview(token: String, ignoreConditions: Boolean): Result<SyncTask> = try {
    lifecycleMutex.withLock {
        val cached = runPreviews.values.firstOrNull { it.token == token } ?: error(AppStrings.sync_preview_expired)
        val task = cached.prepared.task
        val current = getTask(task.id)
        check(current != null && isTaskOwnedByCurrentDevice(current) && !hasActiveSyncRun(task.id) &&
            current.lastRunAt == task.lastRunAt && syncPreviewFingerprint(current) == cached.prepared.previewFingerprint) { AppStrings.sync_preview_expired }
        check(preparedSyncEndpointsCurrent(cached.prepared)) { AppStrings.sync_preview_expired }
        val store = checkNotNull(localStore)
        check(store.localState(task.id)?.fingerprint == syncDefinitionFingerprint(task)) { AppStrings.sync_preview_expired }
        if (cached.pendingRunId > 0) check(store.localState(task.id)?.pendingRunId == cached.pendingRunId) { AppStrings.sync_pending_deletions_changed }
        val gate = CompletableDeferred<Unit>()
        val approved = cached.prepared.preparation.plan.paths.flatMap { path -> path.operations.mapNotNull { it.pendingDeletion(path) } }.toSet()
        check(enqueueRun(current, ignoreConditions) {
            gate.await()
            runTaskInternal(current, "confirm", preparedPreview = cached.prepared, approvedDeletions = approved)
        }) { AppStrings.sync_preview_expired }
        try {
            store.confirmPreview(task.id, syncDefinitionFingerprint(task),
                if (task.requiresFixedSyncTargets()) cached.prepared.participatingDestinations else emptyList(),
                if (task.requiresFixedSyncTargets()) cached.prepared.endpoints.first().connectionBinding else "")
            refreshSyncAttention(task.id)
            runPreviews.remove(task.id)
            clearSyncNotification(task.id)
            gate.complete(Unit)
        } catch (error: Exception) {
            gate.cancel(CancellationException("Preview confirmation failed", error))
            withContext(NonCancellable) { updateTaskStatus(task.id, SyncRunStatus.Idle, sanitizeSyncMessage(error.message)) }
            throw error
        }
        Result.success(checkNotNull(getTask(task.id)))
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(IllegalStateException(sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed), error)) }

suspend fun SyncState.rejectPendingSyncDeletions(taskId: Long, expectedRunId: Long): Boolean = lifecycleMutex.withLock {
    val task = getTask(taskId) ?: return@withLock false
    check(isTaskOwnedByCurrentDevice(task)) { AppStrings.sync_preview_expired }
    val cleared = localStore?.clearPendingDeletions(taskId, syncDefinitionFingerprint(task), expectedRunId) == true
    if (cleared) { runPreviews.remove(taskId); refreshSyncAttention(taskId); clearSyncNotification(taskId) }
    cleared
}
