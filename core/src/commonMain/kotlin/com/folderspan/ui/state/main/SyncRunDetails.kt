package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import strings.AppStrings

/** Counts all outcomes while persisting at most 500 rows per transaction. */
internal class SyncRunDetails(private val task: SyncTask, private val runId: Long, private val store: SyncLocalStore?) {
    private val mutex = Mutex()
    private val counts = linkedMapOf<SyncRunItemKind, LongArray>()
    private val buffer = mutableListOf<SyncRunItem>()
    private var total = 0L
    private var written = 0L
    private var failed = false
    private var initialized = false

    suspend fun add(item: SyncRunItem) = add(listOf(item))

    suspend fun add(items: List<SyncRunItem>) = mutex.withLock {
        for (item in items) {
            counts.getOrPut(item.kind) { LongArray(2) }[if (item.success) 0 else 1]++
            total++
            if (store != null && !failed) {
                buffer.add(item)
                if (buffer.size == 500) flush()
            }
        }
    }

    private suspend fun flush() {
        if (buffer.isEmpty() || store == null || failed) return
        try {
            if (!initialized) { store.deleteRunDetails(task.id, task.ownerDeviceId, runId); initialized = true }
            store.appendRunDetails(task.id, task.ownerDeviceId, runId, written, buffer)
            written += buffer.size
            buffer.clear()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failed = true
            buffer.clear()
            LogKit.w(sanitizeSyncMessage(error.message))
        }
    }

    suspend fun finish(record: SyncRunRecord): SyncRunRecord = withContext(NonCancellable) {
        if (total == 0L) add(record.items)
        mutex.withLock {
            flush()
            record.copy(items = record.items.sortedBy { it.success }.take(RUN_ITEM_LIMIT),
                resultCounts = counts.map { (kind, count) -> SyncRunResultCount(kind, count[0], count[1]) },
                detailCount = written, unsavedDetailCount = if (store == null) 0 else total - written)
        }
    }
}

internal data class SyncRunDetailTarget(val run: SyncRunDetails, val participant: String, val originalSource: FileSimpleInfo? = null) {
    suspend fun record(item: SyncPlanItem, success: Boolean, message: String) {
        val kind = when (item.action) {
            SyncPlanAction.Copy -> if (item.targetExisted) SyncRunItemKind.Overwrite else SyncRunItemKind.Copy
            SyncPlanAction.RenameCopy -> SyncRunItemKind.Rename
            SyncPlanAction.CreateDirectory -> SyncRunItemKind.CreateDirectory
            SyncPlanAction.Unchanged -> SyncRunItemKind.Unchanged
            SyncPlanAction.Skip -> SyncRunItemKind.Skip
        }
        run.add(SyncRunItem(item.targetPath, success, message, originalSource ?: item.source, kind, participant))
    }
}

internal fun SyncFileRecovery.runRecoveryItem(message: String = when (phase) {
    SyncRecoveryPhase.Prepared -> AppStrings.sync_recovery_prepared
    SyncRecoveryPhase.OldPreserved -> AppStrings.sync_recovery_old_preserved
    SyncRecoveryPhase.Placed -> AppStrings.sync_recovery_placed
    SyncRecoveryPhase.Verified -> AppStrings.sync_recovery_verified
}): SyncRunItem = SyncRunItem(logicalPath, false, message, kind = SyncRunItemKind.Recovery,
    participant = participant, recoveryGroup = groupId,
    recoveryLocations = listOf(oldPath, temporaryPath, finalPath).filter { it.isNotEmpty() }.distinct().map { SyncRecoveryLocation(endpoint, it) })

