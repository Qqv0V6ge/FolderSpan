package com.folderspan.ui.state.main

internal data class SyncProtectedRunPlan(
    val plan: SyncPlan,
    val pendingDeletions: List<SyncPendingDelete>,
    val unexpectedlyEmptyScopes: List<Pair<String, String>>,
)

internal fun SyncPlannedOperation.pendingDeletion(path: SyncPathPlan): SyncPendingDelete? =
    takeIf { kind in listOf(SyncOperationKind.Delete, SyncOperationKind.CleanEmptyDirectory) }
        ?.let { SyncPendingDelete(participant, relativePath, path.expected) }

/** Counts the whole participating group and keeps pending paths out of baseline acceptance. */
internal fun protectSyncRunPlan(
    prepared: SyncPreparedRun,
    approvedDeletions: Set<SyncPendingDelete> = emptySet(),
): SyncProtectedRunPlan {
    val task = prepared.task
    val preparation = prepared.preparation
    val deletions = preparation.plan.paths.filter { !it.skipped }.flatMap { path -> path.operations.mapNotNull { it.pendingDeletion(path) } }
    val pending = if (task.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional) && deletions.size > task.deleteThreshold)
        deletions.filter { it !in approvedDeletions } else emptyList()
    val deferred = pending.mapTo(mutableSetOf()) { it.relativePath }
    val plan = SyncPlan(preparation.plan.paths.map { if (it.relativePath in deferred) it.copy(operations = emptyList(), skipped = true) else it })
    val scopes = prepared.sourceScope.selectionTypes.keys.ifEmpty { setOf("") }
    val checked = when (task.mode) {
        SyncMode.Mirror -> preparation.participants.take(1)
        SyncMode.Bidirectional -> preparation.participants
        else -> emptyList()
    }
    val empty = mutableListOf<Pair<String, String>>()
    if (deletions.isNotEmpty()) for (participant in checked) for (scope in scopes) {
        fun inside(path: String, directory: Boolean): Boolean = if (scope.isEmpty()) true else
            path.startsWith("$scope/") || path == scope && !directory
        val hadEntries = preparation.baselines.values.any { baseline ->
            baseline.participants[participant.id]?.let { inside(baseline.relativePath, it.isDirectory) } == true
        }
        val hasEntries = participant.scan.entries.any { (path, file) -> inside(path, file.isDirectory) }
        if (hadEntries && !hasEntries) empty += participant.id to scope
    }
    return SyncProtectedRunPlan(plan, pending, empty)
}
