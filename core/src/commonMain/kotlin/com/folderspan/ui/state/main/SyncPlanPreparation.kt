package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SyncPreparedPlan(
    val participants: List<SyncParticipantScan>,
    val baselines: Map<String, SyncBaseline>,
    val plan: SyncPlan,
    val comparisonReadBytes: Long,
)

/** Read-only preparation shared by previews and execution; a failed baseline read never means an empty baseline. */
internal suspend fun prepareSyncPlan(
    task: SyncTask,
    participants: List<SyncParticipantScan>,
    conflictStamp: String,
    readBaselinePage: suspend (offset: Long) -> List<SyncBaseline>,
    readContent: suspend (participant: String, file: FileSimpleInfo, onRead: (Int) -> Unit) -> Result<SyncEntryState>,
    ignored: (String, Boolean) -> Boolean = { _, _ -> false },
    conflictWord: String = "conflict",
    onComparisonRead: (Long) -> Unit = {},
    scopeRelativePath: (String) -> String = { it },
    selectedSourceTypes: Map<String, Boolean> = emptyMap(),
): Result<SyncPreparedPlan> = try {
    val baselines = linkedMapOf<String, SyncBaseline>()
    if (task.mode != SyncMode.Update) {
        var offset = 0L
        do {
            currentCoroutineContext().ensureActive()
            val page = readBaselinePage(offset)
            check(page.size <= 500) { "Synchronization baseline page exceeds its limit" }
            for (entry in page) check(baselines.put(entry.relativePath, entry) == null) { "Duplicate synchronization baseline" }
            offset += page.size
        } while (page.size == 500)
    }
    var readBytes = 0L
    val verified = participants.associate { it.id to it.verifiedStates.toMutableMap() }
    if (task.mode != SyncMode.Update) {
        val paths = participants.flatMap { it.scan.entries.keys }.toSet()
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            val files = participants.mapNotNull { participant ->
                participant.scan.entries[path]?.takeIf { !it.isDirectory && !it.isSymbolicLink && it.size >= 0 }
                    ?.let { participant.id to it }
            }
            val baseline = baselines[path]
            val firstMerge = task.mode == SyncMode.Bidirectional && (baseline == null || participants.any { it.id !in baseline.participants })
            val uncertainSizes = files.groupBy { it.second.size }.filterValues { sameSize ->
                sameSize.any { it.second.updatedDate <= 0 } || firstMerge && sameSize.size > 1 &&
                    sameSize.any { !syncSameContent(sameSize.first().second.syncEntryState(), it.second.syncEntryState()) }
            }.keys
            for ((participant, file) in files) {
                if (file.size !in uncertainSizes) continue
                val read = readContent(participant, file) { bytes ->
                    require(bytes >= 0)
                    readBytes += bytes.toLong().coerceAtMost(Long.MAX_VALUE - readBytes)
                    onComparisonRead(readBytes)
                }
                (read.exceptionOrNull() as? CancellationException)?.let { throw it }
                currentCoroutineContext().ensureActive()
                val state = read.getOrNull() ?: continue
                if (state.copy(contentSha256 = "") == file.syncEntryState() &&
                    state.contentSha256.length == 64 && state.contentSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                    verified.getValue(participant)[path] = state
                }
            }
        }
    }
    val enriched = participants.map { it.copy(verifiedStates = verified.getValue(it.id)) }
    Result.success(SyncPreparedPlan(enriched, baselines,
        planSync(task, enriched, baselines, conflictStamp, ignored = ignored, conflictWord = conflictWord,
            scopeRelativePath = scopeRelativePath, selectedSourceTypes = selectedSourceTypes), readBytes))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
