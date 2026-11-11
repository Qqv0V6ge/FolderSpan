package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo

internal data class SyncParticipantScan(val id: String, val label: String, val scan: SyncDirectoryScan,
    val verifiedStates: Map<String, SyncEntryState> = emptyMap()) {
    fun state(path: String): SyncEntryState? {
        val metadata = (scan.entries[path] ?: scan.occupied[path])?.syncEntryState() ?: return null
        return verifiedStates[path]?.takeIf { it.copy(contentSha256 = "") == metadata } ?: metadata
    }
}
internal enum class SyncOperationKind { CreateDirectory, Copy, Overwrite, Delete, CleanEmptyDirectory, CleanSource, ConflictCopy }
internal data class SyncPlannedOperation(
    val kind: SyncOperationKind,
    val participant: String,
    val relativePath: String,
    val sourceParticipant: String? = null,
    val sourceRelativePath: String = relativePath,
    val expectedSource: SyncEntryState? = null,
    val expectedTarget: SyncEntryState? = null,
)
internal data class SyncPathPlan(
    val relativePath: String,
    val expected: Map<String, SyncEntryState?>,
    val operations: List<SyncPlannedOperation>,
    val backupPaths: Map<String, String> = emptyMap(),
    val conflict: Boolean = false,
    val skipped: Boolean = false,
    val removedWithParent: String? = null,
)
internal data class SyncPlan(val paths: List<SyncPathPlan>) {
    val operations: List<SyncPlannedOperation> = paths.flatMap { it.operations }
}
internal data class SyncContentMatch(val relativePath: String, val left: String, val right: String,
    val leftState: SyncEntryState, val rightState: SyncEntryState)

internal fun FileSimpleInfo.syncEntryState() = SyncEntryState(isDirectory, size, updatedDate, storageReference)

internal fun syncSameContent(left: SyncEntryState?, right: SyncEntryState?): Boolean {
    if (left == null || right == null) return left == right
    if (left.isDirectory != right.isDirectory) return false
    if (left.isDirectory) return true
    if (left.size < 0 || right.size < 0 || left.size != right.size) return false
    if (left.contentSha256.isNotEmpty() && right.contentSha256.isNotEmpty()) return left.contentSha256 == right.contentSha256
    return left.modifiedAt > 0 && right.modifiedAt > 0 && kotlin.math.abs(left.modifiedAt - right.modifiedAt) <= 2_000
}

