package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ignore.ResolvedIgnoreMatcher

/** Maps virtual source aliases to selected physical paths without widening the normal write scope. */
internal class SyncSourceScope(
    private val root: FileSimpleInfo,
    private val selections: List<FileSimpleInfo>,
    private val separator: String,
) {
    init {
        require(selections.isNotEmpty() || root.isDirectory) { "Source root is not a directory" }
        require(selections.all { it.protocol == root.protocol && it.protocolId == root.protocolId }) { "Source endpoints differ" }
        require(selections.map { it.name }.toSet().size == selections.size) { "Source aliases are ambiguous" }
        for (file in selections) require(safeRelative(file.name) && '/' !in file.name) { "Invalid source alias" }
        selections.forEachIndexed { index, first ->
            for (second in selections.drop(index + 1)) require(
                normalizePathForCompare(first.path, separator) != normalizePathForCompare(second.path, separator) &&
                    !(first.isDirectory && syncTargetInsideSourcePath(first.path, second.path, separator)) &&
                    !(second.isDirectory && syncTargetInsideSourcePath(second.path, first.path, separator))
            ) { "Selected sources overlap" }
        }
    }

    fun contains(relativePath: String): Boolean = safeRelative(relativePath) && (selections.isEmpty() || selections.any { selected ->
        relativePath == selected.name || selected.isDirectory && relativePath.startsWith(selected.name + "/")
    })

    val directoryAliases: Set<String> = selections.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.name }
    val selectionTypes: Map<String, Boolean> = selections.associate { it.name to it.isDirectory }

    fun relativeWithinSelection(path: String): String = syncRelativeWithinSelection(path, selections)

    fun ignored(path: String, directory: Boolean, matchers: Map<String, ResolvedIgnoreMatcher?>): Boolean {
        if (!contains(path) || syncReservedPath(path)) return true
        val prefix = if (selections.isEmpty()) "" else path.substringBefore('/')
        val withinRoot = if (prefix.isEmpty()) path else path.removePrefix(prefix).trimStart('/')
        return matchers[prefix]?.matchesRelativeOperationSkip(withinRoot, directory) == true
    }

    fun location(relativePath: String, conflict: SyncPlannedOperation? = null): FileSimpleInfo {
        require(safeRelative(relativePath)) { "Invalid synchronization path" }
        require(conflict == null || conflict.kind == SyncOperationKind.ConflictCopy && conflict.participant == "source" &&
            conflict.relativePath == relativePath) { "Path is not authorized by the conflict plan" }
        val conflictFor = conflict?.sourceRelativePath
        if (selections.isEmpty()) return root.withCopy(path = joinPath(root.path, relativePath, separator))
        val selected = selections.firstOrNull { relativePath == it.name || it.isDirectory && relativePath.startsWith(it.name + "/") }
        if (selected != null) {
            require(conflictFor == null || conflictFor == selected.name || selected.isDirectory && conflictFor.startsWith(selected.name + "/")) {
                "Conflict path belongs to another selected source"
            }
            if (relativePath == selected.name) return selected
            return selected.withCopy(path = joinPath(selected.path, relativePath.removePrefix(selected.name + "/"), separator))
        }
        val original = selections.singleOrNull { !it.isDirectory && conflictFor == it.name }
        require(original != null && '/' !in relativePath) { "Path is outside the selected source scope" }
        return original.withCopy(path = joinPath(parentPathOf(original.path, separator), relativePath, separator))
    }

    fun artifactRoot(relativePath: String, conflict: SyncPlannedOperation? = null): FileSimpleInfo {
        location(relativePath, conflict)
        val conflictFor = conflict?.sourceRelativePath
        if (selections.isEmpty()) return root
        val selected = selections.firstOrNull { relativePath == it.name || it.isDirectory && relativePath.startsWith(it.name + "/") }
            ?: selections.singleOrNull { !it.isDirectory && conflictFor == it.name && '/' !in relativePath }
            ?: error("Recovery path is outside the selected source scope")
        return if (selected.isDirectory) selected else selected.withCopy(path = parentPathOf(selected.path, separator), isDirectory = true)
    }

    private fun safeRelative(path: String): Boolean = path.isNotEmpty() && '\\' !in path &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." && '\u0000' !in it }
}

internal fun syncRelativeWithinSelection(path: String, selections: List<FileSimpleInfo>): String {
    val selected = selections.firstOrNull { it.isDirectory && (path == it.name || path.startsWith(it.name + "/")) } ?: return path
    return path.removePrefix(selected.name).trimStart('/')
}
