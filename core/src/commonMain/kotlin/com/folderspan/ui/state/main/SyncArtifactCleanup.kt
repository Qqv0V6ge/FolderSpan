package com.folderspan.ui.state.main

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val syncArtifactGeneration = Regex("([1-9][0-9]*)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Names encode creation time because a staged copy can retain a source timestamp from years ago. */
private fun expiredSyncArtifact(name: String, now: Long, ageMillis: Long): Boolean {
    val created = syncArtifactGeneration.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() ?: return false
    return created in 1..now && now - created > ageMillis
}

/** Only this device/task namespace is listed; unknown, linked or journal-referenced content is retained. */
internal suspend fun cleanupSyncTaskArtifacts(
    task: SyncTask,
    endpoints: Map<SyncRecoveryEndpoint, String>,
    io: SyncRecoveryEndpointOperations,
    now: Long,
    recoveries: suspend () -> List<SyncFileRecovery>,
    ensureRunning: suspend () -> Unit = {},
): List<String> {
    require(task.ownerDeviceId.isNotBlank() && task.id > 0 && task.recycleRetentionDays in 1..365)
    val removed = mutableListOf<String>()
    suspend fun running() { currentCoroutineContext().ensureActive(); ensureRunning() }
    for ((endpoint, separator) in endpoints) {
        fun overlaps(path: String, reference: String): Boolean {
            if (reference.isEmpty()) return false
            val left = normalizePathForCompare(path, separator).trimEnd('/', '\\')
            val right = normalizePathForCompare(reference, separator).trimEnd('/', '\\')
            return left == right || left.startsWith(right + separator) || right.startsWith(left + separator)
        }
        suspend fun referenced(path: String): Boolean = recoveries().any { record ->
            val locations = listOf(SyncRecoveryLocation(record.endpoint, record.finalPath),
                SyncRecoveryLocation(record.endpoint, record.temporaryPath), SyncRecoveryLocation(record.endpoint, record.oldPath)) +
                listOfNotNull(record.sourceEndpoint?.let { SyncRecoveryLocation(it, record.sourcePath) }) + record.participantLocations.values
            locations.any { it.endpoint.type == endpoint.type && it.endpoint.ref == endpoint.ref && overlaps(path, it.path) }
        }
        suspend fun remove(path: String, directory: Boolean) {
            running()
            if (referenced(path)) return
            val tree = if (directory) io.verifiedTree(endpoint, path) else emptyMap()
            val state = if (directory) tree.getValue("") else io.verifiedState(endpoint, path) ?: return
            running()
            if (referenced(path)) return
            io.remove(endpoint, path, state, tree)
            removed += path
        }
        running()
        val recycle = joinPath(endpoint.rootPath, ".folderspan-recycle/${syncRecycleOwnerKey(task)}/${task.id}", separator)
        for (group in io.children(endpoint, recycle)) {
            running()
            if (!group.isSymbolicLink && group.isDirectory &&
                expiredSyncArtifact(group.name, now, task.recycleRetentionDays * 86_400_000L)) remove(group.path, true)
        }
        val prefix = ".folderspan-tmp-${syncTaskKey(task)}-"
        for (file in io.children(endpoint, endpoint.rootPath)) {
            running()
            // An orphaned old version is never proven disposable merely because its journal is missing.
            if (!file.isSymbolicLink && !file.isDirectory && file.name.startsWith(prefix) && file.name.endsWith("-new") &&
                expiredSyncArtifact(file.name.removePrefix(prefix).removeSuffix("-new"), now, 86_400_000L)) remove(file.path, false)
        }
    }
    return removed
}
