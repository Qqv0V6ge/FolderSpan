package com.folderspan.service.network

internal class StorageContentRevisionConflictException(val entryId: String, val revisions: List<String>) :
    StorageContentException(StorageContentErrorCode.Conflict,
        "This entry has concurrent revisions; preserve all branches and resolve the conflict")

/** Replays authenticated append records; no clock or backend listing order chooses a winner. */
internal class StorageContentHistory private constructor(
    val vaultId: String,
    val records: Map<String, StorageContentCommit>,
    val heads: Map<String, List<StorageContentCommit>>,
) {
    fun head(entryId: String): StorageContentCommit? {
        val revisions = heads[entryId].orEmpty()
        if (revisions.size > 1) throw StorageContentRevisionConflictException(entryId, revisions.map { it.operationId })
        return revisions.singleOrNull()
    }

    fun liveEntries(): List<StorageContentCommit> {
        val live = heads.keys.mapNotNull(::head).filter {
            it.kind != StorageContentCommitKind.Root && it.kind != StorageContentCommitKind.Delete
        }
        check(live.groupBy { it.parentId to it.name }.values.none { it.size > 1 }) {
            "Concurrent entries have the same logical name; resolve the name conflict"
        }
        live.forEach(::path)
        return live
    }

    fun path(record: StorageContentCommit): String {
        val names = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = record
        var length = 0
        while (current.kind != StorageContentCommitKind.Root) {
            check(visited.add(current.entryId)) { "Content directory hierarchy contains a cycle" }
            check(current.kind != StorageContentCommitKind.Delete) { "A deleted directory still has live children" }
            names.add(current.name)
            length += current.name.length + 1
            check(length <= 16 * 1024) { "Content logical path is too long" }
            val parent = head(current.parentId) ?: error("Content parent directory is missing")
            check(parent.kind == StorageContentCommitKind.Directory || parent.kind == StorageContentCommitKind.Root) {
                "Content parent is not a live directory; preserve its conflicting children"
            }
            current = parent
        }
        return names.asReversed().joinToString("/", prefix = "/")
    }

    companion object {
        // shortcut: replay is bounded to 100,000 records, add paged compaction when real locations reach this limit.
        const val MAX_RECORDS = 100_000

        fun replay(vaultId: String, input: List<StorageContentCommit>): StorageContentHistory {
            require(input.size in 1..MAX_RECORDS) { "Content history is missing or exceeds the replay limit" }
            input.forEach {
                it.requireValid()
                require(it.vaultId == vaultId) { "Content history contains another location" }
            }
            val records = input.associateBy { it.operationId }
            check(records.size == input.size) { "Content history repeats an operation identity" }
            val roots = input.filter { it.kind == StorageContentCommitKind.Root }
            check(roots.size == 1 && roots.single().operationId == vaultId) { "Content history root is invalid" }
            val entries = input.groupBy { it.entryId }
            val children = mutableMapOf<String, MutableList<String>>()
            val remainingParents = mutableMapOf<String, Int>()
            val ready = ArrayDeque<String>()
            input.forEach { record ->
                if (record.kind != StorageContentCommitKind.Root) {
                    check(record.parentId in entries) { "Content history parent directory is missing" }
                    check(record.kind != StorageContentCommitKind.Delete || record.parents.isNotEmpty()) {
                        "Content deletion must reference observed revisions"
                    }
                }
                remainingParents[record.operationId] = record.parents.size
                if (record.parents.isEmpty()) ready.addLast(record.operationId)
                record.parents.forEach { parentId ->
                    val parent = records[parentId] ?: error("Content history parent revision is missing")
                    check(parent.entryId == record.entryId) { "Content revision belongs to another entry" }
                    children.getOrPut(parentId) { mutableListOf() }.add(record.operationId)
                }
            }
            var visited = 0
            while (ready.isNotEmpty()) {
                val id = ready.removeFirst()
                visited++
                children[id].orEmpty().forEach { child ->
                    val count = remainingParents.getValue(child) - 1
                    remainingParents[child] = count
                    if (count == 0) ready.addLast(child)
                }
            }
            check(visited == input.size) { "Content revision graph contains a cycle" }
            val consumed = input.flatMap { it.parents }.toSet()
            val heads = entries.mapValues { (_, revisions) -> revisions.filter { it.operationId !in consumed } }
            return StorageContentHistory(vaultId, records, heads)
        }
    }
}
