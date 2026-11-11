package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo

internal enum class SyncUpdateRootAction { EnsureDirectory, ReplaceFile, Skip }

internal data class SyncUpdateRootPlan(
    val path: String,
    val expected: FileSimpleInfo?,
    val action: SyncUpdateRootAction,
)

internal data class SyncUpdateDirectoryPlan(
    val root: SyncUpdateRootPlan,
    val items: List<SyncPlanItem>,
    val ensureRoot: Boolean = true,
)

/** Freezes the root conflict without removing or creating anything at the endpoint. */
internal fun planUpdateRoot(
    task: SyncTask,
    target: SyncTargetSnapshot,
    separator: String,
    rootPath: String,
): SyncUpdateRootPlan {
    val existing = target.rootInfo
    if (existing == null || existing.isDirectory) return SyncUpdateRootPlan(rootPath, existing, SyncUpdateRootAction.EnsureDirectory)
    return when (task.conflictPolicy) {
        SyncConflictPolicy.Skip -> SyncUpdateRootPlan(rootPath, existing, SyncUpdateRootAction.Skip)
        SyncConflictPolicy.Replace -> SyncUpdateRootPlan(rootPath, existing, SyncUpdateRootAction.ReplaceFile)
        SyncConflictPolicy.Rename -> SyncUpdateRootPlan(
            buildSyncRenamePath(rootPath, true, target.occupiedPaths, separator), null, SyncUpdateRootAction.EnsureDirectory)
    }
}

/** The existing Update actions and its root bootstrap are computed before the first write. */
internal fun syncSelectedTypeConflicts(task: SyncTask, target: SyncTargetSnapshot, rootPath: String, separator: String): Set<String> =
    task.selectedSources.filter { selected ->
        val path = joinPath(rootPath, selected.name, separator)
        val directory = if (path in target.directoryPaths) true else if (path in target.filesByAbsolute) false else null
        directory != null && directory != selected.isDirectory
    }.mapTo(mutableSetOf()) { it.name }

internal fun planSync(
    task: SyncTask,
    source: SyncSourceSnapshot,
    target: SyncTargetSnapshot,
    targetSeparator: String,
    rootPath: String,
    recoverPendingWrite: Boolean = false,
    root: SyncUpdateRootPlan = planUpdateRoot(task, target, targetSeparator, rootPath),
    selectedTypeConflicts: Set<String> = syncSelectedTypeConflicts(task, target, root.path, targetSeparator),
): SyncUpdateDirectoryPlan {
    require(task.mode == SyncMode.Update)
    if (root.action == SyncUpdateRootAction.Skip) return SyncUpdateDirectoryPlan(root, emptyList())
    val blockedRoots = selectedTypeConflicts
    val protectedSource = source.copy(protectedPaths = source.protectedPaths + blockedRoots)
    val items = planDirectorySync(protectedSource, target, task, targetSeparator, root.path, recoverPendingWrite,
        scopeRelativePath = { syncRelativeWithinSelection(it, task.selectedSources) })
    if (task.destinations.isEmpty()) return SyncUpdateDirectoryPlan(root, items)
    val represented = items.filter { it.source != null }.mapTo(mutableSetOf()) { it.relativePath }
    val skipped = source.files.filter { it.relativePath !in represented }.map { entry ->
        SyncPlanItem(SyncPlanAction.Skip, entry.relativePath, joinPath(root.path, entry.relativePath, targetSeparator), entry.file,
            skipReason = if (syncPathProtected(entry.relativePath, blockedRoots)) SyncPlanSkipReason.SelectedTypeConflict else SyncPlanSkipReason.Protected)
    }
    val roots = task.selectedSources.filter { it.isDirectory && it.name in blockedRoots }.map { selected ->
        SyncPlanItem(SyncPlanAction.Skip, selected.name, joinPath(root.path, selected.name, targetSeparator), selected,
            skipReason = SyncPlanSkipReason.SelectedTypeConflict)
    }
    return SyncUpdateDirectoryPlan(root, roots + items + skipped)
}

/** Single-file Update keeps the configured filename instead of appending the source filename. */
internal fun planSync(
    task: SyncTask,
    source: FileSimpleInfo,
    target: SyncTargetSnapshot,
    targetPath: String,
    targetSeparator: String,
): SyncUpdateDirectoryPlan {
    val parent = parentPathOf(targetPath, targetSeparator)
    val root = SyncUpdateRootPlan(parent, target.rootInfo, SyncUpdateRootAction.EnsureDirectory)
    val planned = planSync(task,
        SyncSourceSnapshot(listOf(SyncSourceFileEntry(fileNameOf(targetPath, targetSeparator), source)), emptyList()),
        target, targetSeparator, parent, root = root)
    val items = planned.items.map { it.copy(relativePath = source.name) }
    return planned.copy(items = items, ensureRoot = items.any { it.action == SyncPlanAction.Copy || it.action == SyncPlanAction.RenameCopy })
}

