package com.folderspan.ui.state.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import strings.AppStrings

/** The Update executor consumes the cached actions rather than rescanning or selecting new paths. */
internal suspend fun SyncState.executeUpdatePreparedRun(prepared: SyncPreparedRun, operation: Task, trigger: String, failedItems: MutableList<SyncFailureItem>): SyncRunRecord {
    val started = maxOf(nowMillis(), recordsFor(prepared.task.id).maxOfOrNull { it.runId }?.plus(1) ?: 0)
    val details = SyncRunDetails(prepared.task, started, localStore)
    val record = executeUpdatePreparedWithDetails(prepared, operation, trigger, failedItems, started, details)
    return details.finish(record.copy(policySnapshot = prepared.task, participants = prepared.runParticipants()))
}

private suspend fun SyncState.executeUpdatePreparedWithDetails(prepared: SyncPreparedRun, operation: Task, trigger: String,
    failedItems: MutableList<SyncFailureItem>, started: Long, details: SyncRunDetails): SyncRunRecord {
    try {
        val update = checkNotNull(prepared.update)
        val target = update.targets.single()
        val source = prepared.endpoints.first()
        val guard = SyncUpdateGuard(this, prepared, target, operation)
        val result = executeDirectorySync(prepared.task, source.desk, target.endpoint.desk, update.source, operation,
            frozen = target, guard = guard, details = SyncRunDetailTarget(details, target.endpoint.id))
        failedItems.addAll(result.failedItems)
        return SyncRunRecord(started, prepared.task.id, trigger, started, nowMillis(), result.status,
            result.successCount + result.failureCount, result.successCount, result.failureCount, result.message, result.items,
            copiedCount = result.copiedCount, renamedCount = result.renamedCount, createdDirectoryCount = result.createdDirectoryCount,
            unchangedCount = result.unchangedCount, skippedCount = result.skippedCount, transferredBytes = result.transferredBytes,
            transferDurationMillis = result.transferDurationMillis, unsavedFailureCount = (result.failureCount - result.failedItems.size).coerceAtLeast(0))
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { details.add(SyncRunItem("", false, AppStrings.ui_sync_canceled)) }
        return SyncRunRecord(started, prepared.task.id, trigger, started, nowMillis(), SyncRunStatus.Canceled,
            0, 0, 0, AppStrings.ui_sync_canceled)
    } catch (error: Exception) {
        val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_sync_failed)
        details.add(SyncRunItem("", false, message))
        return SyncRunRecord(started, prepared.task.id, trigger, started, nowMillis(), SyncRunStatus.Failure,
            1, 0, 1, message, listOf(SyncRunItem("", false, message)), unsavedFailureCount = 1)
    }
}
