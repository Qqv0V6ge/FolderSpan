package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.service.sync.beforeSyncConditionFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import strings.AppStrings

internal data class SyncUpdateTarget(
    val endpoint: SyncRunEndpoint,
    val destination: StorageDestination,
    val snapshot: SyncTargetSnapshot,
    val plan: SyncUpdateDirectoryPlan,
    val replacementTrees: Map<String, Map<String, SyncEntryState>> = emptyMap(),
    val expected: Map<String, FileSimpleInfo?> = emptyMap(),
    val contentProofs: Map<String, SyncEntryState> = emptyMap(),
    val comparisonSnapshot: SyncTargetSnapshot = snapshot,
)

internal data class SyncPreparedUpdate(
    val source: SyncSourceSnapshot,
    val targets: List<SyncUpdateTarget>,
    val sourceDirectories: Map<String, FileSimpleInfo>,
)

/** Reads the original Update paths, including a file source and a missing target root. */
internal suspend fun SyncState.prepareUpdateSyncRun(task: SyncTask, operation: Task): Result<SyncPreparedRun> = try {
    val fingerprint = syncPreviewFingerprint(task)
    check(isTaskOwnedByCurrentDevice(task) && getTask(task.id)?.let(::syncPreviewFingerprint) == fingerprint) { AppStrings.sync_preview_expired }
    check(localStore?.recoveries(task.id).isNullOrEmpty()) { "Synchronization has pending recovery operations" }
    suspend fun connect(type: SyncEndpointType, ref: String, location: FileSimpleInfo): DiskBase {
        ensureOperationRunning(operation.key)
        val connection = task.copy(targetType = type, targetRef = ref, targetPath = location.path, targetStorageReference = location.storageReference)
        check(withTimeout(30_000) { targetEndpointMissingError(connection) ?: connectTargetEndpointIfNeeded(connection) } == null) {
            AppStrings.ui_target_endpoint_unavailable
        }
        return checkNotNull(resolveEndpoint(type, ref)) { AppStrings.ui_target_endpoint_unavailable }
    }
    val sourceDesk = connect(task.sourceType, task.sourceRef, sourceLocation(task))
    check(endpointFiles.capabilities(sourceDesk).getOrThrow().read) { AppStrings.ui_source_endpoint_unavailable }
    val sourceRoot = checkNotNull(endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk, task.sourcePath, sourceLocation(task)).getOrThrow()) {
        AppStrings.ui_source_path_does_not_exist
    }
    check(!sourceRoot.isSymbolicLink) { AppStrings.ui_storage_source_changed }
    val roots = task.resolvedSelectedSources().ifEmpty { listOf(sourceRoot) }
    val scopeSelections = task.resolvedSelectedSources().ifEmpty { if (sourceRoot.isDirectory) emptyList() else listOf(sourceRoot) }
    val sourceScope = SyncSourceScope(sourceRoot, scopeSelections, endpointFiles.endpointSeparator(task.sourceType, sourceDesk))
    val matchers = mutableMapOf<String, ResolvedIgnoreMatcher?>()
    val sources = roots.map { selected ->
        beforeSyncConditionFile()
        val fresh = checkNotNull(endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk, selected.path, selected).getOrThrow()) {
            AppStrings.ui_source_path_does_not_exist
        }
        check(!fresh.isSymbolicLink && fresh.isDirectory == selected.isDirectory) { AppStrings.ui_storage_source_changed }
        val prefix = if (task.selectedSources.isNotEmpty()) selected.name else ""
        val scan = collectSourceSnapshot(task, sourceDesk, fresh, parseSyncFilterPatterns(task.filterRaw), prefix).getOrThrow()
        if (fresh.isDirectory) matchers[prefix] = scan.ignoreMatcher
        scan
    }
    val source = SyncSourceSnapshot(sources.flatMap { it.files }, sources.flatMap { it.directories },
        sources.flatMapTo(mutableSetOf()) { it.protectedPaths }, sources.singleOrNull()?.ignoreMatcher)
    if (task.destinations.isNotEmpty()) require(source.files.map { it.relativePath }.distinct().size == source.files.size &&
        source.directories.distinct().size == source.directories.size) { AppStrings.ui_storage_duplicate_source }
    val sourceDirectories = mutableMapOf<String, FileSimpleInfo>()
    var comparisonBytes = 0L
    fun compared(bytes: Long) { comparisonBytes += bytes.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - comparisonBytes) }
    val configured = task.destinations.ifEmpty { listOf(StorageDestination(task.targetType, task.targetRef, task.targetPath, task.targetStorageReference)) }
    requireDistinctStorageDestinations(configured)
    val destinations = if (task.destinations.isNotEmpty() && task.selectRecommended) {
        val metadata = configured.map { networkState.refreshStorageMetadata(it) }
        listOf(checkNotNull(recommendStorageTargets(metadata, source.files.map { it.file.size }, task.preferredEntryIds)
            .firstOrNull { it.eligible }) { AppStrings.ui_storage_no_candidate }.metadata.destination)
    } else configured
    val scannedTargets = destinations.mapIndexed { index, destination ->
        for (selected in roots) validateTask(task.name, task.sourceType, task.sourceRef, selected.path,
            destination.type, destination.ref, destination.path, strictEndpointAvailability = false)?.let { error(it) }
        val location = FileSimpleInfo.pathFileSimpleInfo(destination.path).withCopy(protocol = toProtocol(destination.type),
            protocolId = destination.ref, storageReference = destination.storageReference)
        val desk = connect(destination.type, destination.ref, location)
        check(endpointFiles.capabilities(desk).getOrThrow().write) { AppStrings.ui_target_endpoint_unavailable }
        val targetTask = destination.applyTo(task)
        val separator = endpointFiles.endpointSeparator(destination.type, desk)
        val directorySource = sourceRoot.isDirectory || task.selectedSources.isNotEmpty() || task.destinations.isNotEmpty()
        val raw: SyncTargetSnapshot
        val plan: SyncUpdateDirectoryPlan
        val comparison: SyncTargetSnapshot
        if (directorySource) {
            raw = collectTargetSnapshot(targetTask, desk, destination.path, ignored = { path, directory ->
                sourceScope.ignored(path, directory, matchers)
            }).getOrThrow()
            comparison = raw.copy(filesByAbsolute = raw.filesByAbsolute.mapValues { (_, file) -> syncComparisonTargetFile(desk, file) }.toMutableMap())
            val root = planUpdateRoot(task, raw, separator, destination.path)
            plan = planSync(task, source, comparison, separator, destination.path, root = root)
        } else {
            val root = endpointFiles.readPathInfo(destination.type, destination.ref, desk, destination.path, location).getOrThrow()
            val path = if (root?.isDirectory == true) joinPath(root.path, sourceRoot.name, separator) else destination.path
            val parent = parentPathOf(path, separator)
            val existing = endpointFiles.readPathInfo(destination.type, destination.ref, desk, path, location).getOrThrow()
            val siblings = endpointFiles.listChildren(destination.type, destination.ref, desk, parent, location)
                .getOrElse { if (existing != null) throw it else emptyList() }
            val files = (siblings + listOfNotNull(existing)).associateBy { it.path }
            val parentInfo = endpointFiles.readPathInfo(destination.type, destination.ref, desk, parent, location).getOrThrow()
            raw = SyncTargetSnapshot(parentInfo, mutableMapOf(), files.filterValues { !it.isDirectory }.toMutableMap(),
                files.filterValues { it.isDirectory }.keys.toMutableSet(), files.keys.toMutableSet())
            comparison = raw.copy(filesByAbsolute = raw.filesByAbsolute.mapValues { (_, file) -> syncComparisonTargetFile(desk, file) }.toMutableMap())
            plan = if (source.files.isEmpty()) SyncUpdateDirectoryPlan(
                SyncUpdateRootPlan(parent, parentInfo, SyncUpdateRootAction.EnsureDirectory), emptyList(), ensureRoot = false)
            else planSync(task, sourceRoot, comparison, path, separator)
        }
        val root = raw.rootInfo?.takeIf { it.path == plan.root.path } ?: location.withCopy(path = plan.root.path, isDirectory = true, storageReference = null)
        SyncUpdateTarget(SyncRunEndpoint("target-$index", destination.type, destination.ref, desk, root),
            destination, raw, plan, comparisonSnapshot = comparison)
    }
    val protected = source.protectedPaths + scannedTargets.flatMap { it.snapshot.protectedPaths }
    val typeConflicts = scannedTargets.flatMapTo(mutableSetOf()) {
        syncSelectedTypeConflicts(task, it.snapshot, it.plan.root.path, endpointFiles.endpointSeparator(it.endpoint.type, it.endpoint.desk))
    }
    val targets = scannedTargets.map { scanned ->
        val destination = scanned.destination
        val endpoint = scanned.endpoint
        val desk = endpoint.desk
        val raw = scanned.snapshot
        val separator = endpointFiles.endpointSeparator(destination.type, desk)
        val location = FileSimpleInfo.pathFileSimpleInfo(destination.path).withCopy(protocol = toProtocol(destination.type),
            protocolId = destination.ref, storageReference = destination.storageReference)
        val plan = if (task.destinations.isEmpty()) scanned.plan else planSync(task,
            source.copy(protectedPaths = protected), scanned.comparisonSnapshot, separator, destination.path,
            root = scanned.plan.root, selectedTypeConflicts = typeConflicts)
        for (item in plan.items.filter { it.action == SyncPlanAction.CreateDirectory }) if (item.relativePath !in sourceDirectories) {
            beforeSyncConditionFile()
            val hint = sourceScope.location(item.relativePath)
            val directory = endpointFiles.readPathInfo(task.sourceType, task.sourceRef, sourceDesk, hint.path, hint).getOrThrow()
            check(directory?.isDirectory == true && !directory.isSymbolicLink) { AppStrings.sync_preview_expired }
            sourceDirectories[item.relativePath] = directory
        }
        val recoveryEndpoint = SyncRecoveryEndpoint(destination.type, destination.ref, plan.root.path, endpoint.root.storageReference, endpoint.connectionBinding)
        val io = SyncRecoveryEndpointOperations(endpointFiles, operation, mapOf(recoveryEndpoint to desk))
        val trees = plan.items.filter { it.targetIsDirectory && it.action == SyncPlanAction.Copy }.associate { item ->
            item.targetPath to io.verifiedTree(recoveryEndpoint, item.targetPath).also { tree ->
                tree.values.filter { !it.isDirectory }.forEach { compared(it.size) }
            }
        }
        val expected = linkedMapOf<String, FileSimpleInfo?>()
        val itemPaths = plan.items.filter { it.action != SyncPlanAction.Skip && it.action != SyncPlanAction.Unchanged }
            .mapTo(mutableSetOf()) { it.targetPath }
        for (path in listOf(plan.root.path) + itemPaths) {
            var current = path
            while (current !in expected) {
                val hint = raw.filesByAbsolute[current] ?: raw.rootInfo?.takeIf { it.path == current }
                val actual = endpointFiles.readPathInfo(destination.type, destination.ref, desk, current,
                    hint ?: location.withCopy(path = current, storageReference = null)).getOrThrow()
                check(actual?.isSymbolicLink != true) { AppStrings.ui_storage_source_changed }
                if (hint != null) check(actual != null && actual.isDirectory == hint.isDirectory &&
                    actual.storageReference == hint.storageReference && (hint.isDirectory || actual.size == hint.size && actual.updatedDate == hint.updatedDate)) {
                    AppStrings.sync_preview_expired
                }
                if (current == plan.root.path && plan.root.expected == null) check(actual == null) { AppStrings.sync_preview_expired }
                if (current in itemPaths && current !in raw.occupiedPaths) check(actual == null) { AppStrings.sync_preview_expired }
                expected[current] = actual
                val parent = parentPathOf(current, separator)
                if (parent == current) break
                current = parent
            }
        }
        val writes = plan.items.filter { it.action in listOf(SyncPlanAction.Copy, SyncPlanAction.RenameCopy, SyncPlanAction.CreateDirectory) }
            .mapTo(mutableSetOf()) { it.targetPath }
        if (plan.root.action == SyncUpdateRootAction.ReplaceFile) writes.add(plan.root.path)
        val proofs = expected.filter { (path, file) -> path in writes && file != null && !file.isDirectory && file.updatedDate <= 0 }.mapValues { (_, file) ->
            beforeSyncConditionFile()
            readVerifiedSyncEndpointState(endpointFiles, destination.type, destination.ref, desk,
                syncComparisonTargetFile(desk, checkNotNull(file)), operation, onRead = { compared(it.toLong()) }).getOrThrow()
        }
        scanned.copy(plan = plan, replacementTrees = trees, expected = expected, contentProofs = proofs)
    }
    val sourceEndpoint = SyncRunEndpoint("source", task.sourceType, task.sourceRef, sourceDesk, sourceRoot)
    val paths = targets.flatMap { target ->
        val plan = target.plan
        val root = plan.root
        val rootOperation = if (root.action == SyncUpdateRootAction.Skip) listOf(SyncPathPlan("", emptyMap(), emptyList(), skipped = true))
        else if (plan.ensureRoot && root.expected?.isDirectory != true) listOf(SyncPathPlan("", emptyMap(),
            listOf(SyncPlannedOperation(SyncOperationKind.CreateDirectory, target.endpoint.id, "", expectedTarget = root.expected?.syncEntryState())))) else emptyList()
        rootOperation + plan.operations("source", target.endpoint.id, target.snapshot, endpointFiles.endpointSeparator(target.endpoint.type, target.endpoint.desk), target.expected, sourceDirectories)
    }
    check(isTaskOwnedByCurrentDevice(task) && getTask(task.id)?.let(::syncPreviewFingerprint) == fingerprint) { AppStrings.sync_preview_expired }
    Result.success(SyncPreparedRun(task, fingerprint, listOf(sourceEndpoint) + targets.map { it.endpoint },
        sourceScope, SyncPreparedPlan(emptyList(), emptyMap(), SyncPlan(paths), comparisonBytes),
        localStore?.localState(task.id), SyncPreparedUpdate(source, targets, sourceDirectories)))
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