/** Projects the legacy actions into the operation definition used by every preview. */
internal fun SyncUpdateDirectoryPlan.operations(
    sourceId: String,
    targetId: String,
    target: SyncTargetSnapshot,
    separator: String,
    expectedLocations: Map<String, FileSimpleInfo?> = emptyMap(),
    sourceDirectories: Map<String, FileSimpleInfo> = emptyMap(),
): List<SyncPathPlan> = items.map { item ->
    val destination = item.targetPath.removePrefix(root.path).trimStart(*separator.toCharArray()).replace(separator, "/")
    val existing = expectedLocations[item.targetPath]?.syncEntryState() ?: target.filesByAbsolute[item.targetPath]?.syncEntryState()
        ?: if (item.targetPath in target.directoryPaths) SyncEntryState(true, 0, 0) else null
    val sourceState = (item.source ?: sourceDirectories[item.relativePath])?.syncEntryState()
    val operation = when (item.action) {
        SyncPlanAction.CreateDirectory -> SyncPlannedOperation(SyncOperationKind.CreateDirectory, targetId, destination,
            sourceId.takeIf { sourceState != null }, item.relativePath, sourceState, existing)
        SyncPlanAction.Copy, SyncPlanAction.RenameCopy -> SyncPlannedOperation(
            if (item.action == SyncPlanAction.RenameCopy) SyncOperationKind.ConflictCopy
            else if (item.targetExisted) SyncOperationKind.Overwrite else SyncOperationKind.Copy,
            targetId, destination, sourceId, item.relativePath, item.source?.syncEntryState(), existing)
        SyncPlanAction.Unchanged, SyncPlanAction.Skip -> null
    }
    SyncPathPlan(item.relativePath, mapOf(sourceId to sourceState, targetId to existing),
        listOfNotNull(operation), skipped = item.action == SyncPlanAction.Skip,
        conflict = item.skipReason == SyncPlanSkipReason.SelectedTypeConflict)
}

internal fun planUpdateParticipants(
    task: SyncTask,
    participants: List<SyncParticipantScan>,
    ignored: (String, Boolean) -> Boolean,
    scopeRelativePath: (String) -> String,
    selectedSourceTypes: Map<String, Boolean>,
): SyncPlan {
    val source = participants.first()
    val protected = participants.flatMapTo(mutableSetOf()) { it.scan.protectedPaths }
    val typeConflicts = mutableSetOf<String>()
    for ((path, directory) in selectedSourceTypes) if (participants.any {
        it.scan.entries[path]?.isDirectory?.let { actual -> actual != directory } == true
    }) { protected.add(path); typeConflicts.add(path) }
    val includes = parseSyncFilterPatterns(task.includeRaw)
    val excludes = parseSyncFilterPatterns(task.filterRaw)
    fun allowed(path: String, directory: Boolean): Boolean {
        if (path.startsWith('/') || '\\' in path || path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            syncReservedPath(path) || !task.includeSubdirectories && '/' in scopeRelativePath(path)) return false
        var current = path
        while (current.isNotEmpty()) {
            if (syncFilterMatches(current, current.substringAfterLast('/'), excludes) || ignored(current, current != path || directory)) return false
            current = current.substringBeforeLast('/', "")
        }
        return directory || includes.isEmpty() || syncFilterMatches(path, path.substringAfterLast('/'), includes)
    }
    val entries = (source.scan.entries.toList() + source.scan.duplicateEntries).filter { (path, file) -> allowed(path, file.isDirectory) }
    val snapshot = SyncSourceSnapshot(entries.filter { !it.second.isDirectory }.map { SyncSourceFileEntry(it.first, it.second) },
        entries.filter { it.second.isDirectory }.map { it.first }, protectedPaths = protected)
    val planned = participants.drop(1).flatMap { participant ->
        val files = participant.scan.occupied.filterValues { !it.isDirectory }.mapKeys { "/" + it.key }.toMutableMap()
        val directories = participant.scan.occupied.filterValues { it.isDirectory }.keys.mapTo(mutableSetOf()) { "/$it" }
        val target = SyncTargetSnapshot(null, mutableMapOf(), files, directories,
            participant.scan.occupied.keys.mapTo(mutableSetOf()) { "/$it" }, protected)
        planSync(task, snapshot, target, "/", "/").operations(source.id, participant.id, target, "/", sourceDirectories = entries.filter { it.second.isDirectory }.toMap())
    }
    val represented = planned.mapTo(mutableSetOf()) { it.relativePath }
    val remaining = participants.flatMap { it.scan.entries.keys }.toSet().filter { it !in represented }.sorted().map { path ->
        val states = participants.associate { it.id to it.state(path) }
        val directory = states.values.firstNotNullOfOrNull { it }?.isDirectory ?: false
        SyncPathPlan(path, states, emptyList(), conflict = path in typeConflicts,
            skipped = !allowed(path, directory) || syncPathProtected(path, protected))
    }
    return SyncPlan(planned + remaining)
}
