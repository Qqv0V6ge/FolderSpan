package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.fileOperationKey
import korlibs.crypto.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun syncTaskKey(task: SyncTask): String =
    syncRecycleOwnerKey(task) + "-" + task.id

internal fun syncRecycleOwnerKey(task: SyncTask): String = task.ownerDeviceId.encodeToByteArray().sha256().hexLower.take(24)

internal fun syncReservedPath(relative: String): Boolean = relative.split('/').any { part ->
    val name = part.lowercase()
    name == ".folderspan-recycle" || name.startsWith(".folderspan-tmp-") ||
        name.startsWith(".folderspan-") && name.endsWith(".part")
}

internal fun syncPathProtected(path: String, protected: Set<String>): Boolean {
    if ("" in protected) return true
    var current = path
    while (current.isNotEmpty()) {
        if (current in protected) return true
        current = current.substringBeforeLast('/', "")
    }
    return false
}

internal fun syncParentPaths(paths: Collection<String>): Set<String> = buildSet {
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && add(parent)) parent = parent.substringBeforeLast('/', "")
    }
}

internal data class SyncDirectoryScan(
    val entries: Map<String, FileSimpleInfo>,
    val occupied: Map<String, FileSimpleInfo>,
    val protectedPaths: Set<String>,
    val duplicateEntries: List<Pair<String, FileSimpleInfo>> = emptyList(),
    val nonEmptyDirectories: Set<String> = emptySet(),
)

/** The same rules and Ignore predicate are shared by every participating endpoint. */
internal suspend fun scanSyncDirectory(
    root: FileSimpleInfo,
    task: SyncTask,
    listChildren: suspend (FileSimpleInfo) -> Result<List<FileSimpleInfo>>,
    separator: String = "/",
    allowDuplicateSourceEntries: Boolean = false,
    ignored: (String, Boolean) -> Boolean = { _, _ -> false },
    relativePrefix: String = "",
    directoryAliases: Set<String> = emptySet(),
): Result<SyncDirectoryScan> = try {
    require(root.isDirectory && !root.isSymbolicLink) { "Sync root is unavailable or is not a directory" }
    require(relativePrefix.isEmpty() || relativePrefix.isNotBlank() && '/' !in relativePrefix && '\\' !in relativePrefix &&
        '\u0000' !in relativePrefix && relativePrefix !in listOf(".", "..")) { "Invalid source prefix" }
    val excludes = parseSyncFilterPatterns(task.filterRaw)
    val includes = parseSyncFilterPatterns(task.includeRaw)
    val entries = linkedMapOf<String, FileSimpleInfo>()
    val occupied = linkedMapOf<String, FileSimpleInfo>()
    val duplicates = mutableListOf<Pair<String, FileSimpleInfo>>()
    fun record(path: String, file: FileSimpleInfo) {
        if (path in entries) duplicates.add(path to file) else entries[path] = file
    }
    val protected = linkedSetOf<String>()
    if (relativePrefix.isNotEmpty()) {
        occupied[relativePrefix] = root
        if (syncReservedPath(relativePrefix) || syncFilterMatches(relativePrefix, relativePrefix, excludes) || ignored(relativePrefix, true)) {
            protected.add(relativePrefix)
        } else entries[relativePrefix] = root
    }
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<Pair<String, FileSimpleInfo>>()
    if (relativePrefix !in protected) queue.addLast(relativePrefix to root)
    while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (prefix, directory) = queue.removeFirst()
        check(visited.add(directory.fileOperationKey())) { "Directory traversal cycle" }
        val result = listChildren(directory)
        if (result.isFailure) {
            val error = result.exceptionOrNull()!!
            if (error is CancellationException || prefix == relativePrefix) throw error
            protected.add(prefix)
            continue
        }
        val children = result.getOrThrow()
        if (children.any { it.name.isBlank() || it.name in listOf(".", "..") || '/' in it.name || '\\' in it.name ||
                normalizePathForCompare(it.path, separator) != joinPath(directory.path, it.name, separator) } ||
            children.groupBy { it.name }.values.any { same ->
                same.size > 1 && (!allowDuplicateSourceEntries || same.any { it.storageReference?.id.isNullOrEmpty() } ||
                    same.map { it.storageReference?.id }.toSet().size != same.size)
            }) {
            if (prefix == relativePrefix) error("Directory entries are ambiguous or unsafe")
            protected.add(prefix)
            continue
        }
        for (child in children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            occupied[path] = child
            if (syncReservedPath(path) || child.isSymbolicLink ||
                syncFilterMatches(path, child.name, excludes) || ignored(path, child.isDirectory) ||
                child.isDirectory && !task.includeSubdirectories && path !in directoryAliases) {
                protected.add(path)
                continue
            }
            if (child.isDirectory) {
                record(path, child)
                queue.addLast(path to child)
            } else if (includes.isEmpty() || syncFilterMatches(path, child.name, includes)) {
                record(path, child)
            } else protected.add(path)
        }
    }
    val parents = mutableSetOf<String>()
    for ((path, file) in entries) if (!file.isDirectory) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && parents.add(parent)) parent = parent.substringBeforeLast('/', "")
    }
    val filtered = entries.filter { (path, file) ->
        !file.isDirectory || includes.isEmpty() || path in parents
    }
    Result.success(SyncDirectoryScan(filtered, occupied, protected, duplicates, parents))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