/** Ordered participants start with the source hub; the remaining order defines target priority. */
internal fun planSync(
    task: SyncTask,
    participants: List<SyncParticipantScan>,
    baselines: Map<String, SyncBaseline>,
    conflictStamp: String,
    contentMatches: Set<SyncContentMatch> = emptySet(),
    ignored: (String, Boolean) -> Boolean = { _, _ -> false },
    conflictWord: String = "conflict",
    scopeRelativePath: (String) -> String = { it },
    selectedSourceTypes: Map<String, Boolean> = emptyMap(),
): SyncPlan {
    require(participants.size >= 2 && participants.map { it.id }.toSet().size == participants.size)
    if (task.mode == SyncMode.Update) return planUpdateParticipants(task, participants, ignored, scopeRelativePath, selectedSourceTypes)
    val source = participants.first().id
    val targets = participants.drop(1).map { it.id }
    val byId = participants.associateBy { it.id }
    val occupied = participants.associate { it.id to it.scan.occupied.keys.toMutableSet() }
    val protected = participants.flatMap { it.scan.protectedPaths }.toSet()
    val protectedAncestors = syncParentPaths(protected)
    val contentByPath = contentMatches.groupBy { it.relativePath }
    val includes = parseSyncFilterPatterns(task.includeRaw)
    val excludes = parseSyncFilterPatterns(task.filterRaw)
    val matchingFileParents = syncParentPaths(participants.flatMap { participant ->
        participant.scan.entries.filterValues { !it.isDirectory }.keys
    })
    val allPaths = (participants.flatMap { it.scan.entries.keys } + baselines.keys).toSet()
        .sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it })
    val result = mutableListOf<SyncPathPlan>()
    val replacedDirectories = mutableSetOf<String>()
    val rebuiltDirectories = mutableMapOf<String, Set<String>>()
    val changedAncestors = participants.associate { participant -> participant.id to syncParentPaths(allPaths.filter { path ->
        !syncEntryUnchanged(baselines[path]?.participants?.get(participant.id), participant.state(path))
    }) }
    val unknown = participants.flatMap { participant -> participant.scan.entries.filterValues {
        !it.isDirectory
    }.keys.filter { path -> participant.state(path)!!.let { it.size < 0 || it.modifiedAt <= 0 && it.contentSha256.isEmpty() } } }.toSet()
    val unknownAncestors = syncParentPaths(unknown)
    val blockedStructuralPaths = mutableSetOf<String>()
    val backupRedirects = targets.associateWith { mutableMapOf<String, String>() }
    val blockedBackupTargets = targets.associateWith { mutableSetOf<String>() }

    fun backupDestination(target: String, path: String): String {
        var prefix = path
        while (prefix.isNotEmpty()) {
            val replacement = backupRedirects.getValue(target)[prefix]
            if (replacement != null) return replacement + path.removePrefix(prefix)
            prefix = prefix.substringBeforeLast('/', "")
        }
        return path
    }

    fun rebuiltAtParent(id: String, path: String): Boolean {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (id in rebuiltDirectories[parent].orEmpty()) return true
            parent = parent.substringBeforeLast('/', "")
        }
        return false
    }

    fun allowed(path: String, directory: Boolean): Boolean {
        if (path.startsWith('/') || '\\' in path || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        if (syncReservedPath(path) || !task.includeSubdirectories && '/' in scopeRelativePath(path)) return false
        var candidate = path
        while (candidate.isNotEmpty()) {
            val isDirectory = candidate != path || directory
            if (syncFilterMatches(candidate, candidate.substringAfterLast('/'), excludes) || ignored(candidate, isDirectory)) return false
            candidate = candidate.substringBeforeLast('/', "")
        }
        return directory || includes.isEmpty() || syncFilterMatches(path, path.substringAfterLast('/'), includes)
    }
    fun same(path: String, left: String, right: String, states: Map<String, SyncEntryState?>): Boolean =
        syncSameContent(states[left], states[right]) || contentByPath[path].orEmpty().any { match ->
            match.relativePath == path && (match.left == left && match.right == right &&
                match.leftState == states[left] && match.rightState == states[right] ||
                match.left == right && match.right == left && match.leftState == states[right] && match.rightState == states[left])
        }
    fun unused(path: String, directory: Boolean, ids: List<String>): String {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "")
        val (base, extension) = if (directory) name to "" else splitFileNameAndExt(name)
        var candidate = path
        var number = 1
        while (ids.any { candidate in occupied.getValue(it) }) {
            val leaf = "$base(${number++})$extension"
            candidate = if (parent.isEmpty()) leaf else "$parent/$leaf"
        }
        ids.forEach { occupied.getValue(it).add(candidate) }
        return candidate
    }

    for (path in allPaths) {
        val states = participants.associate { it.id to it.state(path) }
        val directory = states.values.firstOrNull { it != null }?.isDirectory ?: baselines[path]?.participants?.values?.firstOrNull { it != null }?.isDirectory ?: false
        if (!allowed(path, directory) || syncPathProtected(path, protected) || syncPathProtected(path, blockedStructuralPaths) ||
            states.values.any { it?.isDirectory == false } && path in protectedAncestors) {
            result += SyncPathPlan(path, states, emptyList(), skipped = true)
            continue
        }
        val selectedType = selectedSourceTypes[path]
        if (selectedType != null && states.values.any { it != null && it.isDirectory != selectedType }) {
            blockedStructuralPaths.add(path)
            result += SyncPathPlan(path, states, emptyList(), conflict = true, skipped = true)
            continue
        }
        // Selected directories are physical roots; their children carry bidirectional changes independently.
        if (selectedType == true && task.mode == SyncMode.Bidirectional && baselines[path] != null) {
            result += SyncPathPlan(path, states, emptyList())
            continue
        }
        val removedParent = replacedDirectories.firstOrNull { path.startsWith("$it/") }
        if (removedParent != null) {
            result += SyncPathPlan(path, states, emptyList(), removedWithParent = removedParent)
            continue
        }
        val operations = mutableListOf<SyncPlannedOperation>()
        val baseline = baselines[path]
        val backupPaths = linkedMapOf<String, String>()
        var skipped = false
        var conflict = false
        fun applyVersion(origin: String?, receiver: String, destination: String = path, conflictCopy: Boolean = false) {
            if (origin == receiver && destination == path) return
            val desired = origin?.let { states[it] }
            val existing = byId.getValue(receiver).state(destination)
            if (destination == path && origin != null && same(path, origin, receiver, states)) return
            if (desired == null) {
                if (existing != null) operations += SyncPlannedOperation(
                    if (existing.isDirectory) SyncOperationKind.CleanEmptyDirectory else SyncOperationKind.Delete,
                    receiver, destination, expectedTarget = existing)
            } else if (desired.isDirectory) {
                if (existing?.isDirectory != true && (task.includeEmptyDirectories ||
                        byId.getValue(origin).scan.nonEmptyDirectories.contains(path))) {
                    operations += SyncPlannedOperation(SyncOperationKind.CreateDirectory, receiver, destination,
                        origin, path, desired, existing)
                }
            } else {
                operations += SyncPlannedOperation(if (conflictCopy) SyncOperationKind.ConflictCopy else if (existing == null) SyncOperationKind.Copy else SyncOperationKind.Overwrite,
                    receiver, destination, origin, path, desired, existing)
            }
        }
        when (task.mode) {
            SyncMode.Update -> error("Update is planned by the shared Update planner")
            SyncMode.Mirror -> {
                for (target in targets) {
                    val original = states[source]
                    val existing = states[target]
                    val previous = baseline?.participants
                    if (original == null) applyVersion(null, target)
                    else if (existing == null || original.isDirectory != existing.isDirectory ||
                        previous != null && (!syncEntryUnchanged(previous[source], original) || !syncEntryUnchanged(previous[target], existing)) ||
                        original.size != existing.size || original.modifiedAt > 0 && existing.modifiedAt > 0 && original.modifiedAt - existing.modifiedAt > 2_000) {
                        applyVersion(source, target)
                    }
                }
            }
            SyncMode.BackupThenClean -> {
                val original = states[source]
                if (original?.isDirectory == true && (task.includeEmptyDirectories || path in byId.getValue(source).scan.nonEmptyDirectories)) for (target in targets) {
                    if (syncPathProtected(path, blockedBackupTargets.getValue(target))) { skipped = true; continue }
                    val previousCopy = baseline?.backupCopies?.get(target)
                    val previousActual = previousCopy?.let { byId.getValue(target).state(it.relativePath) }
                    val preferred = if (previousCopy != null && previousActual?.isDirectory == true && syncEntryUnchanged(previousCopy.source, original)) previousCopy.relativePath
                        else backupDestination(target, path)
                    val existing = byId.getValue(target).scan.occupied[preferred]
                    val actual = when {
                        existing?.isDirectory != false -> preferred
                        task.conflictPolicy == SyncConflictPolicy.Skip -> {
                            blockedBackupTargets.getValue(target).add(path)
                            skipped = true
                            continue
                        }
                        else -> unused(preferred, true, listOf(target))
                    }
                    backupRedirects.getValue(target)[path] = actual
                    backupPaths[target] = actual
                    applyVersion(source, target, actual)
                }
                else if (original?.isDirectory == false) {
                    for (target in targets) {
                        if (syncPathProtected(path, blockedBackupTargets.getValue(target))) { skipped = true; continue }
                        val preferred = backupDestination(target, path)
                        val existing = byId.getValue(target).state(preferred)
                        val previousCopy = baseline?.backupCopies?.get(target)
                        val previousActual = previousCopy?.let { byId.getValue(target).state(it.relativePath) }
                        when {
                            previousCopy != null && syncEntryUnchanged(previousCopy.source, original) && syncEntryUnchanged(previousCopy.target, previousActual) -> backupPaths[target] = previousCopy.relativePath
                            syncSameContent(original, existing) -> backupPaths[target] = preferred
                            existing == null -> { backupPaths[target] = preferred; applyVersion(source, target, preferred) }
                            task.conflictPolicy == SyncConflictPolicy.Skip -> skipped = true
                            else -> {
                                val destination = unused(preferred, false, listOf(target))
                                backupPaths[target] = destination
                                applyVersion(source, target, destination)
                            }
                        }
                    }
                    if (original.modifiedAt <= 0 && original.contentSha256.isEmpty() || original.size < 0) skipped = true
                    if (!skipped && backupPaths.size == targets.size) operations += SyncPlannedOperation(
                        SyncOperationKind.CleanSource, source, path, expectedSource = original, expectedTarget = original)
                }
            }
            SyncMode.Bidirectional -> {
                val established = baseline != null && participants.all { it.id in baseline.participants }
                val changed = if (established) participants.map { it.id }.filter { id ->
                    !(states[id] == null && rebuiltAtParent(id, path)) &&
                        (!syncEntryUnchanged(baseline.participants[id], states[id]) ||
                            states[id]?.isDirectory == true && path in changedAncestors.getValue(id))
                }
                    else participants.map { it.id }.filter { states[it] != null }
                val versions = mutableListOf<String>()
                for (id in changed) if (versions.none { same(path, it, id, states) }) versions.add(id)
                if (versions.isEmpty()) {
                    // A previously completed destination can be present even when the whole group baseline was not committed.
                    val present = participants.firstOrNull { states[it.id] != null }?.id
                    if (present != null && (!established || states.values.any { it == null } || participants.any { rebuiltAtParent(it.id, path) })) {
                        participants.forEach { applyVersion(present, it.id) }
                    }
                } else if (versions.size == 1) {
                    val winner = versions.single()
                    participants.forEach { applyVersion(winner, it.id) }
                } else {
                    conflict = true
                    val live = versions.filter { states[it] != null }
                    val policy = task.bidiConflictPolicy
                    val times = live.map { states.getValue(it)!!.modifiedAt }
                    val uniqueNewer = live.takeIf { times.all { time -> time > 0 } && times.distinct().size == times.size }
                        ?.maxByOrNull { states.getValue(it)!!.modifiedAt }
                    val selectedPriority = when (policy) {
                        SyncBidiConflictPolicy.Source -> source
                        SyncBidiConflictPolicy.Target -> targets.first()
                        SyncBidiConflictPolicy.Newer -> uniqueNewer
                        SyncBidiConflictPolicy.KeepBoth -> null
                    }
                    val priority = selectedPriority?.takeIf { established || states[it] != null }
                    if (priority != null) participants.forEach { applyVersion(priority, it.id) }
                    else if (live.isNotEmpty()) {
                        val winner = live.firstOrNull { states[it]?.isDirectory == true } ?: uniqueNewer ?: live.sorted().first()
                        for (version in live.filter { it != winner }) {
                            val file = states.getValue(version)!!
                            val name = path.substringAfterLast('/')
                            val parent = path.substringBeforeLast('/', "")
                            val (base, extension) = if (file.isDirectory) name to "" else splitFileNameAndExt(name)
                            val label = byId.getValue(version).label.replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
                            val conflictName = "$base ($conflictWord $label $conflictStamp)$extension"
                            val destination = unused(if (parent.isEmpty()) conflictName else "$parent/$conflictName", file.isDirectory, participants.map { it.id })
                            participants.forEach { applyVersion(version, it.id, destination, conflictCopy = true) }
                        }
                        participants.forEach { applyVersion(winner, it.id) }
                    }
                }
            }
        }
        if (operations.removeAll { it.kind == SyncOperationKind.CleanEmptyDirectory &&
                (it.relativePath in protectedAncestors || includes.isNotEmpty() && it.relativePath !in matchingFileParents) }) {
            skipped = true
        }
        if (task.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional) && (path in unknown || path in unknownAncestors)) {
            val blocked = operations.filter { it.kind in listOf(SyncOperationKind.Overwrite, SyncOperationKind.Delete, SyncOperationKind.CleanSource) ||
                it.kind == SyncOperationKind.CreateDirectory && it.expectedTarget != null }
            if (blocked.isNotEmpty()) {
                operations.removeAll(blocked.toSet())
                skipped = true
                if (states.values.any { it?.isDirectory == true } && states.values.any { it?.isDirectory == false }) blockedStructuralPaths.add(path)
            }
        }
        if (task.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional) &&
            (operations.any { it.kind == SyncOperationKind.Overwrite && it.relativePath == path && it.expectedTarget?.isDirectory == true } ||
                states.values.all { it?.isDirectory == false })) replacedDirectories.add(path)
        val rebuilt = operations.filter { it.kind == SyncOperationKind.CreateDirectory && it.relativePath == path }.mapTo(mutableSetOf()) { it.participant }
        if (rebuilt.isNotEmpty()) rebuiltDirectories[path] = rebuilt
        result += SyncPathPlan(path, states, operations, backupPaths, conflict, skipped)
    }
    val parentCreates = result.flatMap { it.operations }.filter { it.kind in listOf(SyncOperationKind.Copy,
        SyncOperationKind.Overwrite, SyncOperationKind.CreateDirectory, SyncOperationKind.ConflictCopy) && '/' in it.relativePath }
        .groupBy { it.participant }.mapValues { (_, operations) -> operations.mapTo(mutableSetOf()) { it.relativePath.substringBefore('/') } }
    return SyncPlan(result.map { entry ->
        if (task.mode != SyncMode.Bidirectional || selectedSourceTypes[entry.relativePath] != true || entry.skipped) entry
        else {
            val creates = targets.filter { id -> entry.expected[id] == null && entry.operations.none { it.participant == id } &&
                (task.includeEmptyDirectories || entry.relativePath in parentCreates[id].orEmpty()) }.map { id -> SyncPlannedOperation(SyncOperationKind.CreateDirectory, id, entry.relativePath,
                    source, entry.relativePath, entry.expected[source], null) }
            entry.copy(operations = entry.operations + creates)
        }
    })
}
