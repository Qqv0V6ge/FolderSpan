package com.folderspan.ui.state.main

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.SqlDriver
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SyncLocalStore(
    private val database: FolderSpanDatabase,
    driver: SqlDriver,
    private val awaitReady: suspend () -> Unit = DatabaseReady::await,
) {
    private val mutex = Mutex()
    private val transactions = object : SuspendingTransacterImpl(driver) {}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        awaitReady()
        block()
    }

    private suspend fun state(taskId: Long): SyncTaskLocalState? =
        database.syncTaskLocalStateQueries.selectTask(taskId).executeAsOneOrNullAwait()?.let {
            SyncTaskLocalState(it.fingerprint, it.confirmedFingerprint, it.baselineEstablished != 0L,
                it.pendingRunId, it.confirmationNotified != 0L, it.scheduleTimeZone,
                json.decodeFromString<List<StorageDestination>>(it.fixedTargetsJson), it.fixedSourceBinding)
        }

    private suspend fun requireFingerprint(taskId: Long, fingerprint: String) {
        check(state(taskId)?.fingerprint == fingerprint) { "Synchronization definition changed" }
    }

    suspend fun reconcile(task: SyncTask): SyncTaskLocalState = locked {
        val fingerprint = syncDefinitionFingerprint(task)
        transactions.transactionWithResult {
            val current = state(task.id)
            database.syncRunDetailQueries.deleteOtherOwners(task.id, task.ownerDeviceId).await()
            if (current?.fingerprint == fingerprint) return@transactionWithResult current
            database.syncBaselineEntryQueries.deleteTask(task.id).await()
            database.syncPendingDeletionQueries.deleteTask(task.id).await()
            database.syncTaskLocalStateQueries.upsert(task.id, fingerprint, "", 0, 0, 0).await()
            SyncTaskLocalState(fingerprint)
        }
    }

    suspend fun localState(taskId: Long): SyncTaskLocalState? = locked { state(taskId) }

    suspend fun saveScheduleTimeZone(taskId: Long, fingerprint: String, timeZone: TimeZone) = locked {
        transactions.transaction {
            requireFingerprint(taskId, fingerprint)
            database.syncTaskLocalStateQueries.setScheduleTimeZone(timeZone.id, taskId).await()
        }
    }

    suspend fun reconcileTasks(tasks: List<SyncTask>) {
        val ids = tasks.map { it.id }.toSet()
        val obsolete = locked { database.syncTaskLocalStateQueries.selectTaskIds().executeAsListAwait().filter { it !in ids } }
        obsolete.forEach { deleteTaskState(it) }
        tasks.forEach { reconcile(it) }
    }

    suspend fun confirmPreview(taskId: Long, fingerprint: String, fixedTargets: List<StorageDestination> = emptyList(),
        fixedSourceBinding: String = "") = locked {
        transactions.transaction {
            requireFingerprint(taskId, fingerprint)
            database.syncTaskLocalStateQueries.confirmPreview(json.encodeToString(fixedTargets), fixedSourceBinding, taskId).await()
        }
    }

    suspend fun invalidateFixedTargets(taskId: Long, fingerprint: String) = locked {
        transactions.transaction {
            requireFingerprint(taskId, fingerprint)
            database.syncBaselineEntryQueries.deleteTask(taskId).await()
            database.syncPendingDeletionQueries.deleteTask(taskId).await()
            database.syncTaskLocalStateQueries.resetConfirmation(taskId).await()
        }
    }

    suspend fun markConfirmationNotified(taskId: Long, fingerprint: String): Boolean = locked {
        transactions.transactionWithResult {
            requireFingerprint(taskId, fingerprint)
            if (state(taskId)!!.confirmationNotified) return@transactionWithResult false
            database.syncTaskLocalStateQueries.setConfirmationNotified(taskId).await()
            true
        }
    }

    suspend fun baselinePage(taskId: Long, fingerprint: String, offset: Long = 0, limit: Int = 500): List<SyncBaseline> = locked {
        requireFingerprint(taskId, fingerprint)
        database.syncBaselineEntryQueries.selectPage(taskId, fingerprint, limit.coerceIn(1, 500).toLong(), offset.coerceAtLeast(0))
            .executeAsListAwait().map { json.decodeFromString<SyncBaseline>(it.payload) }
    }

    suspend fun baselineEntry(taskId: Long, fingerprint: String, relativePath: String): SyncBaseline? = locked {
        requireFingerprint(taskId, fingerprint)
        database.syncBaselineEntryQueries.selectPath(taskId, fingerprint, relativePath).executeAsOneOrNullAwait()
            ?.let { json.decodeFromString<SyncBaseline>(it.payload) }
    }

    suspend fun replacePendingDeletions(taskId: Long, fingerprint: String, runId: Long, items: List<SyncPendingDelete>) = locked {
        transactions.transaction {
            requireFingerprint(taskId, fingerprint)
            database.syncPendingDeletionQueries.deleteTask(taskId).await()
            for (item in items) database.syncPendingDeletionQueries.upsert(taskId, item.participant, item.relativePath,
                fingerprint, runId, json.encodeToString(item)).await()
            database.syncTaskLocalStateQueries.setPendingRun(if (items.isEmpty()) 0 else runId, taskId).await()
        }
    }

    suspend fun pendingDeletionPage(taskId: Long, fingerprint: String, offset: Long = 0, limit: Int = 500): List<SyncPendingDelete> = locked {
        requireFingerprint(taskId, fingerprint)
        database.syncPendingDeletionQueries.selectPage(taskId, fingerprint, limit.coerceIn(1, 500).toLong(), offset.coerceAtLeast(0))
            .executeAsListAwait().map { json.decodeFromString<SyncPendingDelete>(it.payload) }
    }

    suspend fun pendingDeletionCount(taskId: Long): Long = locked {
        database.syncPendingDeletionQueries.countTask(taskId).executeAsOneAwait()
    }

    suspend fun clearPendingDeletions(taskId: Long, fingerprint: String, expectedRunId: Long): Boolean = locked {
        transactions.transactionWithResult {
            requireFingerprint(taskId, fingerprint)
            if (expectedRunId <= 0 || state(taskId)!!.pendingRunId != expectedRunId) return@transactionWithResult false
            database.syncPendingDeletionQueries.deleteTask(taskId).await()
            database.syncTaskLocalStateQueries.setPendingRun(0, taskId).await()
            true
        }
    }

    suspend fun saveRecovery(record: SyncFileRecovery) = saveRecoveryGroup(listOf(record))

    suspend fun saveRecoveryGroup(records: List<SyncFileRecovery>) = locked {
        require(records.isNotEmpty()) { "A recovery group cannot be empty" }
        val first = records.first()
        require(records.map { it.id }.toSet().size == records.size && records.all {
            it.taskId == first.taskId && it.fingerprint == first.fingerprint && it.groupId == first.groupId &&
                it.ownerDeviceId == first.ownerDeviceId && it.logicalPath == first.logicalPath
        }) { "Recovery group bindings differ" }
        transactions.transaction {
            val savedGroup = database.syncFileRecoveryQueries.selectGroup(first.taskId, first.groupId).executeAsListAwait()
                .map { json.decodeFromString<SyncFileRecovery>(it.payload) }
            check(savedGroup.isEmpty() || records.all { record -> savedGroup.any { it.id == record.id } }) {
                "Cannot append operations to an existing recovery group"
            }
            for (record in records) {
                val existing = database.syncFileRecoveryQueries.selectRecord(record.id).executeAsOneOrNullAwait()
                    ?.let { json.decodeFromString<SyncFileRecovery>(it.payload) }
                if (existing == null) {
                    requireFingerprint(record.taskId, record.fingerprint)
                    check(record.phase == SyncRecoveryPhase.Prepared && record.attempt == 0 && record.restoredState == null) {
                        "New recovery operations must start prepared"
                    }
                }
                else {
                    check(record.sameRecoveryOperation(existing)) { "Recovery operation changed" }
                    val forward = record.attempt == existing.attempt && record.phase.ordinal >= existing.phase.ordinal
                    val restored = record.attempt == existing.attempt + 1 && existing.phase <= SyncRecoveryPhase.OldPreserved &&
                        record.phase == SyncRecoveryPhase.Prepared && record.restoredState != null && record.oldStoredState == null && record.placedState == null
                    check(forward || restored) { "Recovery phase cannot move backwards without a verified restoration" }
                }
                database.syncFileRecoveryQueries.upsert(record.id, record.taskId, record.fingerprint, record.groupId, json.encodeToString(record)).await()
            }
        }
    }

    suspend fun recoveries(taskId: Long? = null): List<SyncFileRecovery> = locked {
        val rows = if (taskId == null) database.syncFileRecoveryQueries.selectAll().executeAsListAwait()
            else database.syncFileRecoveryQueries.selectTask(taskId).executeAsListAwait()
        rows.map { json.decodeFromString<SyncFileRecovery>(it.payload) }
    }

    suspend fun removeRecovery(id: String) = locked {
        transactions.transaction { database.syncFileRecoveryQueries.deleteRecord(id).await() }
    }

    suspend fun commitVerified(
        taskId: Long,
        fingerprint: String,
        entries: List<SyncBaseline>,
        removedPaths: Set<String> = emptySet(),
        completedRecoveryIds: Set<String> = emptySet(),
    ) = locked {
        require(entries.size + removedPaths.size <= 500) { "A synchronization baseline batch is limited to 500 entries" }
        transactions.transaction {
            requireFingerprint(taskId, fingerprint)
            if (completedRecoveryIds.isNotEmpty()) {
                val groups = mutableSetOf<String>()
                for (ids in completedRecoveryIds.toList().chunked(400)) {
                    groups += database.syncFileRecoveryQueries.selectRecords(ids).executeAsListAwait()
                        .filter { it.taskId == taskId && it.fingerprint == fingerprint }.map { it.groupId }
                }
                val paths = entries.mapTo(mutableSetOf()) { it.relativePath } + removedPaths
                for (ids in groups.toList().chunked(400)) {
                    val records = database.syncFileRecoveryQueries.selectGroups(taskId, fingerprint, ids).executeAsListAwait()
                        .map { json.decodeFromString<SyncFileRecovery>(it.payload) }
                    check(records.all { it.id in completedRecoveryIds && it.phase == SyncRecoveryPhase.Verified && it.logicalPath in paths }) {
                        "Recovery groups must be verified and committed together"
                    }
                }
            }
            for (entry in entries) database.syncBaselineEntryQueries.upsert(taskId, entry.relativePath,
                fingerprint, json.encodeToString(entry)).await()
            for (path in removedPaths) database.syncBaselineEntryQueries.deletePath(taskId, path).await()
            for (id in completedRecoveryIds) database.syncFileRecoveryQueries.deleteVerifiedRecord(id, taskId, fingerprint).await()
            database.syncTaskLocalStateQueries.setBaselineEstablished(taskId).await()
        }
    }

    suspend fun appendRunDetails(taskId: Long, ownerDeviceId: String, runId: Long, offset: Long, items: List<SyncRunItem>) = locked {
        require(offset >= 0 && items.size <= 500) { "Run detail batches are limited to 500 rows" }
        transactions.transaction {
            items.forEachIndexed { index, item -> database.syncRunDetailQueries.upsert(taskId, ownerDeviceId, runId,
                offset + index, item.kind.name, if (item.success) 1 else 0, json.encodeToString(item)).await() }
        }
    }

    suspend fun runDetailPage(taskId: Long, ownerDeviceId: String, runId: Long, through: Long, offset: Long = 0,
        limit: Int = 100, kind: SyncRunItemKind? = null): List<SyncRunDetailRow> = locked {
        val pageSize = limit.coerceIn(1, 500).toLong()
        if (kind == null) database.syncRunDetailQueries.selectPage(taskId, ownerDeviceId, runId, through.coerceAtLeast(0), pageSize, offset.coerceAtLeast(0))
            .executeAsListAwait().map { SyncRunDetailRow(it.ordinal, json.decodeFromString(it.payload)) }
        else database.syncRunDetailQueries.selectKindPage(taskId, ownerDeviceId, runId, through.coerceAtLeast(0), kind.name, pageSize, offset.coerceAtLeast(0))
            .executeAsListAwait().map { SyncRunDetailRow(it.ordinal, json.decodeFromString(it.payload)) }
    }

    suspend fun deleteRunDetails(taskId: Long, ownerDeviceId: String, runId: Long) = locked {
        database.syncRunDetailQueries.deleteRun(taskId, ownerDeviceId, runId).await()
    }

    suspend fun deleteRunDetails(taskId: Long, ownerDeviceId: String, runIds: Set<Long>) = locked {
        if (runIds.isNotEmpty()) database.syncRunDetailQueries.deleteRuns(taskId, ownerDeviceId, runIds).await()
    }

    suspend fun pruneRunDetails(taskId: Long, ownerDeviceId: String, retainedRunIds: Set<Long>) = locked {
        require(retainedRunIds.isNotEmpty() && retainedRunIds.size <= RUN_HISTORY_LIMIT)
        database.syncRunDetailQueries.pruneRuns(taskId, ownerDeviceId, retainedRunIds).await()
    }

    suspend fun recoveryGroup(taskId: Long, groupId: String): List<SyncFileRecovery> = locked {
        database.syncFileRecoveryQueries.selectGroup(taskId, groupId).executeAsListAwait().map { json.decodeFromString(it.payload) }
    }

    suspend fun recoveryPage(taskId: Long, offset: Long = 0, limit: Int = 100): List<SyncFileRecovery> = locked {
        database.syncFileRecoveryQueries.selectTaskPage(taskId, limit.coerceIn(1, 500).toLong(), offset.coerceAtLeast(0))
            .executeAsListAwait().map { json.decodeFromString(it.payload) }
    }

    suspend fun deleteTaskState(taskId: Long) = locked {
        transactions.transaction {
            database.syncBaselineEntryQueries.deleteTask(taskId).await()
            database.syncPendingDeletionQueries.deleteTask(taskId).await()
            database.syncTaskLocalStateQueries.deleteTask(taskId).await()
            database.syncRunDetailQueries.deleteTask(taskId).await()
        }
    }
}