internal fun syncRunPathItems(path: SyncPathPlan, records: List<SyncFileRecovery>, labels: Map<String, String>,
    success: Boolean = true, message: String = AppStrings.ui_synchronization_completed): List<SyncRunItem> = buildList {
    if (path.conflict) add(SyncRunItem(path.relativePath, success,
        if (!success) message else when {
            path.skipped -> AppStrings.sync_conflict_skipped
            path.operations.any { it.kind == SyncOperationKind.ConflictCopy } -> AppStrings.sync_conflict_all_versions_kept
            path.operations.isNotEmpty() && path.operations.all { it.kind == SyncOperationKind.Delete } -> AppStrings.sync_conflict_selected_deletion
            else -> AppStrings.sync_conflict_version_used.format(arg0 = path.operations.firstNotNullOfOrNull { it.sourceParticipant }?.let { labels[it] ?: it }.orEmpty())
        }, kind = SyncRunItemKind.Conflict))
    if (path.operations.isEmpty()) {
        if (!path.conflict) add(SyncRunItem(path.relativePath, success, message,
            kind = if (path.skipped) SyncRunItemKind.Skip else SyncRunItemKind.Unchanged))
        return@buildList
    }
    for (operation in path.operations) {
        val record = records.firstOrNull { it.participant == operation.participant && it.relativePath == operation.relativePath }
        val completed = success || record?.phase == SyncRecoveryPhase.Verified
        val kind = when (operation.kind) {
            SyncOperationKind.Copy -> SyncRunItemKind.Copy
            SyncOperationKind.Overwrite -> SyncRunItemKind.Overwrite
            SyncOperationKind.ConflictCopy -> SyncRunItemKind.Rename
            SyncOperationKind.CreateDirectory -> SyncRunItemKind.CreateDirectory
            SyncOperationKind.Delete, SyncOperationKind.CleanEmptyDirectory -> SyncRunItemKind.Delete
            SyncOperationKind.CleanSource -> SyncRunItemKind.CleanSource
        }
        val locations = if (completed && record?.recycleEnabled == true && record.oldPath.isNotEmpty())
            listOf(SyncRecoveryLocation(record.endpoint, record.oldPath)) else emptyList()
        add(SyncRunItem(operation.relativePath, completed, if (completed) AppStrings.ui_synchronization_completed else message,
            kind = kind, participant = operation.participant, recoveryGroup = record?.groupId.orEmpty(), recoveryLocations = locations))
        if (operation.kind == SyncOperationKind.CreateDirectory && operation.expectedTarget != null)
            add(SyncRunItem(operation.relativePath, completed, message, kind = SyncRunItemKind.Overwrite,
                participant = operation.participant, recoveryGroup = record?.groupId.orEmpty(), recoveryLocations = locations))
        if (locations.isNotEmpty()) add(SyncRunItem(operation.relativePath, true, AppStrings.sync_recycled,
            kind = SyncRunItemKind.Recycle, participant = operation.participant, recoveryGroup = record!!.groupId, recoveryLocations = locations))
    }
    if (!success) addAll(records.map { it.runRecoveryItem(message) })
}

suspend fun SyncState.runDetailPage(record: SyncRunRecord, offset: Long = 0, limit: Int = 100,
    kind: SyncRunItemKind? = null): List<SyncRunDetailRow> {
    val task = getTask(record.taskId) ?: return emptyList()
    check(isTaskOwnedByCurrentDevice(task)) { AppStrings.ui_task_does_not_exist_has_been_deleted }
    val current = recordsFor(record.taskId).firstOrNull { it.runId == record.runId } ?: return emptyList()
    val owner = record.policySnapshot?.ownerDeviceId?.takeIf { it.isNotEmpty() } ?: task.ownerDeviceId
    check(owner == task.ownerDeviceId) { AppStrings.ui_task_does_not_exist_has_been_deleted }
    val rows = if (record.detailCount > 0) checkNotNull(localStore).runDetailPage(task.id, owner, record.runId,
        minOf(record.detailCount, current.detailCount), offset, limit, kind)
    else (if (record.targets.isEmpty()) record.items else record.targets.flatMapIndexed { index, target ->
        target.items.map { it.copy(participant = "target-$index") }
    }).withIndex().filter { kind == null || it.value.kind == kind }.sortedBy { it.value.success }
        .drop(offset.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()).take(limit.coerceIn(1, 500)).map { SyncRunDetailRow(it.index.toLong(), it.value) }
    check(getTask(task.id)?.ownerDeviceId == owner && isTaskOwnedByCurrentDevice(task)) { AppStrings.ui_task_does_not_exist_has_been_deleted }
    return rows
}

suspend fun SyncState.pendingRecoveryPage(taskId: Long, offset: Long = 0, limit: Int = 100): List<SyncRunDetailRow> {
    val task = getTask(taskId) ?: return emptyList()
    if (!isTaskOwnedByCurrentDevice(task)) return emptyList()
    val records = localStore?.recoveryPage(taskId, offset, limit).orEmpty()
    check(getTask(taskId)?.ownerDeviceId == task.ownerDeviceId && isTaskOwnedByCurrentDevice(task)) { AppStrings.ui_task_does_not_exist_has_been_deleted }
    return records.mapIndexed { index, record -> SyncRunDetailRow(offset + index, record.runRecoveryItem()) }
}
